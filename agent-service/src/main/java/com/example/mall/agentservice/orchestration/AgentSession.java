package com.example.mall.agentservice.orchestration;

import com.example.mall.common.model.AgentType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次用户会话的状态：当前活跃 Agent、编排模式、handoff 历史。
 */
public class AgentSession {

    private final String sessionId;
    /** 内部用户 ID（报告解读/健康档案按 userId 隔离数据；普通对话路径可空） */
    private Long userId;
    private AgentType activeAgent = AgentType.MANAGER;
    private OrchestrationMode mode = OrchestrationMode.SUPERVISOR;
    private final List<HandoffRecord> handoffHistory = new ArrayList<>();
    private Instant updatedAt = Instant.now();

    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public AgentType activeAgent() {
        return activeAgent;
    }

    public OrchestrationMode mode() {
        return mode;
    }

    public List<HandoffRecord> handoffHistory() {
        return handoffHistory;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void activate(AgentType agent, OrchestrationMode mode) {
        this.activeAgent = agent;
        this.mode = mode;
        touch();
    }

    public void recordHandoff(AgentType from, AgentType to, String reason) {
        handoffHistory.add(new HandoffRecord(from, to, reason, Instant.now()));
        activate(to, OrchestrationMode.HANDOFF);
    }

    public boolean isUnderSpecialist() {
        return activeAgent != AgentType.MANAGER && mode == OrchestrationMode.HANDOFF;
    }

    public record HandoffRecord(AgentType from, AgentType to, String reason, Instant at) {
    }
}
