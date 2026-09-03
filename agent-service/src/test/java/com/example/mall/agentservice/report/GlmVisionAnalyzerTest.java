package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉通道分层策略单测（CI）：未启用→明确未识别；免费档正常；并发溢出→付费档；
 * 免费档失败→付费档升级重试；无付费兜底→降级未识别。
 * 真实 VLM 的抽取质量门禁见 VisionEvalTest（env-gated）。
 */
class GlmVisionAnalyzerTest {

    private static final String EXTRACTION_JSON = """
            {"pageType":"LAB","examName":"shenghua","reportDate":null,
             "indicators":[{"name":"WBC","value":"6.5","unit":"10^9/L","refRange":"3.5-9.5"}],
             "findings":[]}
            """;

    private static ChatModel stubJson(String json) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private static ChatModel failing() {
        return prompt -> {
            throw new IllegalStateException("vision api error");
        };
    }

    private static PageContent imagePage() {
        return new PageContent(1, "", ReportParserTest.tinyPng());
    }

    private static final ExtractionRecipe LAB_RECIPE = new ExtractionRecipe(PageType.LAB, "prompt");

    @Test
    void disabled_returnsUnrecognized() {
        GlmVisionAnalyzer analyzer = new GlmVisionAnalyzer(null, null, 2, false);

        PageExtraction extraction = analyzer.analyze(imagePage(), LAB_RECIPE);

        assertEquals(PageExtraction.CHANNEL_VISION_UNAVAILABLE, extraction.channel());
        assertTrue(extraction.indicators().isEmpty(), "未接入视觉模型绝不虚构");
    }

    @Test
    void freeTierOk_extractsWithSelfClassification() {
        GlmVisionAnalyzer analyzer = new GlmVisionAnalyzer(stubJson(EXTRACTION_JSON), null, 2, false);

        PageExtraction extraction = analyzer.analyze(imagePage(), LAB_RECIPE);

        assertEquals("vision", extraction.channel());
        assertEquals(PageType.LAB, extraction.pageType(), "VLM 一次调用完成页型自分类");
        assertEquals(1, extraction.indicators().size());
        assertEquals("WBC", extraction.indicators().get(0).name());
        assertEquals("6.5", extraction.indicators().get(0).value());
    }

    @Test
    void freeConcurrencyExhausted_overflowsToPaidModel() {
        // permits=0：免费档额度耗尽 → 直接走付费档（保吞吐）
        GlmVisionAnalyzer analyzer = new GlmVisionAnalyzer(stubJson(EXTRACTION_JSON),
                stubJson(EXTRACTION_JSON), 0, true);

        PageExtraction extraction = analyzer.analyze(imagePage(), LAB_RECIPE);

        assertEquals("vision-overflow-paid", extraction.channel());
        assertEquals(1, extraction.indicators().size());
    }

    @Test
    void freeTierFails_escalatesToPaidRetry() {
        GlmVisionAnalyzer analyzer = new GlmVisionAnalyzer(failing(), stubJson(EXTRACTION_JSON), 2, true);

        PageExtraction extraction = analyzer.analyze(imagePage(), LAB_RECIPE);

        assertEquals("vision-retry-paid", extraction.channel());
        assertEquals(1, extraction.indicators().size());
    }

    @Test
    void freeTierFails_withoutFallback_degradesToUnrecognized() {
        GlmVisionAnalyzer analyzer = new GlmVisionAnalyzer(failing(), null, 2, false);

        PageExtraction extraction = analyzer.analyze(imagePage(), LAB_RECIPE);

        assertEquals(PageExtraction.CHANNEL_VISION_UNAVAILABLE, extraction.channel());
        assertTrue(extraction.indicators().isEmpty());
    }
}
