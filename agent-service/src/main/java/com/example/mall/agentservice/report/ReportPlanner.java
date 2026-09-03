package com.example.mall.agentservice.report;

/** 解析规划器（PLAN 步骤）：依据文件画像生成本次解析的执行计划。 */
public interface ReportPlanner {

    ReportPlan plan(FileProfile profile);
}
