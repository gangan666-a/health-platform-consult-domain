package com.example.mall.agentservice.agent;

import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.common.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sub Agent 基类：统一 LLM 调用、memory 隔离、RAG 注入、无 Key 占位回复。
 * 失败升级重试：主档模型调用失败时，用 agent.llm.escalation-model（如 deepseek-reasoner）
 * 同供应商更强档重试一次（网关模型亲和路由会把该模型名送到声明托管它的供应商）；
 * 留空关闭。仍失败则异常上抛，由各调用点既有降级接住（报告解读→占位回复）。
 */
public abstract class AbstractSubAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractSubAgent.class);

    protected final AgentChatClientFactory clientFactory;
    protected final KnowledgeRetrievalService retrievalService;
    protected final boolean llmEnabled;

    /** 失败升级模型（同供应商更强档，如 deepseek-reasoner）；留空关闭。 */
    @Value("${agent.llm.escalation-model:}")
    protected String escalationModel;

    protected AbstractSubAgent(AgentChatClientFactory clientFactory,
                               KnowledgeRetrievalService retrievalService,
                               @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.clientFactory = clientFactory;
        this.retrievalService = retrievalService;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    protected abstract String systemPrompt();

    @Override
    public String handle(AgentSession session, String userMessage, AgentInvocationContext context) {
        context.addTrace(type());
        String rag = retrievalService.retrieve(type(), userMessage);
        context.setRagContext(rag);

        if (!llmEnabled) {
            return stubReply(userMessage, rag);
        }

        ChatClient client = clientFactory.forAgent(type(), buildSystemPrompt(rag, session, context));
        String memoryKey = AgentChatClientFactory.memoryKey(session.sessionId(), type());
        return callWithEscalation(client, tools(session), memoryKey, userMessage);
    }

    private String callWithEscalation(ChatClient client, Object binding, String memoryKey, String userMessage) {
        try {
            return prepare(client, binding, memoryKey, userMessage, null).call().content();
        } catch (Exception e) {
            if (escalationModel == null || escalationModel.isBlank()) {
                throw e;
            }
            log.warn("{} 主档调用失败，升级 {} 重试: {}", type().displayName(), escalationModel, e.getMessage());
            return prepare(client, binding, memoryKey, userMessage,
                    OpenAiChatOptions.builder().model(escalationModel).build()).call().content();
        }
    }

    private static ChatClient.ChatClientRequestSpec prepare(ChatClient client, Object binding,
                                                            String memoryKey, String userMessage,
                                                            OpenAiChatOptions options) {
        var request = client.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey));
        if (binding != null) {
            request.tools(binding);
        }
        if (options != null) {
            request.options(options);
        }
        return request.user(userMessage);
    }

    private String buildSystemPrompt(String rag, AgentSession session, AgentInvocationContext context) {
        StringBuilder sb = new StringBuilder(systemPrompt());
        String memory = memoryContext(session);
        if (memory != null && !memory.isBlank()) {
            sb.append("\n\n【用户健康档案（长期记忆）】\n").append(memory);
        }
        if (rag != null && !rag.isBlank()) {
            sb.append("\n\n【知识库检索片段】\n").append(rag);
        }
        if (context != null && context.delegationNote() != null && !context.delegationNote().isBlank()) {
            sb.append("\n\n【Manager 委派上下文（含会话要点）】\n").append(context.delegationNote());
        }
        if (context != null && context.handoffNote() != null && !context.handoffNote().isBlank()) {
            sb.append("\n\n【交接纪要（上一 Agent 会话历史的总结，供接续参考）】\n").append(context.handoffNote());
        }
        return sb.toString();
    }

    /** 可覆写：注入用户级长期记忆上下文（默认空）。 */
    protected String memoryContext(AgentSession session) {
        return "";
    }

    /** 可覆写：提供本 Agent 专用的 @Tool 绑定对象（默认无工具）。 */
    protected Object tools(AgentSession session) {
        return null;
    }

    /** 无 API Key 时的占位回复，保证框架可联调 */
    protected String stubReply(String userMessage, String rag) {
        return "【" + type().displayName() + "·框架占位】已收到：「"
                + truncate(userMessage, 80) + "」。"
                + (rag.isBlank() ? "" : " （已检索知识库片段）")
                + " 请在 agent-service/src/main/resources/application.properties 配置 agent.deepseek.api-key 后启用完整 LLM 能力。";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
