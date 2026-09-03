package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.specialist.SafetyAgent;
import com.example.mall.common.model.AgentChatRequest;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import org.springframework.stereotype.Service;

/**
 * 多 Agent 编排门面：Supervisor + Handoff 混合模式。
 * <ol>
 *   <li>安全 Agent 前置审查（fail-closed）</li>
 *   <li>无 active Sub Agent → Supervisor（Manager 委派）</li>
 *   <li>已有 active Sub Agent → Handoff 续聊</li>
 * </ol>
 */
@Service
public class AgentOrchestrator {

    private final AgentSessionStore sessionStore;
    private final SafetyAgent safetyAgent;
    private final SupervisorOrchestrator supervisorOrchestrator;
    private final HandoffOrchestrator handoffOrchestrator;

    public AgentOrchestrator(AgentSessionStore sessionStore,
                             SafetyAgent safetyAgent,
                             SupervisorOrchestrator supervisorOrchestrator,
                             HandoffOrchestrator handoffOrchestrator) {
        this.sessionStore = sessionStore;
        this.safetyAgent = safetyAgent;
        this.supervisorOrchestrator = supervisorOrchestrator;
        this.handoffOrchestrator = handoffOrchestrator;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        AgentSession session = sessionStore.getOrCreate(request.getSessionId());
        AgentInvocationContext context = new AgentInvocationContext();

        SafetyAgent.SafetyCheckResult safety = safetyAgent.preCheck(request.getMessage());
        if (safety.blocked()) {
            AgentChatResponse blocked = new AgentChatResponse();
            blocked.setSessionId(session.sessionId());
            blocked.setActiveAgent(AgentType.SAFETY);
            blocked.setBlocked(true);
            blocked.setBlockReason(safety.reason());
            blocked.setContent(safety.reason());
            blocked.setOrchestrationMode(OrchestrationMode.SUPERVISOR.name());
            context.addTrace(AgentType.SAFETY);
            blocked.setAgentTrace(context.trace());
            return blocked;
        }

        if (request.getTargetAgent() != null && request.getTargetAgent().isSpecialist()) {
            session.activate(request.getTargetAgent(), OrchestrationMode.HANDOFF);
        }

        if (session.isUnderSpecialist()) {
            return handoffOrchestrator.continueWithActive(session, request.getMessage(), context);
        }

        AgentChatResponse response = supervisorOrchestrator.route(session, request.getMessage(), context);
        if (session.activeAgent() != AgentType.MANAGER) {
            response.setHandoff(true);
            response.setHandoffFrom(AgentType.MANAGER);
            response.setHandoffTo(session.activeAgent());
        }
        return response;
    }
}
