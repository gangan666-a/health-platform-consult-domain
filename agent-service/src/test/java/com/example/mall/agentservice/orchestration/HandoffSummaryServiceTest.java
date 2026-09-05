package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.common.model.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交接纪要门禁：handoff 必须携带「总结后」的纪要——
 * LLM 不可用时降级为近期对话要点的确定性摘要，历史为空/异常时返回占位纪要，任何情况不抛出。
 */
class HandoffSummaryServiceTest {

    @Test
    void summarize_withoutLlm_fallsBackToDeterministicDigest() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of(
                new UserMessage("我最近头晕，血压偏高"),
                new AssistantMessage("建议监测血压，若持续偏高请就医。")));
        HandoffSummaryService service = new HandoffSummaryService(memory, mock(AgentChatClientFactory.class), "");

        String note = service.summarize("s1", AgentType.CONSULTATION, "需要用药建议");

        assertTrue(note.contains("交接纪要"), note);
        assertTrue(note.contains("问诊 Agent"), note);
        assertTrue(note.contains("头晕"), note);
        assertTrue(note.contains("交接原因：需要用药建议"), note);
    }

    @Test
    void summarize_emptyHistory_returnsPlaceholder_neverThrows() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of());
        HandoffSummaryService service = new HandoffSummaryService(memory, mock(AgentChatClientFactory.class), "");

        String note = service.summarize("s1", AgentType.REPORT, null);

        assertTrue(note.contains("暂无历史对话"), note);
        assertTrue(note.contains("交接原因：未填写"), note);
    }

    @Test
    void summarize_memoryThrows_degradesToPlaceholderNote() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenThrow(new IllegalStateException("boom"));
        HandoffSummaryService service = new HandoffSummaryService(memory, mock(AgentChatClientFactory.class), "");

        String note = service.summarize("s1", AgentType.MEDICINE_QA, "换域续聊");

        assertTrue(note.contains("交接纪要"), note);
        assertTrue(note.contains("交接原因：换域续聊"), note);
    }

    @Test
    void digest_returnsRecentTurns_asDelegationContext() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of(new UserMessage("咳嗽一周")));
        HandoffSummaryService service = new HandoffSummaryService(memory, mock(AgentChatClientFactory.class), "");

        String brief = service.digest("s1", AgentType.MANAGER, 4);

        assertTrue(brief.contains("用户：咳嗽一周"), brief);
    }
}
