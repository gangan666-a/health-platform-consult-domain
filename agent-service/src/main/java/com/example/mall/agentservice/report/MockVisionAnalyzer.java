package com.example.mall.agentservice.report;

import java.util.List;

/**
 * 多模态 LLM 模拟实现（T-7：接口 + Mock，不绑定真实 VLM）。
 * 图片/扫描页当前无视觉模型可用：返回明确的「未识别」空抽取（channel=vision-unavailable），
 * 绝不虚构样例指标——整个文件零内容时由上游按 E-3 拒绝并提示重传。
 * Bean 装配见 VisionConfig：配置 agent.report.vision.api-key 后切换为 GlmVisionAnalyzer。
 */
public class MockVisionAnalyzer implements VisionAnalyzer {

    @Override
    public PageExtraction analyze(PageContent page, ExtractionRecipe recipe) {
        PageType pageType = recipe.pageType();
        return new PageExtraction(page.pageNo(), pageType, pageType.display(), null,
                List.of(), List.of(), PageExtraction.CHANNEL_VISION_UNAVAILABLE);
    }
}
