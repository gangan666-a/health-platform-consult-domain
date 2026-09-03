package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 数值双次交叉校验：抽取值与原文不符时按核对结果纠正（channel=llm-verified-corrected），
 * 核对通过标记 llm-verified，核对失败保留原抽取（降级不阻断）。
 * 桩模型按 prompt 内容行为分派：核对 prompt → 返回纠正 JSON；抽取 prompt → 返回抽取 JSON。
 */
class LlmTextPageExtractorVerifyTest {

    private static final String EXTRACTION_JSON = """
            {"examName":"shenghua","reportDate":null,
             "indicators":[{"name":"GLU","value":"9.9","unit":"mmol/L","refRange":"3.9-6.1"}],
             "findings":[]}
            """;

    private static final String CORRECTION_JSON = """
            {"corrections":[{"name":"GLU","extracted":"9.9","correct":"5.2"}]}
            """;

    /** 行为分派桩：用户消息含【页面原文】+【已抽取指标】= 核对调用；否则 = 抽取调用。 */
    private static ChatModel behaviorModel(String extractionAnswer, String verifyAnswer, AtomicInteger verifyCalls) {
        return prompt -> {
            String text = userText(prompt);
            if (text.contains("已抽取指标")) {
                verifyCalls.incrementAndGet();
                return response(verifyAnswer);
            }
            return response(extractionAnswer);
        };
    }

    private static String userText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .findFirst().orElse("");
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static PageContent labPage() {
        return new PageContent(1, "#shenghua\nGLU 5.2 mmol/L 3.9-6.1", null);
    }

    private static LlmTextPageExtractor extractor(ChatModel model, boolean verifyEnabled) {
        return new LlmTextPageExtractor(model, new PageTypeRegistry(), "sk-test", "", verifyEnabled);
    }

    @Test
    void mismatchCorrected_fromVerifyPass() {
        AtomicInteger verifyCalls = new AtomicInteger();
        LlmTextPageExtractor extractor = extractor(
                behaviorModel(EXTRACTION_JSON, CORRECTION_JSON, verifyCalls), true);

        PageExtraction extraction = extractor.extract(labPage(), PageType.LAB);

        assertEquals(1, verifyCalls.get(), "抽取后必须执行一次交叉核对");
        assertEquals(PageExtraction.CHANNEL_LLM_VERIFIED_CORRECTED, extraction.channel());
        assertEquals("5.2", extraction.indicators().get(0).value(), "抄错值按原文纠正");
    }

    @Test
    void verifiedPass_channelMarked() {
        AtomicInteger verifyCalls = new AtomicInteger();
        LlmTextPageExtractor extractor = extractor(
                behaviorModel(EXTRACTION_JSON, "{\"corrections\":[]}", verifyCalls), true);

        PageExtraction extraction = extractor.extract(labPage(), PageType.LAB);

        assertEquals(PageExtraction.CHANNEL_LLM_VERIFIED, extraction.channel());
        assertEquals("9.9", extraction.indicators().get(0).value(), "核对通过不改值");
    }

    @Test
    void verifyDisabled_skipsSecondCall() {
        AtomicInteger verifyCalls = new AtomicInteger();
        LlmTextPageExtractor extractor = extractor(
                behaviorModel(EXTRACTION_JSON, CORRECTION_JSON, verifyCalls), false);

        PageExtraction extraction = extractor.extract(labPage(), PageType.LAB);

        assertEquals(0, verifyCalls.get(), "关闭开关不做交叉核对");
        assertEquals(PageExtraction.CHANNEL_LLM, extraction.channel());
    }
}
