package com.example.mall.common.model;

/**
 * 处方状态：单向流转，终态不可逆。
 * <p>
 * 状态机：ACTIVE→USED（下单原子消费，一次性）；ACTIVE→EXPIRED（now > validUntil，懒判定+定时扫描双保险）；
 * ACTIVE→REVOKED（医生/用户撤销）。USED/EXPIRED/REVOKED 均为终态，不可再消费。
 * <p>
 * JSON 序列化保持枚举 name；中文文案通过 DTO 的 statusLabel 字段传递（NFR-12）。
 */
public enum PrescriptionStatus {

    ACTIVE("有效"),
    USED("已使用"),
    EXPIRED("已过期"),
    REVOKED("已撤销");

    private final String label;

    PrescriptionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否为终态（终态处方不可再消费） */
    public boolean isTerminal() {
        return this == USED || this == EXPIRED || this == REVOKED;
    }
}
