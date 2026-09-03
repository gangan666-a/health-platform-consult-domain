package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 配置（阿里 text-embedding-v3，走 DashScope OpenAI 兼容协议）：
 * api-key 留空 → 向量通道不启用，hybrid 检索自动降级 BM25-only（对齐 T-2 降级纪律）。
 * 维度须与 ES mapping 的 dense_vector dims 一致（当前 1024）。
 */
@ConfigurationProperties(prefix = "agent.embedding")
public class EmbeddingProperties {

    /** OpenAI 兼容 embeddings 端点（DashScope 兼容模式）。 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** DashScope API Key；留空=不启用向量通道。 */
    private String apiKey = "";

    private String model = "text-embedding-v3";

    private int dimensions = 1024;

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
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

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }
}
