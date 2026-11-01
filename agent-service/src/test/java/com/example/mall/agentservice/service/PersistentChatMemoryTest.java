package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.ChatMessageRecord;
import com.example.mall.agentservice.repository.ChatMessageRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L0 持久化会话记忆：USER/ASSISTANT 正文全量落库，SYSTEM/TOOL/工具调用消息不落库；
 * get 只回放最近窗口且按时间正序；clear 整会话删除。
 */
class PersistentChatMemoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final String KEY = "s1:REPORT";

    private final ChatMessageRecordRepository repo = mock(ChatMessageRecordRepository.class);
    private final PersistentChatMemory memory = new PersistentChatMemory(repo, 40);

    @Test
    void add_persistsOnlyUserAndPlainAssistantText() {
        AssistantMessage toolCall = new AssistantMessage("调工具中", Map.of(),
                List.of(new AssistantMessage.ToolCall("t1", "function", "queryReportCompare", "{}")));

        memory.add(KEY, List.of(
                new SystemMessage("persona"),
                new UserMessage("帮我解读报告"),
                toolCall,
                new ToolResponseMessage(List.of()),
                new AssistantMessage("结论：LDL 偏高，建议复诊")));

        ArgumentCaptor<ChatMessageRecord> captor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(repo, times(2)).save(captor.capture());
        List<ChatMessageRecord> saved = captor.getAllValues();
        assertEquals("USER", saved.get(0).getRole());
        assertEquals("帮我解读报告", saved.get(0).getContent());
        assertEquals("ASSISTANT", saved.get(1).getRole());
        assertEquals("结论：LDL 偏高，建议复诊", saved.get(1).getContent());
        assertEquals(KEY, saved.get(0).getMemoryKey());
    }

    @Test
    void add_skipsBlankAssistantText() {
        memory.add(KEY, List.of(new AssistantMessage("  ")));

        verify(repo, times(0)).save(any());
    }

    @Test
    void get_replaysLatestWindowInOrder() {
        when(repo.findByMemoryKeyOrderByIdDesc(eq(KEY), any(PageRequest.class)))
                .thenReturn(List.of(
                        new ChatMessageRecord(KEY, "ASSISTANT", "回复", NOW),
                        new ChatMessageRecord(KEY, "USER", "提问", NOW)));

        List<Message> replayed = memory.get(KEY);

        assertEquals(2, replayed.size(), "库里只有 2 条，窗口 40 不应截断");
        assertInstanceOf(UserMessage.class, replayed.get(0));
        assertEquals("提问", replayed.get(0).getText(), "回放必须按时间正序（库内倒序取、反转）");
        assertInstanceOf(AssistantMessage.class, replayed.get(1));
        assertEquals("回复", replayed.get(1).getText());
        verify(repo).findByMemoryKeyOrderByIdDesc(eq(KEY), eq(PageRequest.of(0, 40)));
    }

    @Test
    void clear_deletesWholeConversation() {
        memory.clear(KEY);

        verify(repo).deleteByMemoryKey(KEY);
    }
}
