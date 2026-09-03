package com.example.mall.agentservice.report;

/**
 * 检查所见（超声/放射/心电图/总检结论等文本结论页的产出）：
 * 只承载报告文字结论，不对影像本身下诊断（spec NG 红线），进 prompt 前需脱敏。
 */
public record ReportFinding(String section, String text, int pageNo) {
}
