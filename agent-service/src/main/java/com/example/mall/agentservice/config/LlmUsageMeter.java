package com.example.mall.agentservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用计量（在 RoutedChatModel 收口处无侵入采集，全部 Agent/分类器/规划器/抽取器共用）：
 * ① Micrometer：agent.llm.calls{provider,result} 计数 + agent.llm.call.duration{provider} 计时
 *   + agent.llm.tokens{provider,model,type} token 计数（/actuator/metrics 可查）；
 * ② 内存环形队列保留最近调用明细 + 按供应商累计（GET /api/agents/llm-usage 可查，含单价成本估算）。
 */
@Component
public class LlmUsageMeter {

    /** 单次调用明细。 */
    public record LlmCallRecord(Instant time, String provider, String model, long promptTokens,
                                long completionTokens, long latencyMs, boolean ok, String note) {
    }

    /** 供应商累计。 */
    public record ProviderTotals(long calls, long failures, long promptTokens,
                                 long completionTokens, double estimatedCost) {
    }

    /** 端点快照。 */
    public record UsageSnapshot(List<LlmCallRecord> recent, Map<String, ProviderTotals> totals) {
    }

    private static final int RECENT_LIMIT = 200;

    private static final class Accumulator {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
    }

    private final MeterRegistry registry;
    private final LlmProviderProperties properties;
    private final Map<String, Accumulator> totals = new ConcurrentHashMap<>();
    private final Deque<LlmCallRecord> recent = new ArrayDeque<>();

    public LlmUsageMeter(MeterRegistry registry, LlmProviderProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /** 成功调用：从 ChatResponse 读取 usage 计量。 */
    public void recordSuccess(String provider, org.springframework.ai.chat.model.ChatResponse response,
                              long latencyMs) {
        long promptTokens = 0;
        long completionTokens = 0;
        String model = "";
        if (response != null && response.getMetadata() != null) {
            model = response.getMetadata().getModel() == null ? "" : response.getMetadata().getModel();
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = orZero(usage.getPromptTokens());
                completionTokens = orZero(usage.getCompletionTokens());
            }
        }
        Accumulator acc = totals.computeIfAbsent(provider, k -> new Accumulator());
        acc.calls.incrementAndGet();
        acc.promptTokens.addAndGet(promptTokens);
        acc.completionTokens.addAndGet(completionTokens);
        append(new LlmCallRecord(Instant.now(), provider, model, promptTokens, completionTokens,
                latencyMs, true, ""));
        registry.counter("agent.llm.calls", "provider", provider, "result", "success").increment();
        registry.counter("agent.llm.tokens", "provider", provider, "model", model, "type", "prompt")
                .increment(promptTokens);
        registry.counter("agent.llm.tokens", "provider", provider, "model", model, "type", "completion")
                .increment(completionTokens);
        Timer.builder("agent.llm.call.duration").tag("provider", provider)
                .register(registry).record(java.time.Duration.ofMillis(latencyMs));
    }

    /** 失败调用（限流等待超时/熔断开闸/供应商异常）。 */
    public void recordFailure(String provider, long latencyMs, String note) {
        Accumulator acc = totals.computeIfAbsent(provider, k -> new Accumulator());
        acc.calls.incrementAndGet();
        acc.failures.incrementAndGet();
        append(new LlmCallRecord(Instant.now(), provider, "", 0, 0, latencyMs, false, note));
        registry.counter("agent.llm.calls", "provider", provider, "result", "failure").increment();
    }

    public synchronized UsageSnapshot snapshot() {
        List<LlmCallRecord> recentList = new ArrayList<>(recent);
        Map<String, ProviderTotals> totalsView = new LinkedHashMap<>();
        totals.forEach((provider, acc) -> totalsView.put(provider, new ProviderTotals(
                acc.calls.get(), acc.failures.get(), acc.promptTokens.get(), acc.completionTokens.get(),
                estimatedCost(provider, acc.promptTokens.get(), acc.completionTokens.get()))));
        return new UsageSnapshot(recentList, totalsView);
    }

    private synchronized void append(LlmCallRecord record) {
        recent.addLast(record);
        while (recent.size() > RECENT_LIMIT) {
            recent.removeFirst();
        }
    }

    private double estimatedCost(String provider, long promptTokens, long completionTokens) {
        LlmProviderProperties.Price price = properties.getPrices().get(provider);
        if (price == null) {
            return 0;
        }
        return price.getInputPerMillion() * promptTokens / 1_000_000
                + price.getOutputPerMillion() * completionTokens / 1_000_000;
    }

    private static long orZero(Number value) {
        return value == null ? 0 : value.longValue();
    }
}
