package com.example.mall.agentservice.report;

import java.util.List;

/**
 * 解析总产物：结构化报告文档 + 检查所见 + 计划与执行轨迹（审计）。
 * findings 进入解读 prompt 前必须脱敏（B-6 数据最小化）。
 */
public record ReportParseResult(ReportDoc doc, List<ReportFinding> findings,
                                ReportPlan plan, List<String> trace) {

    public boolean hasNoContent() {
        return doc.indicators().isEmpty() && findings().isEmpty();
    }
}
