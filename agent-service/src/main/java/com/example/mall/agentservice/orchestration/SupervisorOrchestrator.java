package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.agentservice.config.AgentOrchestrationProperties;
import com.example.mall.agentservice.agent.specialist.SafetyAgent;
import com.example.mall.agentservice.orchestration.tools.ManagerDelegateTools;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supervisor 编排：Manager Agent 通过 Tool 委派 Sub Agent，汇总回复。
 */
@Component
public class SupervisorOrchestrator {

    private final AgentChatClientFactory clientFactory;
    private final SubAgentRegistry registry;
    private final SafetyAgent safetyAgent;
    private final AgentSessionStore sessionStore;
    private final AgentOrchestrationProperties properties;
    private final boolean llmEnabled;

    public SupervisorOrchestrator(AgentChatClientFactory clientFactory,
                                  SubAgentRegistry registry,
                                  SafetyAgent safetyAgent,
                                  AgentSessionStore sessionStore,
                                  AgentOrchestrationProperties properties,
                                  @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.clientFactory = clientFactory;
        this.registry = registry;
        this.safetyAgent = safetyAgent;
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    public AgentChatResponse route(AgentSession session, String userMessage, AgentInvocationContext context) {
        context.addTrace(AgentType.MANAGER);

        if (!llmEnabled) {
            AgentType target = ManagerRouter.heuristicRoute(userMessage);
            return delegateDirect(session, userMessage, context, target);
        }

        ManagerDelegateTools tools = new ManagerDelegateTools(registry, safetyAgent, session, context);
        ChatClient manager = clientFactory.forAgent(AgentType.MANAGER, managerSystemPrompt());
        String memoryKey = AgentChatClientFactory.memoryKey(session.sessionId(), AgentType.MANAGER);

        String content = manager.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .tools(tools)
                .user(userMessage)
                .call()
                .content();

        AgentChatResponse response = new AgentChatResponse();
        response.setSessionId(session.sessionId());
        response.setActiveAgent(session.activeAgent());
        response.setContent(content);
        response.setOrchestrationMode(OrchestrationMode.SUPERVISOR.name());
        response.setAgentTrace(context.trace());
        return response;
    }

    /** 无 LLM 时的规则路由 + 直接委派 */
    private AgentChatResponse delegateDirect(AgentSession session, String userMessage,
                                             AgentInvocationContext context, AgentType target) {
        SubAgent agent = registry.require(target);
        String reply = agent.handle(session, userMessage, context);
        if (properties.isHandoffEnabled() && context.isHandoffRequested()) {
            sessionStore.handoff(session, target, context.handoffTarget(), context.handoffReason());
        } else {
            session.activate(target, OrchestrationMode.HANDOFF);
        }
        return buildResponse(session, reply, context, OrchestrationMode.SUPERVISOR, target, false, null, null, null);
    }

    static AgentChatResponse buildResponse(AgentSession session, String content, AgentInvocationContext context,
                                           OrchestrationMode mode, AgentType active,
                                           boolean handoff, AgentType from, AgentType to, String reason) {
        AgentChatResponse r = new AgentChatResponse();
        r.setSessionId(session.sessionId());
        r.setActiveAgent(active);
        r.setContent(content);
        r.setOrchestrationMode(mode.name());
        r.setAgentTrace(context.trace());
        r.setHandoff(handoff);
        r.setHandoffFrom(from);
        r.setHandoffTo(to);
        r.setHandoffReason(reason);
        return r;
    }

    private static String managerSystemPrompt() {
        return """
                你是医疗健康平台的 Manager 协调者（Supervisor）。
                你不直接回答专业问题，而是通过工具委派给 Sub Agent：
                - consultationAgent：症状、就医建议、在线问诊
                - medicineQaAgent：用药咨询
                - shoppingGuideAgent：选药购药引导
                - scienceAgent：健康科普
                - reportAgent：报告解读
                - safetyAgent：合规与风险提示
                规则：一次只委派最匹配的一个 Agent；只有问题明确跨域时才依次调用多个。
                委派后将 Sub Agent 的回答整理成用户友好的回复，保留必要免责声明。
                """;
    }
}
