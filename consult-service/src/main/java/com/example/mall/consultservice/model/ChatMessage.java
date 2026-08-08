package com.example.mall.consultservice.model;

import com.example.mall.common.model.MessageSenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 问诊聊天消息。
 * <p>
 * 唯一键：
 * - UK(session_id, seq)：会话内单调序号唯一，防乱序重复（seq 由每会话内存 AtomicLong 分配）。
 * - UK(session_id, client_msg_id)：客户端幂等去重（client_msg_id 可空，MySQL 唯一索引允许多个 NULL）。
 * 批量写队列按 session_id 分组批量 INSERT（INSERT IGNORE 逐行容错，P1-04）。
 */
@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "idx_message_session", columnList = "session_id"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_message_session_seq", columnNames = {"session_id", "seq"}),
                @UniqueConstraint(name = "uk_message_session_client", columnNames = {"session_id", "client_msg_id"})
        })
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 10)
    private MessageSenderType senderType;

    /** 客户端幂等键（用户侧必填；医生/系统消息可为空） */
    @Column(name = "client_msg_id", length = 64)
    private String clientMsgId;

    /** 文本内容（业务上限 500 字，帧长上限 4KB） */
    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private Integer seq;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatMessage() {
    }

    public ChatMessage(Long sessionId, MessageSenderType senderType, String clientMsgId, String content, int seq) {
        this.sessionId = sessionId;
        this.senderType = senderType;
        this.clientMsgId = clientMsgId;
        this.content = content;
        this.seq = seq;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public MessageSenderType getSenderType() {
        return senderType;
    }

    public void setSenderType(MessageSenderType senderType) {
        this.senderType = senderType;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public void setClientMsgId(String clientMsgId) {
        this.clientMsgId = clientMsgId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
