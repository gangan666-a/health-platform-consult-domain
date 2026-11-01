package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.ChatMessageRecord;
import com.example.mall.agentservice.repository.ChatMessageRecordRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * L0 会话记忆（持久化版）：对话原文全量落库 MySQL，重启不丢、可还原；
 * {@code get} 只回放最近 window 条（与旧 MessageWindowChatMemory 相同的注入体积），
 * 超窗部分仍在库里，交接纪要、历史还原等场景可按需放大读取。
 * 落库纪律：只存 USER / ASSISTANT 正文——SYSTEM persona 每次请求重建，
 * TOOL / 带工具调用的 ASSISTANT 是请求内协议消息，回放会因缺少配对 tool 结果而被 API 拒绝。
 */
public class PersistentChatMemory implements ChatMemory {

    private final ChatMessageRecordRepository repository;
    private final int windowSize;

    public PersistentChatMemory(ChatMessageRecordRepository repository, int windowSize) {
        this.repository = repository;
        this.windowSize = windowSize;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            String content = sanitize(message);
            if (content != null) {
                repository.save(new ChatMessageRecord(conversationId, message.getMessageType().name(),
                        content, LocalDateTime.now()));
            }
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        Pageable window = PageRequest.of(0, windowSize);
        List<ChatMessageRecord> latest = repository.findByMemoryKeyOrderByIdDesc(conversationId, window);
        List<Message> result = new ArrayList<>(latest.size());
        for (int i = latest.size() - 1; i >= 0; i--) {
            ChatMessageRecord record = latest.get(i);
            if (MessageType.USER.name().equals(record.getRole())) {
                result.add(new UserMessage(record.getContent()));
            } else {
                result.add(new AssistantMessage(record.getContent()));
            }
        }
        return result;
    }

    @Override
    @Transactional
    public void clear(String conversationId) {
        repository.deleteByMemoryKey(conversationId);
    }

    /** 可落库则返回正文（USER 原文 / 无工具调用的 ASSISTANT 回复），否则返回 null 跳过。 */
    private String sanitize(Message message) {
        if (message.getMessageType() == MessageType.USER) {
            return blankToNull(message.getText());
        }
        if (message.getMessageType() == MessageType.ASSISTANT && message instanceof AssistantMessage assistant
                && !assistant.hasToolCalls()) {
            return blankToNull(assistant.getText());
        }
        return null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
