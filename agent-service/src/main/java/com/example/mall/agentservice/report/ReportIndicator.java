package com.example.mall.agentservice.report;

import java.time.LocalDate;

/** 单个化验/检查指标：名称/数值原文/单位/参考范围原文 + 异常标记（UNKNOWN 为初始态）。 */
public record ReportIndicator(
        String name,
        String value,
        String unit,
        String refRange,
        IndicatorStatus status,
        LocalDate examDate) {

    public ReportIndicator withStatus(IndicatorStatus newStatus) {
        return new ReportIndicator(name, value, unit, refRange, newStatus, examDate);
    }
}
