package com.example.mall.agentservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;/**
 * LLM 路由装配：主供应商 = 自动装配的 OpenAiChatModel（spring.ai.openai.* 即 DeepSeek），
 * 备用供应商 = agent.llm.providers[*] 手动构建（OpenAI 兼容协议），key 留空自动不注册。
 * @Primary 收口：全应用注入的 ChatModel 均为网关，限流/熔断/故障转移/计量无侵入生效。
 */
@Configuration
@EnableConfigurationProperties({LlmProviderProperties.class, ReportTaskProperties.class,
        EmbeddingProperties.class, VisionProperties.class})
public class LlmRoutingConfig {

    @Bean
    @Primary
    ChatModel routedChatModel(ObjectProvider<OpenAiChatModel> primary,
                              LlmProviderProperties properties, LlmUsageMeter meter,
                              List<AlertPublisher> alertPublishers) {
        List<RoutedChatModel.Candidate> candidates = new ArrayList<>();
        primary.ifAvailable(model -> candidates.add(
                candidate(properties.getPrimaryName(), model, properties.getPrimaryModels(), properties, alertPublishers)));
        for (LlmProviderProperties.Provider provider : properties.getEnabledProviders()) {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(provider.getBaseUrl())
                    .apiKey(provider.getApiKey())
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().model(provider.getModel()).build())
                    .build();
            candidates.add(candidate(provider.getName(), model, provider.getModels(), properties, alertPublishers));
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("未配置任何 LLM 供应商（spring.ai.openai 自动装配缺失）");
        }
        return new RoutedChatModel(candidates, meter);
    }

    private static RoutedChatModel.Candidate candidate(String name, ChatModel model, List<String> hostedModels,
                                                       LlmProviderProperties properties,
                                                       List<AlertPublisher> alertPublishers) {
        RateLimiterConfig limiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(properties.getRateLimiter().getLimitForPeriod())
                .limitRefreshPeriod(properties.getRateLimiter().getLimitRefreshPeriod())
                .timeoutDuration(properties.getRateLimiter().getTimeoutDuration())
                .build();
        CircuitBreaker breaker = CircuitBreaker.of(name + "-circuit-breaker", CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getCircuitBreaker().getFailureRateThreshold())
                .waitDurationInOpenState(properties.getCircuitBreaker().getWaitDurationInOpenState())
                .slidingWindowSize(properties.getCircuitBreaker().getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getCircuitBreaker().getMinimumNumberOfCalls())
                .build());
        // 熔断状态变更即告警（开闸=该供应商已熔断、半开=试探恢复）：日志告警 + Webhook（可选）
        breaker.getEventPublisher().onStateTransition(event ->
                alertPublishers.forEach(publisher -> publisher.publish("WARN", "LLM 供应商熔断状态变更",
                        name + ": " + event.getStateTransition())));
        return new RoutedChatModel.Candidate(name, model,
                RateLimiter.of(name + "-rate-limiter", limiterConfig), breaker,
                hostedModels == null ? List.of() : hostedModels);
    }
}
