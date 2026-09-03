package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.agent.specialist.SafetyAgent;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.agentservice.config.AgentOrchestrationProperties;
import com.example.mall.agentservice.orchestration.tools.HandoffTools;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handoff 编排：会话已绑定某 Sub Agent 时，由该 Agent 续聊并可再次 handoff。
 */
@Component
public class HandoffOrchestrator {

    private final SubAgentRegistry registry;
    private final SafetyAgent safetyAgent;
    private final HandoffTools handoffTools;
    private final AgentChatClientFactory clientFactory;
    private final AgentOrchestrationProperties properties;
    private final boolean llmEnabled;

    public HandoffOrchestrator(SubAgentRegistry registry,
                               SafetyAgent safetyAgent,
                               HandoffTools handoffTools,
                               AgentChatClientFactory clientFactory,
                               AgentOrchestrationProperties properties,
                               @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.registry = registry;
        this.safetyAgent = safetyAgent;
        this.handoffTools = handoffTools;
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    public AgentChatResponse continueWithActive(AgentSession session, String userMessage,
                                                AgentInvocationContext context) {
        AgentType active = session.activeAgent();

        if (active == AgentType.MANAGER) {
            throw new IllegalStateException("Handoff 模式下 activeAgent 不应为 MANAGER");
        }

        String content;
        if (active == AgentType.SAFETY) {
            content = safetyAgent.handle(session, userMessage, context);
        } else if (!llmEnabled || !properties.isHandoffEnabled()) {
            SubAgent agent = registry.require(active);
            content = agent.handle(session, userMessage, context);
        } else {
            content = invokeWithHandoffTools(session, userMessage, context, active);
        }

        boolean handoff = context.isHandoffRequested();
        AgentType handoffTo = context.handoffTarget();
        AgentType handoffFrom = context.handoffFrom() != null ? context.handoffFrom() : active;

        if (handoff && handoffTo != null && handoffTo != AgentType.MANAGER) {
            SubAgent next = registry.require(handoffTo);
            String followUp = next.handle(session, userMessage, context);
            content = content + "\n\n---\n\n" + followUp;
            active = handoffTo;
        } else if (handoff && handoffTo == AgentType.MANAGER) {
            active = AgentType.MANAGER;
        }

        return SupervisorOrchestrator.buildResponse(
                session, content, context, OrchestrationMode.HANDOFF, active,
                handoff, handoffFrom, handoffTo, context.handoffReason());
    }

    private String invokeWithHandoffTools(AgentSession session, String userMessage,
                                          AgentInvocationContext context, AgentType active) {
        SubAgent agent = registry.require(active);
        HandoffTools.HandoffToolBinding binding = handoffTools.bind(session, context, active);

        ChatClient client = clientFactory.forAgent(active, agentHandoffPrompt(active));
        String memoryKey = AgentChatClientFactory.memoryKey(session.sessionId(), active);

        context.addTrace(active);
        return client.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .tools(binding)
                .user(userMessage)
                .call()
                .content();
    }

    private static String agentHandoffPrompt(AgentType active) {
        return "你是「" + active.displayName() + "」。回答用户问题。"
                + "若问题超出你的职责，使用 handoff 工具转交给更合适的 Agent，并说明原因。"
                + "不要编造医疗结论。";
    }
}
