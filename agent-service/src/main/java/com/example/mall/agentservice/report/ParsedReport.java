package com.example.mall.agentservice.report;

import java.time.LocalDate;
import java.util.List;

/**
 * 聚合后的报告中间结果：结构化指标 + 检查所见 + 原文。
 * status 尚未比对（由 ReportReferenceService 统一标记 HIGH/NORMAL/LOW/UNKNOWN）。
 */
public record ParsedReport(
        String examName,
        LocalDate reportDate,
        String source,
        String rawText,
        List<ReportIndicator> indicators,
        List<ReportFinding> findings) {

    public ParsedReport {
        findings = findings == null ? List.of() : findings;
    }
}
