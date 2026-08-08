package com.example.mall.consultservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 问诊服务业务常量集中配置（评审 R-18：空闲/断线/心跳/处方有效期统一可配）。
 * <p>
 * 默认值全部 fail-closed：心跳 30s、断线缓冲 60s、空闲 30min、扫表 60s、处方 7 天、队列上限 500、批量 20 条/1s。
 * 注意：断线缓冲不允许配为 0（避免瞬时抖动即误结束），由校验约束（P2-09 关联 R-18）。
 */
@Component
@ConfigurationProperties(prefix = "consult")
public class ConsultProperties {

    /** Netty WebSocket 端口（REST 走 server.port） */
    private int wsPort = 8086;

    /** 心跳探活：读空闲时长（秒），连续两个周期无响应判死 */
    private int wsHeartbeatIntervalSeconds = 30;

    /** 断线缓冲（秒）：身份离线后此期限内重连会话继续 */
    private int sessionDisconnectGraceSeconds = 60;

    /** 空闲超时（秒）：ONGOING 会话连续无文本消息时长，超时置 EXPIRED */
    private int sessionIdleTimeoutSeconds = 30 * 60;

    /** 调度器扫表周期（秒） */
    private int sessionSweepIntervalSeconds = 60;

    /** 处方默认有效期（天） */
    private int prescriptionValidDays = 7;

    /** 批量写队列：每会话队列长度上限 */
    private int batchQueueCapacity = 500;

    /** 批量写队列：累计条数达到该阈值即 drain */
    private int batchDrainThreshold = 20;

    /** 批量写队列：距上次 drain 超过该毫秒数即 drain */
    private long batchDrainIntervalMs = 1000;

    public int getWsPort() {
        return wsPort;
    }

    public void setWsPort(int wsPort) {
        this.wsPort = wsPort;
    }

    public int getWsHeartbeatIntervalSeconds() {
        return wsHeartbeatIntervalSeconds;
    }

    public void setWsHeartbeatIntervalSeconds(int wsHeartbeatIntervalSeconds) {
        this.wsHeartbeatIntervalSeconds = wsHeartbeatIntervalSeconds;
    }

    public int getSessionDisconnectGraceSeconds() {
        return sessionDisconnectGraceSeconds;
    }

    public void setSessionDisconnectGraceSeconds(int sessionDisconnectGraceSeconds) {
        this.sessionDisconnectGraceSeconds = sessionDisconnectGraceSeconds;
    }

    public int getSessionIdleTimeoutSeconds() {
        return sessionIdleTimeoutSeconds;
    }

    public void setSessionIdleTimeoutSeconds(int sessionIdleTimeoutSeconds) {
        this.sessionIdleTimeoutSeconds = sessionIdleTimeoutSeconds;
    }

    public int getSessionSweepIntervalSeconds() {
        return sessionSweepIntervalSeconds;
    }

    public void setSessionSweepIntervalSeconds(int sessionSweepIntervalSeconds) {
        this.sessionSweepIntervalSeconds = sessionSweepIntervalSeconds;
    }

    public int getPrescriptionValidDays() {
        return prescriptionValidDays;
    }

    public void setPrescriptionValidDays(int prescriptionValidDays) {
        this.prescriptionValidDays = prescriptionValidDays;
    }

    public int getBatchQueueCapacity() {
        return batchQueueCapacity;
    }

    public void setBatchQueueCapacity(int batchQueueCapacity) {
        this.batchQueueCapacity = batchQueueCapacity;
    }

    public int getBatchDrainThreshold() {
        return batchDrainThreshold;
    }

    public void setBatchDrainThreshold(int batchDrainThreshold) {
        this.batchDrainThreshold = batchDrainThreshold;
    }

    public long getBatchDrainIntervalMs() {
        return batchDrainIntervalMs;
    }

    public void setBatchDrainIntervalMs(long batchDrainIntervalMs) {
        this.batchDrainIntervalMs = batchDrainIntervalMs;
    }
}
