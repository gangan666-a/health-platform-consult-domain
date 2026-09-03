package com.example.mall.agentservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 网关：故障转移、熔断开闸跳过、限流拒绝、计量采集。 */
class RoutedChatModelTest {

    private static ChatModel okModel(String marker) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(marker))));
    }

    private static ChatModel countingFailingModel(AtomicInteger calls) {
        return prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider down");
        };
    }

    /** 宽松限流（不干扰故障转移/熔断用例）。 */
    private static RateLimiter permissiveLimiter(String name) {
        return RateLimiter.of(name, RateLimiterConfig.custom()
                .limitForPeriod(1000)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ofSeconds(2))
                .build());
    }

    @Test
    void primaryOk_returnsDirectly() {
        LlmUsageMeter meter = newMeter();
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("primary", okModel("p"), permissiveLimiter("a"), breaker("a"), List.of())), meter);

        assertEquals("p", router.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals(1, meter.snapshot().totals().get("primary").calls());
    }

    @Test
    void primaryFails_failsOverToSecondary() {
        LlmUsageMeter meter = newMeter();
        AtomicInteger primaryCalls = new AtomicInteger();
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("primary", countingFailingModel(primaryCalls),
                        permissiveLimiter("b"), breaker("b"), List.of()),
                new RoutedChatModel.Candidate("backup", okModel("ok"), permissiveLimiter("c"), breaker("c"), List.of())), meter);

        assertEquals("ok", router.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, meter.snapshot().totals().get("primary").failures());
        assertEquals(1, meter.snapshot().totals().get("backup").calls());
    }

    @Test
    void circuitOpens_skipsPrimaryEntirely() {
        LlmUsageMeter meter = newMeter();
        AtomicInteger primaryCalls = new AtomicInteger();
        // 小窗口：2 次调用即统计，失败率 50% 开闸
        CircuitBreaker quick = CircuitBreaker.of("quick", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("primary", countingFailingModel(primaryCalls),
                        permissiveLimiter("d"), quick, List.of()),
                new RoutedChatModel.Candidate("backup", okModel("ok"), permissiveLimiter("e"), breaker("e"), List.of())), meter);

        router.call(new Prompt("1")); // primary 失败 → backup
        router.call(new Prompt("2")); // primary 失败 → backup；此时窗口满、失败率 100% → 开闸
        int callsAfterOpen = primaryCalls.get();
        router.call(new Prompt("3")); // primary 熔断开闸 → 不再实际调用 primary

        assertEquals("ok", router.call(new Prompt("4")).getResult().getOutput().getText());
        assertEquals(callsAfterOpen, primaryCalls.get(), "熔断开后 primary 不应再被实际调用");
    }

    @Test
    void rateLimited_failsOverOrThrows() throws InterruptedException {
        LlmUsageMeter meter = newMeter();
        // 限流：每 200ms 1 个许可，等待 50ms 超时
        RateLimiter tight = RateLimiter.of("tight", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(200))
                .timeoutDuration(Duration.ofMillis(50))
                .build());
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("primary", okModel("p"), tight, breaker("f"), List.of())), meter);

        assertEquals("p", router.call(new Prompt("1")).getResult().getOutput().getText());
        long t0 = System.nanoTime();
        assertThrows(RuntimeException.class, () -> router.call(new Prompt("2")));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 190, "限流拒绝应快速失败而非长阻塞");
    }

    @Test
    void usageRecordedFromResponseMetadata() {
        LlmUsageMeter meter = newMeter();
        ChatModel withUsage = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .model("deepseek-chat")
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(10, 20, 30))
                        .build());
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("primary", withUsage, permissiveLimiter("g"), breaker("g"), List.of())), meter);

        router.call(new Prompt("hi"));

        LlmUsageMeter.ProviderTotals totals = meter.snapshot().totals().get("primary");
        assertEquals(10, totals.promptTokens());
        assertEquals(20, totals.completionTokens());
    }

    // ---- 模型亲和路由 ----

    @Test
    void affinity_routesModelToDeclaringProviderWithoutFailedAttempt() {
        LlmUsageMeter meter = newMeter();
        AtomicInteger deepseekCalls = new AtomicInteger();
        ChatModel deepseek = prompt -> {
            deepseekCalls.incrementAndGet();
            throw new IllegalStateException("deepseek 不托管 glm 模型");
        };
        ChatModel zhipu = okModel("glm-ok");
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("deepseek", deepseek, permissiveLimiter("h"), breaker("h"),
                        List.of("deepseek-chat", "deepseek-reasoner")),
                new RoutedChatModel.Candidate("zhipu", zhipu, permissiveLimiter("i"), breaker("i"),
                        List.of("glm-4.7-flash", "glm-4.6v-flash", "glm-4.6v"))), meter);

        String answer = router.call(new Prompt("hi",
                        org.springframework.ai.openai.OpenAiChatOptions.builder().model("glm-4.7-flash").build()))
                .getResult().getOutput().getText();

        assertEquals("glm-ok", answer);
        assertEquals(0, deepseekCalls.get(), "亲和路由不应先打到不托管该模型的供应商");
        assertEquals(1, meter.snapshot().totals().get("zhipu").calls());
    }

    @Test
    void affinity_providerFails_stillFailsOver() {
        LlmUsageMeter meter = newMeter();
        ChatModel brokenZhipu = prompt -> {
            throw new IllegalStateException("zhipu down");
        };
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("deepseek", okModel("ds-ok"), permissiveLimiter("j"), breaker("j"),
                        List.of("deepseek-chat")),
                new RoutedChatModel.Candidate("zhipu", brokenZhipu, permissiveLimiter("k"), breaker("k"),
                        List.of("glm-4.7-flash"))), meter);

        String answer = router.call(new Prompt("hi",
                        org.springframework.ai.openai.OpenAiChatOptions.builder().model("glm-4.7-flash").build()))
                .getResult().getOutput().getText();

        assertEquals("ds-ok", answer, "亲和供应商失败仍应故障转移到其余候选");
    }

    @Test
    void affinity_unknownModel_keepsDefaultOrder() {
        RoutedChatModel router = new RoutedChatModel(List.of(
                new RoutedChatModel.Candidate("deepseek", okModel("ds-ok"), permissiveLimiter("l"), breaker("l"),
                        List.of("deepseek-chat")),
                new RoutedChatModel.Candidate("zhipu", okModel("glm-ok"), permissiveLimiter("m"), breaker("m"),
                        List.of("glm-4.7-flash"))), newMeter());

        assertEquals(List.of("deepseek", "zhipu"),
                router.orderedCandidates("totally-unknown-model").stream()
                        .map(RoutedChatModel.Candidate::name).toList());
        assertEquals(List.of("deepseek", "zhipu"), router.orderedCandidates(null).stream()
                .map(RoutedChatModel.Candidate::name).toList());
        assertEquals(List.of("zhipu", "deepseek"), router.orderedCandidates("glm-4.7-flash").stream()
                .map(RoutedChatModel.Candidate::name).toList());
    }

    private static LlmUsageMeter newMeter() {
        return new LlmUsageMeter(new SimpleMeterRegistry(), new LlmProviderProperties());
    }

    private static CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    }
}
