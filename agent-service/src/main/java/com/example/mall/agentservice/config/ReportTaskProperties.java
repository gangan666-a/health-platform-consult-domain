package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 报告异步任务配置：有界并发（保护 LLM 配额与下游）+ 任务保留时长（内存态 TTL 清理）。 */
@ConfigurationProperties(prefix = "agent.report.task")
public class ReportTaskProperties {

    /** 最大并发解析任务数（超出排队等待，虚拟线程承载）。 */
    private int maxConcurrent = 8;

    /** 任务终态后的保留时长（惰性清理 + 提交时清扫，过期可回收）。 */
    private int ttlMinutes = 30;

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(int ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }
}
