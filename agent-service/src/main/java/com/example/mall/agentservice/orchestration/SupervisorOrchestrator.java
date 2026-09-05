package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.agentservice.config.AgentOrchestrationProperties;
import com.example.mall.agentservice.orchestration.tools.ManagerDelegateTools;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supervisor 编排：Manager Agent 的五项职责——
 * <ol>
 *   <li>路由：tool-calling 单选委派；无 LLM 时降级 ManagerRouter 关键词启发式，链路不断</li>
 *   <li>组织上下文：委派时附带 Manager 会话要点，Sub-Agent 不丢上下文</li>
 *   <li>总结汇总：把 Sub Agent 的回答整理成用户友好的回复</li>
 *   <li>超意图兜底：不属于任何 Sub Agent 领域的问题（问候/平台咨询/投诉），Manager 直接作答</li>
 *   <li>反思：委派结果为空或带失败标记时改派一次；Manager 最终无有效答复则确定性兜底</li>
 * </ol>
 */
@Component
public class SupervisorOrchestrator {

    private final AgentChatClientFactory clientFactory;
    private final SubAgentRegistry registry;
    private final AgentSessionStore sessionStore;
    private final AgentOrchestrationProperties properties;
    private final HandoffSummaryService handoffSummary;
    private final boolean llmEnabled;

    public SupervisorOrchestrator(AgentChatClientFactory clientFactory,
                                  SubAgentRegistry registry,
                                  AgentSessionStore sessionStore,
                                  AgentOrchestrationProperties properties,
                                  HandoffSummaryService handoffSummary,
                                  @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.clientFactory = clientFactory;
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.handoffSummary = handoffSummary;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    public AgentChatResponse route(AgentSession session, String userMessage, AgentInvocationContext context) {
        context.addTrace(AgentType.MANAGER);

        if (!llmEnabled) {
            AgentType target = ManagerRouter.heuristicRoute(userMessage);
            return delegateDirect(session, userMessage, context, target);
        }

        // 组织上下文：取 Manager 会话记忆的近期要点，随每次委派下发给 Sub Agent
        String contextBrief = handoffSummary.digest(session.sessionId(), AgentType.MANAGER, 4);
        ManagerDelegateTools tools = new ManagerDelegateTools(registry, session, context, contextBrief);
        ChatClient manager = clientFactory.forAgent(AgentType.MANAGER, managerSystemPrompt());
        String memoryKey = AgentChatClientFactory.memoryKey(session.sessionId(), AgentType.MANAGER);

        String content = manager.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .tools(tools)
                .user(userMessage)
                .call()
                .content();

        if (content == null || content.isBlank()) {
            // 反思兜底：Manager 最终未产出有效答复（委派全部失败/模型空返回），链路不断
            context.addTrace(AgentType.MANAGER);
            content = managerFallbackAnswer();
        }

        AgentChatResponse response = new AgentChatResponse();
        response.setSessionId(session.sessionId());
        response.setActiveAgent(session.activeAgent());
        response.setContent(content);
        response.setOrchestrationMode(OrchestrationMode.SUPERVISOR.name());
        response.setAgentTrace(context.trace());
        return response;
    }

    /** 无 LLM 时的规则路由 + 直接委派；target=MANAGER 表示超意图兜底（Manager 直接作答） */
    private AgentChatResponse delegateDirect(AgentSession session, String userMessage,
                                             AgentInvocationContext context, AgentType target) {
        if (target == AgentType.MANAGER) {
            return buildResponse(session, managerFallbackAnswer(), context,
                    OrchestrationMode.SUPERVISOR, AgentType.MANAGER, false, null, null, null);
        }
        SubAgent agent = registry.require(target);
        context.setDelegationNote("委派目标：" + target.displayName() + "（关键词路由）。会话要点："
                + handoffSummary.digest(session.sessionId(), AgentType.MANAGER, 4));
        String reply = agent.handle(session, userMessage, context);
        if (properties.isHandoffEnabled() && context.isHandoffRequested()) {
            sessionStore.handoff(session, target, context.handoffTarget(), context.handoffReason());
        } else {
            session.activate(target, OrchestrationMode.HANDOFF);
        }
        return buildResponse(session, reply, context, OrchestrationMode.SUPERVISOR, target, false, null, null, null);
    }

    /** 超意图兜底 / 反思失败的确定性答复，保证链路不断 */
    private static String managerFallbackAnswer() {
        return """
                我是医疗健康平台助手，这个问题我可以帮你转给对应的专业服务：
                · 症状问诊与就医建议（问诊）
                · 药品用法用量、禁忌咨询（问药）
                · 选药推荐与购药路径（导购）
                · 健康知识科普（科普）
                · 体检/化验报告解读（报告解读，上传报告后使用）
                请直接描述你的问题；如出现呼吸困难、大出血、意识不清等紧急症状，请立即拨打 120。
                """;
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
                你是医疗健康平台的 Manager 协调者（Supervisor），五项职责：
                1) 路由：通过工具委派最匹配的 Sub Agent。一次只委派一个；仅当问题明确跨域时才依次调用多个。
                2) 组织上下文：委派结果已附带会话要点，整理回复时保持用户原意，不丢关键信息。
                3) 总结汇总：把 Sub Agent 的回答整理成用户友好的回复，保留必要免责声明。
                4) 超意图兜底：问候、平台能力咨询、投诉等不属于任何 Sub Agent 领域的问题，不要强行委派——
                   直接礼貌作答并说明平台能力范围（问诊/问药/导购/科普/报告解读）。
                5) 反思：若工具返回【委派失败】标记、内容为空或明显不足以回答用户，反思一次并改派更合适的 Agent；
                   仍失败则向用户致歉并建议就医或联系人工客服，不要编造医疗结论。
                可委派的 Sub Agent：
                - consultationAgent：症状、就医建议、在线问诊
                - medicineQaAgent：用药咨询
                - shoppingGuideAgent：选药购药引导
                - scienceAgent：健康科普
                - reportAgent：报告解读
                """;
    }
}
