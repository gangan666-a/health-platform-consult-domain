package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * 问诊聊天消息（consult-service → 调用方/前端，历史查询与实时广播复用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessageDTO {

    private Long id;
    private Long sessionId;
    private MessageSenderType senderType;
    /** 客户端幂等键（用户侧必填；医生/系统消息可为空） */
    private String clientMsgId;
    private String content;
    /** 会话内单调递增序号（唯一键 (sessionId, seq) 兜底） */
    private Integer seq;
    private LocalDateTime createdAt;

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
