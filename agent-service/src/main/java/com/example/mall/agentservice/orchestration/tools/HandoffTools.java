package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.orchestration.AgentSessionStore;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Handoff 工具：Sub Agent 在对话中可将会话转交给其他 Agent。
 */
@Component
public class HandoffTools {

    private final SubAgentRegistry registry;
    private final AgentSessionStore sessionStore;

    public HandoffTools(SubAgentRegistry registry, AgentSessionStore sessionStore) {
        this.registry = registry;
        this.sessionStore = sessionStore;
    }

    public HandoffToolBinding bind(AgentSession session, AgentInvocationContext context, AgentType current) {
        return new HandoffToolBinding(session, context, current);
    }

    public class HandoffToolBinding {

        private final AgentSession session;
        private final AgentInvocationContext context;
        private final AgentType current;

        HandoffToolBinding(AgentSession session, AgentInvocationContext context, AgentType current) {
            this.session = session;
            this.context = context;
            this.current = current;
        }

        @Tool(description = "将对话 handoff 给问诊 Agent")
        public String handoffToConsultation(@ToolParam(description = "handoff 原因") String reason) {
            return doHandoff(AgentType.CONSULTATION, reason);
        }

        @Tool(description = "将对话 handoff 给问药 Agent")
        public String handoffToMedicineQa(@ToolParam(description = "handoff 原因") String reason) {
            return doHandoff(AgentType.MEDICINE_QA, reason);
        }

        @Tool(description = "将对话 handoff 给导购 Agent")
        public String handoffToShoppingGuide(@ToolParam(description = "handoff 原因") String reason) {
            return doHandoff(AgentType.SHOPPING_GUIDE, reason);
        }

        @Tool(description = "将对话 handoff 给科普 Agent")
        public String handoffToScience(@ToolParam(description = "handoff 原因") String reason) {
            return doHandoff(AgentType.SCIENCE, reason);
        }

        @Tool(description = "将对话 handoff 给报告解读 Agent")
        public String handoffToReport(@ToolParam(description = "handoff 原因") String reason) {
            return doHandoff(AgentType.REPORT, reason);
        }

        @Tool(description = "将对话交还 Manager 协调者")
        public String handoffToManager(@ToolParam(description = "handoff 原因") String reason) {
            sessionStore.resetToManager(session);
            context.requestHandoff(current, AgentType.MANAGER, reason);
            return "已交还 Manager 协调者，原因：" + reason;
        }

        private String doHandoff(AgentType target, String reason) {
            if (!registry.get(current).map(a -> a.handoffTargets().contains(target)).orElse(false)
                    && target != AgentType.MANAGER) {
                return "不允许从 " + current.displayName() + " handoff 到 " + target.displayName();
            }
            sessionStore.handoff(session, current, target, reason);
            context.requestHandoff(current, target, reason);
            return "已 handoff 至 " + target.displayName() + "，原因：" + reason;
        }
    }
}
