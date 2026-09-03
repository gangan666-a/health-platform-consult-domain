package com.example.mall.agentservice.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 报告上传响应（§4）：结构化指标 + 检查所见 + 摘要 + 解读 + 免责 + handoff 建议
 * + 危急值告警（防线3，规则引擎强制产出）+ plan-execute 解析轨迹（审计）。
 */
public record ReportUploadResponse(
        Long reportId,
        String examName,
        LocalDate reportDate,
        String source,
        boolean referenceVerified,
        List<IndicatorView> indicators,
        String summary,
        String interpretation,
        String disclaimer,
        List<String> suggestedHandoffs,
        List<String> criticalAlerts,
        List<FindingView> findings,
        List<String> parseTrace) {

    public record IndicatorView(String name, String value, String unit, String refRange, String status,
                                LocalDate examDate) {
    }

    /** 检查所见（已脱敏）：超声/放射/心电/总检结论的文字结论。 */
    public record FindingView(String section, String text, int pageNo) {
    }
}
