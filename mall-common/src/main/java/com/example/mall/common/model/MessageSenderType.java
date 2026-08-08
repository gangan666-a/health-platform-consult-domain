package com.example.mall.common.model;

/**
 * 消息发送方类型：用户 / 医生 / 系统（医生接入、会话结束、开方等关键事件以 SYSTEM 消息落库并推送）。
 * <p>
 * JSON 序列化保持枚举 name。
 */
public enum MessageSenderType {

    USER("用户"),
    DOCTOR("医生"),
    SYSTEM("系统");

    private final String label;

    MessageSenderType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
