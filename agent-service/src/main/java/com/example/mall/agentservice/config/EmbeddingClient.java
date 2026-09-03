package com.example.mall.agentservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embedding 客户端：基于 Spring AI OpenAiEmbeddingModel 指向 DashScope 兼容模式
 * （阿里 text-embedding-v3，OpenAI 兼容 /embeddings 协议，无 SDK 额外依赖）。
 * 未配置 key → enabled=false，调用方降级 BM25-only；调用失败由调用方按降级纪律处理。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final OpenAiEmbeddingModel model;

    public EmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        if (properties.enabled()) {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(properties.getBaseUrl())
                    .apiKey(properties.getApiKey())
                    .build();
            this.model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder()
                            .model(properties.getModel())
                            .dimensions(properties.getDimensions())
                            .build());
            log.info("Embedding 客户端就绪：{}（{} 维，DashScope 兼容模式）",
                    properties.getModel(), properties.getDimensions());
        } else {
            this.model = null;
            log.info("Embedding 未配置（agent.embedding.api-key 为空），向量通道降级 BM25-only");
        }
    }

    public boolean enabled() {
        return model != null;
    }

    /** 单条文本向量；未启用返回 null（调用方降级）。 */
    public float[] embed(String text) {
        List<float[]> all = embedAll(List.of(text));
        return all == null ? null : all.get(0);
    }

    /** 批量向量（种子灌库用）；未启用返回 null；失败抛出由调用方处理。 */
    public List<float[]> embedAll(List<String> texts) {
        if (model == null || texts == null || texts.isEmpty()) {
            return null;
        }
        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));
        return response.getResults().stream()
                .map(org.springframework.ai.embedding.Embedding::getOutput)
                .toList();
    }
}
