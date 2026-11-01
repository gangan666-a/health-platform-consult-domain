package com.example.mall.agentservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * L0 会话原文（shopdb_agent.chat_message）：对话全量落库，重启可还原——
 * 只存 USER / ASSISTANT 对话正文；SYSTEM persona 每次请求重建不落库，
 * TOOL / 工具调用中间消息属请求内协议细节，不落库也不回放。
 * memoryKey = sessionId:AGENT_TYPE（同 {@link com.example.mall.common.model.AgentType} 隔离）。
 */
@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "idx_chat_memory_key", columnList = "memory_key"))
public class ChatMessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memory_key", nullable = false, length = 160)
    private String memoryKey;

    /** USER / ASSISTANT */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatMessageRecord() {
    }

    public ChatMessageRecord(String memoryKey, String role, String content, LocalDateTime createdAt) {
        this.memoryKey = memoryKey;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
