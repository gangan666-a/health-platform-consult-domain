package com.example.mall.agentservice.report;

/**
 * 危急值告警（防线3，规则引擎产物）：确定性规则触发，与 LLM 解读无关；
 * 触发后必须出现在最终响应中并强制注入解读 prompt（无论 LLM 如何措辞）。
 */
public record CriticalAlert(String severity, String ruleId, String indicatorName, String message) {

    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_HIGH = "HIGH";

    public boolean isCritical() {
        return SEVERITY_CRITICAL.equals(severity);
    }

    /** 响应/提示展示文案。 */
    public String display() {
        return (isCritical() ? "【危急值·" : "【重点提醒·") + ruleId + "】" + message;
    }
}
