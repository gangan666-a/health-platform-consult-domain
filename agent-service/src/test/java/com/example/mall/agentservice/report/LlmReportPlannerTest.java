package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 解析规划器：LLM 结构化输出解析 + 白名单校验 + 无 Key/失败/输出无效时确定性兜底。 */
class LlmReportPlannerTest {

    private static ChatModel stubModel(String answer) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static FileProfile profile(String... pageTexts) {
        List<PageContent> pages = new java.util.ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) {
            pages.add(new PageContent(i + 1, pageTexts[i], null));
        }
        return new FileProfile("report.pdf", "application/pdf", true, pages, null);
    }

    @Test
    void noKey_deterministicPlanByTextPresence() {
        LlmReportPlanner planner = new LlmReportPlanner(null, "", "");

        ReportPlan plan = planner.plan(profile("WBC 6.5", "", "超声提示"));

        assertEquals("deterministic", plan.planner());
        assertEquals(PageChannel.TEXT_LLM, plan.channelOf(new PageContent(1, "WBC 6.5", null)));
        assertEquals(PageChannel.VISION, plan.channelOf(new PageContent(2, "", null)));
        assertEquals(PageChannel.TEXT_LLM, plan.channelOf(new PageContent(3, "超声提示", null)));
        assertTrue(plan.steps().contains(ReportPlan.STEP_EXTRACT));
    }

    @Test
    void llmPlan_parsedAndValidated() {
        LlmReportPlanner planner = new LlmReportPlanner(stubModel("""
                {"steps":["CLASSIFY_PAGES","EXTRACT_PAGES","AGGREGATE","VALIDATE"],
                 "pageChannels":[{"pageNo":1,"channel":"TEXT_LLM"},{"pageNo":2,"channel":"VISION"}],
                 "expectedPageTypes":["LAB","ULTRASOUND"]}
                """), "sk-test", "");

        ReportPlan plan = planner.plan(profile("WBC 6.5", ""));

        assertEquals("llm", plan.planner());
        assertEquals(PageChannel.TEXT_LLM, plan.channelOf(new PageContent(1, "WBC 6.5", null)));
        assertEquals(PageChannel.VISION, plan.channelOf(new PageContent(2, "", null)));
        assertEquals(List.of("LAB", "ULTRASOUND"), plan.expectedPageTypes());
    }

    @Test
    void llmPlanInvalidPages_fallsBackToDeterministic() {
        // 页号全部越界 → 校验后无可信字段 → 确定性计划兜底
        LlmReportPlanner planner = new LlmReportPlanner(stubModel("""
                {"steps":["X"],"pageChannels":[{"pageNo":9,"channel":"TEXT_LLM"}],"expectedPageTypes":["?"]}
                """), "sk-test", "");

        ReportPlan plan = planner.plan(profile("WBC 6.5"));

        assertEquals("deterministic", plan.planner());
    }

    @Test
    void llmGarbageOutput_fallsBackToDeterministic() {
        LlmReportPlanner planner = new LlmReportPlanner(stubModel("计划：先看文本"), "sk-test", "");

        ReportPlan plan = planner.plan(profile("WBC 6.5"));

        assertEquals("deterministic", plan.planner());
    }

    @Test
    void imageOnlyProfile_neverCallsLlm() {
        // 无任何文本页直接走确定性（图片件无文本可规划）
        ChatModel failing = prompt -> {
            throw new IllegalStateException("不应调用 LLM");
        };
        LlmReportPlanner planner = new LlmReportPlanner(failing, "sk-test", "");

        ReportPlan plan = planner.plan(profile("", ""));

        assertEquals("deterministic", plan.planner());
    }
}
