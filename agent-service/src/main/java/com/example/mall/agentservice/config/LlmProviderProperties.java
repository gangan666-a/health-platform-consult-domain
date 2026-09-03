package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 网关配置：主供应商（复用 spring.ai.openai 自动装配的 DeepSeek）+ 备用供应商列表
 * （全部 OpenAI 兼容协议，key 留空/enable=false 自动不注册）；限流/熔断参数与计量单价。
 */
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProviderProperties {

    /** 主供应商显示名（用于计量/日志标注），底层为自动装配的 spring.ai.openai 客户端。 */
    private String primaryName = "deepseek";

    /** 主供应商托管的模型清单（模型亲和路由：请求模型名命中者优先路由，如 deepseek-chat/deepseek-reasoner）。 */
    private List<String> primaryModels = new ArrayList<>();

    /** 备用供应商（OpenAI 兼容）；baseUrl/apiKey 留空则跳过注册。 */
    private List<Provider> providers = new ArrayList<>();

    /** 每供应商限流：保护服务商 QPM 配额（令牌桶）。 */
    private RateLimiter rateLimiter = new RateLimiter();

    /** 每供应商熔断：失败率超阈值开闸，快速故障转移到下一供应商。 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** 计量单价（每百万 token，估算成本用；币种自定，默认 0 不估算）。key = 供应商名。 */
    private Map<String, Price> prices = new HashMap<>();

    public static class Provider {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        private boolean enabled = true;
        /** 该供应商托管的模型清单（模型亲和路由用）。 */
        private List<String> models = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }
    }

    public static class RateLimiter {
        private int limitForPeriod = 30;
        private Duration limitRefreshPeriod = Duration.ofSeconds(10);
        private Duration timeoutDuration = Duration.ofSeconds(5);

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public Duration getLimitRefreshPeriod() {
            return limitRefreshPeriod;
        }

        public void setLimitRefreshPeriod(Duration limitRefreshPeriod) {
            this.limitRefreshPeriod = limitRefreshPeriod;
        }

        public Duration getTimeoutDuration() {
            return timeoutDuration;
        }

        public void setTimeoutDuration(Duration timeoutDuration) {
            this.timeoutDuration = timeoutDuration;
        }
    }

    public static class CircuitBreaker {
        private float failureRateThreshold = 50f;
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }
    }

    public static class Price {
        private double inputPerMillion;
        private double outputPerMillion;

        public double getInputPerMillion() {
            return inputPerMillion;
        }

        public void setInputPerMillion(double inputPerMillion) {
            this.inputPerMillion = inputPerMillion;
        }

        public double getOutputPerMillion() {
            return outputPerMillion;
        }

        public void setOutputPerMillion(double outputPerMillion) {
            this.outputPerMillion = outputPerMillion;
        }
    }

    /** 已启用且配置完整的备用供应商。 */
    public List<Provider> getEnabledProviders() {
        List<Provider> enabled = new ArrayList<>();
        for (Provider p : providers) {
            if (p.isEnabled() && p.getName() != null && !p.getName().isBlank()
                    && p.getBaseUrl() != null && !p.getBaseUrl().isBlank()
                    && p.getApiKey() != null && !p.getApiKey().isBlank()) {
                enabled.add(p);
            }
        }
        return enabled;
    }

    public String getPrimaryName() {
        return primaryName;
    }

    public void setPrimaryName(String primaryName) {
        this.primaryName = primaryName;
    }

    public List<String> getPrimaryModels() {
        return primaryModels;
    }

    public void setPrimaryModels(List<String> primaryModels) {
        this.primaryModels = primaryModels;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public Map<String, Price> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, Price> prices) {
        this.prices = prices;
    }
}
