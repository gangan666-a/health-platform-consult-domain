package com.example.mall.common.model;

/**
 * 问诊会话状态。
 * <p>
 * 状态机（CAS 影响行数裁决）：WAITING→ONGOING（医生接单）；ONGOING→PRESCRIPTION_ISSUED（开方成功，同事务）；
 * WAITING/ONGOING→ENDED（主动/断线）；ONGOING→EXPIRED（空闲超时，仅 ONGOING 计时，WAITING 不自动结束）。
 * 终态：PRESCRIPTION_ISSUED / ENDED / EXPIRED（只读，拒绝发消息/开方/接诊）。
 * <p>
 * JSON 序列化保持枚举 name；中文文案通过 DTO 的 statusLabel 字段传递（NFR-12）。
 */
public enum ConsultationStatus {

    WAITING("等待医生接入"),
    ONGOING("进行中"),
    PRESCRIPTION_ISSUED("已开具处方"),
    ENDED("已结束"),
    EXPIRED("已过期");

    private final String label;

    ConsultationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否为终态（终态会话拒绝发消息/开方/接诊，仅可查历史） */
    public boolean isTerminal() {
        return this == PRESCRIPTION_ISSUED || this == ENDED || this == EXPIRED;
    }
}
