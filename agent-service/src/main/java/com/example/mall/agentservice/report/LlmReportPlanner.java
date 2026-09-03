package com.example.mall.agentservice.report;

import com.example.mall.agentservice.config.AgentLlmSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 解析规划器（plan-execute 的 PLAN 步骤）：由 LLM 依据各页文本预览决定
 * 每页解析通道（TEXT_LLM/VISION）与预期页型，输出经白名单校验（页号/通道/页型不合法即丢弃）。
 * 无 Key / 调用失败 / 输出全不合法 → 确定性计划兜底（有文本走文本通道，无文本走视觉）。
 * 计划不可变且可审计（planner 标记来源），执行状态记录在 PlanExecutionTrace。
 */
@Component
public class LlmReportPlanner implements ReportPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmReportPlanner.class);

    private static final int PAGE_PREVIEW_CHARS = 400;

    private static final String SYSTEM_PROMPT = """
            你是医疗报告解析规划器。输入一份报告文件的各页文本预览，输出本次解析的执行计划：
            1. pageChannels：为每一页选择解析通道——页面有可读文本（含指标表格或文字结论）选 TEXT_LLM，
               页面文本为空（图片/扫描页）选 VISION。
            2. expectedPageTypes：预期页型列表，取值 COVER/SUMMARY/LAB/ULTRASOUND/RADIOLOGY/ECG/DISCLAIMER/OTHER。
            3. steps：步骤顺序，固定为 CLASSIFY_PAGES → EXTRACT_PAGES → AGGREGATE → VALIDATE。
            只依据给定预览判断，不确定的页选 TEXT_LLM 由分类器兜底。
            """;

    /** LLM 结构化输出 DTO。 */
    public record LlmPlan(List<String> steps, List<LlmPageChannel> pageChannels, List<String> expectedPageTypes) {
    }

    public record LlmPageChannel(int pageNo, String channel) {
    }

    private final ChatClient chatClient;
    private final OpenAiChatOptions options;
    private final boolean llmEnabled;

    public LlmReportPlanner(ChatModel chatModel,
                            @Value("${spring.ai.openai.api-key:}") String apiKey,
                            @Value("${agent.report.planner.model:}") String model) {
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
        this.chatClient = llmEnabled ? ChatClient.create(chatModel) : null;
        this.options = model == null || model.isBlank() ? null
                : OpenAiChatOptions.builder().model(model).build();
    }

    @Override
    public ReportPlan plan(FileProfile profile) {
        if (!llmEnabled || !profile.hasAnyTextPage()) {
            return deterministicPlan(profile);
        }
        try {
            var spec = chatClient.prompt();
            if (options != null) {
                spec = spec.options(options);
            }
            LlmPlan parsed = spec
                    .system(SYSTEM_PROMPT)
                    .user(userMessage(profile))
                    .call()
                    .entity(LlmPlan.class);
            ReportPlan validated = validate(parsed, profile);
            if (validated == null) {
                log.warn("LLM 规划输出无可信字段，降级确定性计划");
                return deterministicPlan(profile);
            }
            return validated;
        } catch (Exception e) {
            log.warn("LLM 规划失败，降级确定性计划: {}", e.getMessage());
            return deterministicPlan(profile);
        }
    }

    private static String userMessage(FileProfile profile) {
        StringBuilder sb = new StringBuilder("文件名：").append(profile.fileName());
        if (profile.exifCaptureDate() != null) {
            sb.append("（拍摄日期 ").append(profile.exifCaptureDate()).append("）");
        }
        sb.append('\n');
        for (PageContent page : profile.pages()) {
            sb.append("—— 第 ").append(page.pageNo()).append(" 页 ——\n");
            sb.append(page.hasText() ? page.textForPrompt(PAGE_PREVIEW_CHARS) : "（无文本层，图片页）");
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 白名单校验：页号越界/通道词非法的条目丢弃；全部无效 → 返回 null（走确定性计划）。 */
    private static ReportPlan validate(LlmPlan parsed, FileProfile profile) {
        if (parsed == null || parsed.pageChannels() == null || parsed.pageChannels().isEmpty()) {
            return null;
        }
        Map<Integer, PageChannel> channels = new LinkedHashMap<>();
        for (LlmPageChannel c : parsed.pageChannels()) {
            if (c == null || c.pageNo() < 1 || c.pageNo() > profile.pages().size()) {
                continue;
            }
            PageChannel channel = PageChannel.parse(c.channel());
            if (channel != null) {
                channels.putIfAbsent(c.pageNo(), channel);
            }
        }
        if (channels.isEmpty()) {
            return null;
        }
        List<String> expected = new ArrayList<>();
        if (parsed.expectedPageTypes() != null) {
            for (String t : parsed.expectedPageTypes()) {
                PageType type = PageType.parse(t);
                if (type != null && !expected.contains(type.name())) {
                    expected.add(type.name());
                }
            }
        }
        return new ReportPlan("llm", defaultSteps(), channels, expected);
    }

    /** 确定性计划：文本层页 → TEXT_LLM，无文本页 → VISION（图片件全部走视觉）。 */
    static ReportPlan deterministicPlan(FileProfile profile) {
        Map<Integer, PageChannel> channels = new LinkedHashMap<>();
        for (PageContent page : profile.pages()) {
            channels.put(page.pageNo(), page.hasText() ? PageChannel.TEXT_LLM : PageChannel.VISION);
        }
        return new ReportPlan("deterministic", defaultSteps(), channels, List.of());
    }

    private static List<String> defaultSteps() {
        return List.of(ReportPlan.STEP_CLASSIFY, ReportPlan.STEP_EXTRACT,
                ReportPlan.STEP_AGGREGATE, ReportPlan.STEP_VALIDATE);
    }
}
