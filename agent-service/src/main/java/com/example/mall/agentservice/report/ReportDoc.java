package com.example.mall.agentservice.report;

import com.example.mall.agentservice.report.ReportTypeDetector.ReportType;

import java.time.LocalDate;
import java.util.List;

/**
 * 解析 + 参考范围比对完成后的报告文档（§0 术语「检查报告」）。
 * referenceVerified=false 表示参考范围未经 ES 校验（E-4 降级标注）。
 */
public record ReportDoc(
        String examName,
        LocalDate reportDate,
        String source,
        ReportType type,
        String rawText,
        boolean referenceVerified,
        List<ReportIndicator> indicators) {

    public static ReportDoc from(ParsedReport parsed, ReportType type, boolean referenceVerified) {
        return new ReportDoc(parsed.examName(), parsed.reportDate(), parsed.source(), type,
                parsed.rawText(), referenceVerified, parsed.indicators());
    }
}
