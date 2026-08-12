package com.example.mall.recommendservice.ai;

import com.example.mall.common.trace.TraceContext;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek API 调用方（OpenAI Chat Completions 兼容）。
 * <p>
 * 关键点：
 * <ul>
 *   <li>RestClient 必须显式设置 connect/read 超时，默认无限超时会卡死调用方页面；</li>
 *   <li>thinking 显式关闭 + response_format=json_object，保证返回是可直接解析的 JSON；</li>
 *   <li>apiKey 为空时 isEnabled()=false，上层自动走规则回退，服务可无 Key 启动。</li>
 * </ul>
 */
@Component
public class DeepSeekClient {

    private final DeepSeekProperties props;
    private final RestClient restClient;

    public DeepSeekClient(DeepSeekProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .requestInterceptor(TraceContext.interceptor())
                .build();
    }

    /** 是否启用 AI（未配置 API Key 时关闭） */
    public boolean isEnabled() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * 调用 chat/completions，返回 choices[0].message.content 原文（不解析业务含义）。
     * 网络失败 / 非 2xx / 返回结构异常时抛异常，由上层兜底回退规则推荐。
     */
    public String chatCompletion(List<DeepSeekChatRequest.Message> messages) {
        DeepSeekChatRequest body = new DeepSeekChatRequest();
        body.setModel(props.getModel());
        body.setMessages(messages);
        body.setTemperature(props.getTemperature());
        body.setThinking(Map.of("type", "disabled"));
        body.setResponseFormat(Map.of("type", "json_object"));
        body.setMaxTokens(props.getMaxTokens());
        body.setStream(false);

        DeepSeekChatResponse resp = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(DeepSeekChatResponse.class);

        if (resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()
                || resp.getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("DeepSeek 响应结构异常");
        }
        String content = resp.getChoices().get(0).getMessage().getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek 返回空内容");
        }
        return content;
    }
}
