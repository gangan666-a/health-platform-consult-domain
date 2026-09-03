package com.example.mall.agentservice.report;

import java.util.List;
import java.util.Map;

/**
 * 解析计划（PLAN 步骤产物，可审计）：预期页型、每页解析通道、步骤序列与规划器来源。
 * 计划与执行分离：执行状态记录在 PlanExecutionTrace，本记录保持不可变。
 */
public record ReportPlan(String planner, List<String> steps,
                         Map<Integer, PageChannel> pageChannels, List<String> expectedPageTypes) {

    public static final String STEP_PROFILE = "PROFILE";
    public static final String STEP_PLAN = "PLAN";
    public static final String STEP_CLASSIFY = "CLASSIFY_PAGES";
    public static final String STEP_EXTRACT = "EXTRACT_PAGES";
    public static final String STEP_AGGREGATE = "AGGREGATE";
    public static final String STEP_VALIDATE = "VALIDATE";

    /** 通道兜底：计划缺失该页（或值非法）时按文本层有无决定。 */
    public PageChannel channelOf(PageContent page) {
        PageChannel planned = pageChannels().get(page.pageNo());
        return planned != null ? planned : (page.hasText() ? PageChannel.TEXT_LLM : PageChannel.VISION);
    }
}
