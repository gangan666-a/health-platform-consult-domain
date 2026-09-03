package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.agent.specialist.SafetyAgent;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Manager Supervisor 的委派工具：每个 Sub Agent 暴露为一个 @Tool。
 */
public class ManagerDelegateTools {

    private final SubAgentRegistry registry;
    private final SafetyAgent safetyAgent;
    private final AgentSession session;
    private final AgentInvocationContext context;

    public ManagerDelegateTools(SubAgentRegistry registry, SafetyAgent safetyAgent,
                                AgentSession session, AgentInvocationContext context) {
        this.registry = registry;
        this.safetyAgent = safetyAgent;
        this.session = session;
        this.context = context;
    }

    @Tool(description = "委派给问诊 Agent：症状分析、就医建议、在线问诊引导")
    public String consultationAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.CONSULTATION, query);
    }

    @Tool(description = "委派给问药 Agent：用法用量、禁忌、相互作用")
    public String medicineQaAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.MEDICINE_QA, query);
    }

    @Tool(description = "委派给导购 Agent：选药推荐与购药路径")
    public String shoppingGuideAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.SHOPPING_GUIDE, query);
    }

    @Tool(description = "委派给科普 Agent：疾病与健康知识科普")
    public String scienceAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.SCIENCE, query);
    }

    @Tool(description = "委派给报告解读 Agent：检验检查报告解读")
    public String reportAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.REPORT, query);
    }

    @Tool(description = "委派给安全 Agent：合规审查与风险提示")
    public String invokeSafetyAgent(@ToolParam(description = "用户问题原文") String query) {
        session.activate(AgentType.SAFETY, com.example.mall.agentservice.orchestration.OrchestrationMode.HANDOFF);
        return safetyAgent.handle(session, query, context);
    }

    private String delegate(AgentType type, String query) {
        SubAgent agent = registry.require(type);
        session.activate(type, com.example.mall.agentservice.orchestration.OrchestrationMode.HANDOFF);
        return agent.handle(session, query, context);
    }
}
