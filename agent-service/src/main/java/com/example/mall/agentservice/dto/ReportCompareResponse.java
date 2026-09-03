package com.example.mall.agentservice.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 同指标多日期对比响应（§4）：多日期并列展示 + 咨询提示（OPEN-5 不做显著性判定）。
 */
public record ReportCompareResponse(
        String indicatorName,
        List<CompareEntry> entries,
        String note) {

    public record CompareEntry(LocalDate examDate, String value, String unit, String refRange, String status) {
    }
}
