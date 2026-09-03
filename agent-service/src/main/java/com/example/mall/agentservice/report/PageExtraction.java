package com.example.mall.agentservice.report;

import java.util.List;

/**
 * 单页抽取结果。channel 标记解析通道：llm（真实 LLM 抽取）/ deterministic（规则文法兜底）/
 * vision（视觉通道，未接入真实 VLM 时 indicators 与 findings 为空，绝不虚构）。
 */
public record PageExtraction(int pageNo, PageType pageType, String sectionTitle, String reportDate,
                             List<ReportIndicator> indicators, List<String> findings, String channel) {

    public static final String CHANNEL_LLM = "llm";
    public static final String CHANNEL_LLM_VERIFIED = "llm-verified";
    public static final String CHANNEL_LLM_VERIFIED_CORRECTED = "llm-verified-corrected";
    public static final String CHANNEL_DETERMINISTIC = "deterministic";
    public static final String CHANNEL_DETERMINISTIC_FALLBACK = "deterministic-llm-failed";
    public static final String CHANNEL_VISION_UNAVAILABLE = "vision-unavailable";

    public boolean hasContent() {
        return !indicators().isEmpty() || !findings().isEmpty();
    }
}
