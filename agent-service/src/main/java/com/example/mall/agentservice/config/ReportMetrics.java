package com.example.mall.agentservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 报告业务指标（观测三支柱之 metrics；/actuator/metrics 可查、告警可订阅）：
 * ① 解析结果：report.parse.total{result=success|e3-rejected|failed}（解析成功率）
 * ② 页型分布：report.parse.pages{pageType}（版式覆盖画像，指导页型模板迭代）
 * ③ 通道分布：report.parse.channel{channel}（llm-verified-corrected↑ = 抽取质量预警）
 * ④ 降级统计：report.degrade{type=es|graph|vision|storage|prompt-guard|verify}
 * ⑤ 危急值/幂等命中/解析耗时（timer report.parse.duration）。
 */
@Component
public class ReportMetrics {

    private final MeterRegistry registry;

    public ReportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void parseResult(String result) {
        registry.counter("report.parse.total", "result", result).increment();
    }

    public void pageType(String pageType) {
        registry.counter("report.parse.pages", "pageType", pageType).increment();
    }

    public void pageChannel(String channel) {
        registry.counter("report.parse.channel", "channel", channel).increment();
    }

    public void degrade(String type) {
        registry.counter("report.degrade", "type", type).increment();
    }

    public void criticalAlert() {
        registry.counter("report.critical.alerts").increment();
    }

    public void idempotentHit() {
        registry.counter("report.upload.idempotent").increment();
    }

    public void recordParseDuration(long millis) {
        Timer.builder("report.parse.duration").register(registry).record(Duration.ofMillis(millis));
    }
}
