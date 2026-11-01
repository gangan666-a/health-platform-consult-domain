package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 视觉通道配置（GLM VLM，智谱 OpenAI 兼容协议 https://open.bigmodel.cn/api/paas/v4）：
 * model=免费档 glm-4.6v-flash；fallback-model=付费档 glm-4.6v（免费档并发溢出/调用失败时升级）。
 * 模型/端点来源：.claude/llm-switch.json（role=vision），由 sync-llm-model hook 渲染，勿硬编码。
 * api-key 留空 → 视觉通道未接入（Mock 明确未识别，不虚构）。
 */
@ConfigurationProperties(prefix = "agent.report.vision")
public class VisionProperties {

    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 智谱 API Key；留空=视觉通道不启用。 */
    private String apiKey = "";

    /** 免费档模型（默认主力，受平台并发/RPM 限制）。 */
    private String model = "glm-4.6v-flash";

    /** 付费档模型：免费档并发溢出或调用失败时升级使用；留空则不升级。 */
    private String fallbackModel = "glm-4.6v";

    /** 免费档并发额度（按平台限制设置，如新用户 2 并发）；溢出请求直接走付费档。 */
    private int freeConcurrency = 2;

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

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public int getFreeConcurrency() {
        return freeConcurrency;
    }

    public void setFreeConcurrency(int freeConcurrency) {
        this.freeConcurrency = freeConcurrency;
    }
}
