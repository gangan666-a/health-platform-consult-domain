package com.example.mall.agentservice.report;

import com.example.mall.agentservice.config.AgentLlmSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 轻量 LLM 页面分类（页级）：小模型判定 8 种页型，仅输出类别词，成本低延迟小。
 * 未配置 Key / 调用失败 / 输出不可解析时，降级关键词规则（PageTypeKeywordDetector），不阻断解析。
 */
@Component
public class LlmPageClassifier implements PageClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmPageClassifier.class);

    private static final String SYSTEM_PROMPT = """
            你是医疗报告页面分类器。根据文件名和单页内容判断页面类型，只输出以下类别词之一，不要输出其他内容：
            COVER（封面：报告首页，含个人信息排版）、SUMMARY（总检结论/体检汇总页）、LAB（检验明细：指标数值表格）、
            ULTRASOUND（超声报告）、RADIOLOGY（放射/CT/DR 报告）、ECG（心电图）、DISCLAIMER（免责声明/说明页）、OTHER（其他或无法判断）。
            """;

    private static final int MAX_TEXT_CHARS = 1500;

    private final ChatClient chatClient;
    private final OpenAiChatOptions options;
    private final boolean llmEnabled;
    private final PageTypeKeywordDetector keywordFallback;

    public LlmPageClassifier(ChatModel chatModel,
                             PageTypeKeywordDetector keywordFallback,
                             @Value("${spring.ai.openai.api-key:}") String apiKey,
                             @Value("${agent.report.classifier.model:}") String model) {
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
        this.chatClient = llmEnabled ? ChatClient.create(chatModel) : null;
        this.options = model == null || model.isBlank() ? null
                : OpenAiChatOptions.builder().model(model).build();
        this.keywordFallback = keywordFallback;
    }

    @Override
    public PageType classify(PageContent page, String fileName) {
        if (!llmEnabled) {
            return keywordFallback.detect(page, fileName);
        }
        if (!page.hasText()) {
            // 图片页无文本可判（真实 VLM 接入后走视觉分类），当前按关键词/文件名兜底
            return keywordFallback.detect(page, fileName);
        }
        try {
            var spec = chatClient.prompt();
            if (options != null) {
                spec = spec.options(options);
            }
            String answer = spec
                    .system(SYSTEM_PROMPT)
                    .user("文件名：" + fileName + "\n第 " + page.pageNo() + " 页内容：\n" + page.textForPrompt(MAX_TEXT_CHARS))
                    .call()
                    .content();
            PageType parsed = PageType.parse(answer);
            if (parsed == null) {
                log.warn("页面分类输出不可解析（{}），降级关键词规则", answer == null ? "" : answer.trim());
                return keywordFallback.detect(page, fileName);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("轻量 LLM 页面分类失败，降级关键词规则: {}", e.getMessage());
            return keywordFallback.detect(page, fileName);
        }
    }
}
