package com.example.mall.common.model;

/**
 * 订单状态：待支付 / 已支付 / 已取消
 *
 * JSON 序列化保持枚举 name（PAID/CREATED/CANCELLED），
 * 调用方依赖 name 做状态判断；中文文案通过 DTO 的 statusLabel 字段传递。
 */
public enum OrderStatus {

    CREATED("待支付"),
    PAID("已支付"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
