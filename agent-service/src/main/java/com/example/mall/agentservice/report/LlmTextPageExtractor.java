package com.example.mall.agentservice.report;

import com.example.mall.agentservice.config.AgentLlmSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本层页抽取器（真实 LLM 通道）：按页型配方 prompt + 结构化输出（.entity）抽取指标与所见。
 * 页级定向降级（replan 的最小形态）：LLM 失败/无 Key 时回退确定性 TextLayerParser，channel 标记
 * deterministic-llm-failed 供轨迹区分「无 Key 走规则」与「LLM 失败兜底」。
 */
@Component
public class LlmTextPageExtractor implements PageExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmTextPageExtractor.class);

    private static final int MAX_TEXT_CHARS = 6000;

    /** LLM 结构化输出 DTO（BeanOutputConverter 依据此 schema 生成格式指令）。 */
    public record LlmPageExtraction(String examName, String reportDate,
                                     List<LlmIndicator> indicators, List<String> findings) {
    }

    public record LlmIndicator(String name, String value, String unit, String refRange) {
    }

    /** 交叉校验 DTO：核对抽取值与原文，返回需要纠正的指标。 */
    public record LlmVerification(List<LlmCorrection> corrections) {
    }

    public record LlmCorrection(String name, String extracted, String correct) {
    }

    private static final String VERIFY_PROMPT = """
            你是医疗报告数值核对员。给你页面原文与已抽取的指标列表，逐项核对「数值」是否与原文完全一致。
            只输出需要纠正的项（抽取值抄错/幻觉的）；数值一致或原文中不存在的项不要输出。
            correct 必须逐字照抄原文中的数值。
            """;

    private final ChatClient chatClient;
    private final OpenAiChatOptions options;
    private final boolean llmEnabled;
    private final boolean verifyEnabled;
    private final PageTypeRegistry registry;

    public LlmTextPageExtractor(ChatModel chatModel,
                                PageTypeRegistry registry,
                                @Value("${spring.ai.openai.api-key:}") String apiKey,
                                @Value("${agent.report.extractor.model:}") String model,
                                @Value("${agent.report.verify.enabled:true}") boolean verifyEnabled) {
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
        this.chatClient = llmEnabled ? ChatClient.create(chatModel) : null;
        this.options = model == null || model.isBlank() ? null
                : OpenAiChatOptions.builder().model(model).build();
        this.registry = registry;
        this.verifyEnabled = verifyEnabled;
    }

    boolean llmEnabled() {
        return llmEnabled;
    }

    @Override
    public PageExtraction extract(PageContent page, PageType pageType) {
        if (pageType == PageType.DISCLAIMER) {
            return new PageExtraction(page.pageNo(), pageType, pageType.display(), null,
                    List.of(), List.of(), PageExtraction.CHANNEL_DETERMINISTIC);
        }
        if (!llmEnabled || !page.hasText()) {
            return TextLayerParser.parse(page, pageType);
        }
        try {
            ExtractionRecipe recipe = registry.recipe(pageType);
            var spec = chatClient.prompt();
            if (options != null) {
                spec = spec.options(options);
            }
            LlmPageExtraction parsed = spec
                    .system(recipe.prompt())
                    .user(userMessage(page))
                    .call()
                    .entity(LlmPageExtraction.class);
            return verify(page, pageType, toExtraction(page, pageType, parsed));
        } catch (Exception e) {
            log.warn("LLM 页抽取失败（第{}页·{}），回退确定性解析: {}", page.pageNo(), pageType, e.getMessage());
            PageExtraction fallback = TextLayerParser.parse(page, pageType);
            return new PageExtraction(fallback.pageNo(), fallback.pageType(), fallback.sectionTitle(),
                    fallback.reportDate(), fallback.indicators(), fallback.findings(),
                    PageExtraction.CHANNEL_DETERMINISTIC_FALLBACK);
        }
    }

    private static String userMessage(PageContent page) {
        return "【页面文本】\n" + page.textForPrompt(MAX_TEXT_CHARS);
    }

    /**
     * 数值双次交叉校验（runtime 防幻觉）：抽取成功后，用同页原文核对每个数值，
     * 抄错/幻觉的按原文纠正。校验失败降级保留原抽取（不阻断）。channel 标记：
     * llm-verified（核对通过）/ llm-verified-corrected（发生纠正，进轨迹可审计）。
     */
    private PageExtraction verify(PageContent page, PageType pageType, PageExtraction extraction) {
        if (!verifyEnabled || extraction.indicators().isEmpty()) {
            return extraction;
        }
        try {
            StringBuilder extractedList = new StringBuilder();
            for (int i = 0; i < extraction.indicators().size(); i++) {
                var in = extraction.indicators().get(i);
                extractedList.append(i + 1).append(". ").append(in.name()).append(" = ")
                        .append(in.value()).append('\n');
            }
            var spec = chatClient.prompt();
            if (options != null) {
                spec = spec.options(options);
            }
            LlmVerification verification = spec
                    .system(VERIFY_PROMPT)
                    .user("【页面原文】\n" + page.textForPrompt(MAX_TEXT_CHARS)
                            + "\n\n【已抽取指标】\n" + extractedList)
                    .call()
                    .entity(LlmVerification.class);
            if (verification == null || verification.corrections() == null
                    || verification.corrections().isEmpty()) {
                return withChannel(extraction, PageExtraction.CHANNEL_LLM_VERIFIED);
            }
            List<ReportIndicator> corrected = new ArrayList<>();
            for (ReportIndicator in : extraction.indicators()) {
                LlmCorrection c = verification.corrections().stream()
                        .filter(x -> x != null && in.name().equalsIgnoreCase(x.name()))
                        .findFirst().orElse(null);
                if (c != null && !isBlank(c.correct())) {
                    log.warn("数值交叉校验纠正（第{}页）: {} {} → {}", page.pageNo(), in.name(), in.value(), c.correct());
                    corrected.add(new ReportIndicator(in.name(), c.correct().trim(), in.unit(),
                            in.refRange(), in.status(), in.examDate()));
                } else {
                    corrected.add(in);
                }
            }
            return new PageExtraction(extraction.pageNo(), extraction.pageType(), extraction.sectionTitle(),
                    extraction.reportDate(), corrected, extraction.findings(),
                    PageExtraction.CHANNEL_LLM_VERIFIED_CORRECTED);
        } catch (Exception e) {
            log.warn("数值交叉校验失败（第{}页），保留原抽取: {}", page.pageNo(), e.getMessage());
            return extraction;
        }
    }

    private static PageExtraction withChannel(PageExtraction extraction, String channel) {
        return new PageExtraction(extraction.pageNo(), extraction.pageType(), extraction.sectionTitle(),
                extraction.reportDate(), extraction.indicators(), extraction.findings(), channel);
    }

    private static PageExtraction toExtraction(PageContent page, PageType pageType, LlmPageExtraction parsed) {
        if (parsed == null) {
            return new PageExtraction(page.pageNo(), pageType, pageType.display(), null,
                    List.of(), List.of(), PageExtraction.CHANNEL_LLM);
        }
        List<ReportIndicator> indicators = new ArrayList<>();
        LocalDate date = parseDate(parsed.reportDate());
        if (parsed.indicators() != null) {
            for (LlmIndicator in : parsed.indicators()) {
                if (in == null || isBlank(in.name()) || isBlank(in.value())) {
                    continue; // 契约：名称或数值缺失的行丢弃，不猜
                }
                indicators.add(new ReportIndicator(in.name().trim(), in.value().trim(),
                        orEmpty(in.unit()), orEmpty(in.refRange()), IndicatorStatus.UNKNOWN, date));
            }
        }
        List<String> findings = new ArrayList<>();
        if (parsed.findings() != null) {
            for (String f : parsed.findings()) {
                if (!isBlank(f)) {
                    findings.add(f.trim());
                }
            }
        }
        String title = isBlank(parsed.examName()) ? pageType.display() : parsed.examName().trim();
        return new PageExtraction(page.pageNo(), pageType, title,
                parsed.reportDate(), indicators, findings, PageExtraction.CHANNEL_LLM);
    }

    private static LocalDate parseDate(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().replace("/", "-").replace(".", "-"));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
