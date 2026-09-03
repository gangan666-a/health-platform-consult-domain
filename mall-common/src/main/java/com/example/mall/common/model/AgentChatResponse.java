package com.example.mall.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 平台对话响应。
 */
public class AgentChatResponse {

    private String sessionId;
    private AgentType activeAgent;
    private String content;
    /** 编排模式：SUPERVISOR 或 HANDOFF */
    private String orchestrationMode;
    /** 本轮是否发生 handoff */
    private boolean handoff;
    private AgentType handoffFrom;
    private AgentType handoffTo;
    private String handoffReason;
    /** 参与本轮的 Agent 链路（便于前端展示） */
    private List<AgentType> agentTrace = new ArrayList<>();
    /** 安全 Agent 是否拦截 */
    private boolean blocked;
    private String blockReason;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public AgentType getActiveAgent() {
        return activeAgent;
    }

    public void setActiveAgent(AgentType activeAgent) {
        this.activeAgent = activeAgent;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOrchestrationMode() {
        return orchestrationMode;
    }

    public void setOrchestrationMode(String orchestrationMode) {
        this.orchestrationMode = orchestrationMode;
    }

    public boolean isHandoff() {
        return handoff;
    }

    public void setHandoff(boolean handoff) {
        this.handoff = handoff;
    }

    public AgentType getHandoffFrom() {
        return handoffFrom;
    }

    public void setHandoffFrom(AgentType handoffFrom) {
        this.handoffFrom = handoffFrom;
    }

    public AgentType getHandoffTo() {
        return handoffTo;
    }

    public void setHandoffTo(AgentType handoffTo) {
        this.handoffTo = handoffTo;
    }

    public String getHandoffReason() {
        return handoffReason;
    }

    public void setHandoffReason(String handoffReason) {
        this.handoffReason = handoffReason;
    }

    public List<AgentType> getAgentTrace() {
        return agentTrace;
    }

    public void setAgentTrace(List<AgentType> agentTrace) {
        this.agentTrace = agentTrace != null ? agentTrace : new ArrayList<>();
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(String blockReason) {
        this.blockReason = blockReason;
    }
}
