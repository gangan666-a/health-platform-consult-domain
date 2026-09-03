package com.example.mall.agentservice.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 统一网关（@Primary ChatModel）：**模型亲和路由** + 按优先级故障转移 + 每供应商限流/熔断 + 无侵入计量。
 * 亲和规则：请求携带模型名（options.model）且某候选供应商声明托管该模型 → 优先路由给该供应商，
 * 其余供应商排在后面作故障转移——避免「glm 模型名先打到 DeepSeek 报错再转移」的白烧调用与熔断污染。
 * 重试策略分层：429/5xx 指数退避重试由 Spring AI RetryTemplate 在各供应商模型内部完成
 * （OpenAiChatModel 内建），网关不叠加 Retry，避免双重重试放大时延；
 * 网关负责限流（保护配额）、熔断（快速失败）与跨供应商故障转移。
 * 全部供应商失败时抛出最后一个异常，由上游既有兜底接住（关键词规则/TextLayerParser/stubReply）。
 * 便捷方法 call(String)/call(Message...) 由接口默认实现路由到 call(Prompt)，本类无需覆写。
 */
public class RoutedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutedChatModel.class);

    /** 供应商候选：委托模型 + 独立限流器/熔断器 + 托管模型清单（亲和路由依据）。 */
    public record Candidate(String name, ChatModel delegate, RateLimiter limiter, CircuitBreaker breaker,
                            List<String> models) {
    }

    private final List<Candidate> candidates;
    private final LlmUsageMeter meter;

    public RoutedChatModel(List<Candidate> candidates, LlmUsageMeter meter) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("LLM 供应商列表为空");
        }
        this.candidates = List.copyOf(candidates);
        this.meter = meter;
        log.info("LLM 网关就绪：{} 个供应商（{}）", candidates.size(),
                candidates.stream().map(Candidate::name).toList());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        for (Candidate candidate : orderedCandidates(requestedModel(prompt))) {
            long start = System.nanoTime();
            try {
                if (!candidate.limiter().acquirePermission()) {
                    meter.recordFailure(candidate.name(), elapsedMs(start), "限流等待超时");
                    last = new IllegalStateException(candidate.name() + " 限流等待超时");
                    continue;
                }
                ChatResponse response = candidate.breaker()
                        .executeSupplier(() -> candidate.delegate().call(prompt));
                meter.recordSuccess(candidate.name(), response, elapsedMs(start));
                return response;
            } catch (CallNotPermittedException e) {
                meter.recordFailure(candidate.name(), elapsedMs(start), "熔断开闸跳过");
                log.warn("供应商 {} 熔断中，故障转移下一供应商", candidate.name());
                last = e;
            } catch (RuntimeException e) {
                meter.recordFailure(candidate.name(), elapsedMs(start), e.getClass().getSimpleName());
                log.warn("供应商 {} 调用失败（{}），尝试下一供应商", candidate.name(), e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("无可用 LLM 供应商");
    }

    /** 模型亲和排序：声明托管请求模型的候选排前（保持原相对序），其余候选随后作故障转移。 */
    List<Candidate> orderedCandidates(String model) {
        if (model == null || model.isBlank()) {
            return candidates;
        }
        List<Candidate> affinity = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (Candidate candidate : candidates) {
            (candidate.models() != null && candidate.models().contains(model) ? affinity : rest).add(candidate);
        }
        return affinity.isEmpty() ? candidates : concat(affinity, rest);
    }

    private static String requestedModel(Prompt prompt) {
        if (prompt == null || prompt.getOptions() == null) {
            return null;
        }
        return prompt.getOptions().getModel();
    }

    private static List<Candidate> concat(List<Candidate> a, List<Candidate> b) {
        List<Candidate> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return candidates.getFirst().delegate().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return candidates.getFirst().delegate().getDefaultOptions();
    }

    /** 供应商名列表（诊断用）。 */
    public List<String> providerNames() {
        return candidates.stream().map(Candidate::name).toList();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
