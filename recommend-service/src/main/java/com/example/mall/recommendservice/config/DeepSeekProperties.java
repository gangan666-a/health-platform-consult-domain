package com.example.mall.recommendservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * DeepSeek 大模型配置（前缀 deepseek.*），见 resources/application.properties。
 */
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    /** API Key；留空时自动走规则推荐。可被环境变量 DEEPSEEK_API_KEY 覆盖。 */
    private String apiKey = "";

    /** API 基础地址（OpenAI Chat Completions 兼容） */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名：推荐场景用 deepseek-v4-flash（便宜快、原生 JSON 输出） */
    private String model = "deepseek-v4-flash";

    /** 单次输出的最大 token 数（过小会截断 JSON 导致解析失败） */
    private int maxTokens = 2000;

    /** 推荐条数上限 */
    private int topN = 4;

    /** 采样温度：仅 thinking 关闭时生效 */
    private double temperature = 0.3;

    /** 读取超时（读取响应） */
    private Duration timeout = Duration.ofSeconds(12);

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
