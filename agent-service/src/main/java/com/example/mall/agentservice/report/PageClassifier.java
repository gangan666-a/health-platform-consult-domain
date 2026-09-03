package com.example.mall.agentservice.report;

/**
 * 页面分类（页级）：判定单页页型，供执行器选择抽取配方。
 * 实现：轻量 LLM 优先，关键词规则兜底（不猜未知页型以外的值，UNKNOWN 概念由 OTHER 承担）。
 */
public interface PageClassifier {

    PageType classify(PageContent page, String fileName);
}
