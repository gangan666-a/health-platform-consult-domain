package com.example.mall.common.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户 → Agent 平台对话请求。
 */
public class AgentChatRequest {

    /** 会话 ID；为空时由服务端生成 */
    private String sessionId;

    @NotBlank(message = "消息不能为空")
    private String message;

    /** 可选：强制指定 Sub Agent（跳过 Manager 路由，用于 handoff 续聊） */
    private AgentType targetAgent;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public AgentType getTargetAgent() {
        return targetAgent;
    }

    public void setTargetAgent(AgentType targetAgent) {
        this.targetAgent = targetAgent;
    }
}
