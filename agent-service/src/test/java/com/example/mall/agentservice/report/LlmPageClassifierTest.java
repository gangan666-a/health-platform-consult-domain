package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 页级分类（8 页型）：LLM 输出解析 + 失败/无 Key/无文本 降级关键词规则。 */
class LlmPageClassifierTest {

    private final PageTypeKeywordDetector fallback = new PageTypeKeywordDetector();

    /** 桩 ChatModel：固定返回指定文本，不发起真实网络调用。 */
    private static ChatModel stubModel(String answer) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static PageContent page(int pageNo, String text) {
        return new PageContent(pageNo, text, null);
    }

    @Test
    void noKey_fallsBackToKeywordRules() {
        LlmPageClassifier classifier = new LlmPageClassifier(null, fallback, "", "");

        assertEquals(PageType.ULTRASOUND,
                classifier.classify(page(1, "超声所见 肝脏形态大小正常\n超声提示 肝内钙化灶"), "report.pdf"));
        assertEquals(PageType.ECG,
                classifier.classify(page(2, "诊断：窦性心律\nST段改变"), "report.pdf"));
        assertEquals(PageType.LAB,
                classifier.classify(page(3, "检验项目 结果 单位 参考范围\nWBC 6.5 10^9/L 3.5-9.5"), "report.pdf"));
        assertEquals(PageType.SUMMARY,
                classifier.classify(page(4, "总检结论：血脂偏高，建议低脂饮食"), "report.pdf"));
        assertEquals(PageType.COVER,
                classifier.classify(page(1, "健康体检报告\n姓名 张三 性别 男 年龄 30"), "report.pdf"));
        assertEquals(PageType.DISCLAIMER,
                classifier.classify(page(5, "本报告仅供个人健康管理使用\n免责声明"), "report.pdf"));
        assertEquals(PageType.OTHER,
                classifier.classify(page(6, "some unknown content"), "report.pdf"));
    }

    @Test
    void noText_fallsBackToKeywordRulesByFilename() {
        LlmPageClassifier classifier = new LlmPageClassifier(null, fallback, "", "");

        assertEquals(PageType.LAB, classifier.classify(new PageContent(1, "", new byte[]{1}), "血常规.png"));
    }

    @Test
    void llmAnswer_parsedToPageType() {
        LlmPageClassifier classifier =
                new LlmPageClassifier(stubModel("LAB"), fallback, "sk-test", "");

        assertEquals(PageType.LAB, classifier.classify(page(1, "任意文本"), "report.pdf"));
    }

    @Test
    void llmAnswerUnparseable_fallsBack() {
        LlmPageClassifier classifier =
                new LlmPageClassifier(stubModel("这是化验单页"), fallback, "sk-test", "");

        assertEquals(PageType.LAB,
                classifier.classify(page(1, "检验项目 结果 单位 参考范围\nWBC 6.5"), "report.pdf"));
    }

    @Test
    void llmCallFailed_fallsBack() {
        ChatModel broken = prompt -> {
            throw new IllegalStateException("boom");
        };
        LlmPageClassifier classifier = new LlmPageClassifier(broken, fallback, "sk-test", "");

        assertEquals(PageType.ULTRASOUND, classifier.classify(page(1, "超声提示 肝囊肿"), "report.pdf"));
    }
}
