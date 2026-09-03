package com.example.mall.agentservice.report;

/** 多模态视觉通道（页级）：图片/扫描页的结构化抽取，接入真实 VLM 时替换实现即可。 */
public interface VisionAnalyzer {

    PageExtraction analyze(PageContent page, ExtractionRecipe recipe);
}
