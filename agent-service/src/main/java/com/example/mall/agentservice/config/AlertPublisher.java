package com.example.mall.agentservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 告警通道：熔断开闸/危急值风暴/降级率异常等事件的出口。
 * 默认日志+指标（LoggingAlertPublisher 常驻）；配置 agent.alert.webhook 后
 * 追加 Webhook 推送（钉钉/飞书机器人格式），生产再扩展短信/电话升级链路。
 */
public interface AlertPublisher {

    void publish(String level, String title, String detail);
}

/** 基础通道：结构化 ERROR 日志 + 告警计数指标（日志采集侧可直接做告警规则）。 */
@Component
class LoggingAlertPublisher implements AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger("ALERT");

    private final MeterRegistry registry;

    LoggingAlertPublisher(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publish(String level, String title, String detail) {
        log.error("[{}] {} — {}", level, title, detail);
        registry.counter("agent.alert.total", "level", level).increment();
    }
}

/** 可选 Webhook 通道（钉钉/飞书群机器人）：agent.alert.webhook 配置后启用。 */
@Component
@ConditionalOnProperty(name = "agent.alert.webhook")
class WebhookAlertPublisher implements AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertPublisher.class);

    private final String webhook;
    private final RestClient restClient = RestClient.create();

    WebhookAlertPublisher(@Value("${agent.alert.webhook}") String webhook) {
        this.webhook = webhook;
    }

    @Override
    public void publish(String level, String title, String detail) {
        try {
            restClient.post().uri(webhook)
                    .header("Content-Type", "application/json")
                    .body(Map.of("msgtype", "text",
                            "text", Map.of("content", "[" + level + "] " + title + " — " + detail)))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("告警 Webhook 推送失败（告警不阻塞业务）: {}", e.getMessage());
        }
    }
}
