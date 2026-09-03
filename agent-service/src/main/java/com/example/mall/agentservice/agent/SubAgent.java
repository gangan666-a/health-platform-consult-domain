package com.example.mall.agentservice.agent;

import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.common.model.AgentType;

import java.util.List;

/**
 * Sub Agent 契约：每种专业 Agent 实现此接口，由编排层统一调度。
 */
public interface SubAgent {

    AgentType type();

    /** 允许 handoff 的目标 Agent（不含 MANAGER、SAFETY） */
    List<AgentType> handoffTargets();

    /**
     * 处理用户消息。
     *
     * @return 回复正文；若需 handoff，通过 {@link AgentInvocationContext} 标记
     */
    String handle(AgentSession session, String userMessage, AgentInvocationContext context);
}
