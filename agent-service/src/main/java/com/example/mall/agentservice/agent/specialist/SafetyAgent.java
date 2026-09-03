package com.example.mall.agentservice.agent.specialist;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.common.model.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 安全 Agent：全链路前置审查 + 高风险关键词拦截（fail-closed）。
 * 后续可扩展 LLM 审核与合规规则引擎。
 */
@Component
public class SafetyAgent {

    private static final Pattern EMERGENCY = Pattern.compile(
            "自杀|自残|胸口剧烈|呼吸困难|昏迷|大出血|孕妇.*(出血|剧痛)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRESCRIPTION_ABUSE = Pattern.compile(
            "帮我开处方|伪造处方|不用处方买", Pattern.CASE_INSENSITIVE);

    private final AgentChatClientFactory clientFactory;
    private final KnowledgeRetrievalService retrievalService;
    private final boolean llmEnabled;

    public SafetyAgent(AgentChatClientFactory clientFactory,
                         KnowledgeRetrievalService retrievalService,
                         @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.clientFactory = clientFactory;
        this.retrievalService = retrievalService;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    public AgentType type() {
        return AgentType.SAFETY;
    }

    public List<AgentType> handoffTargets() {
        return List.of(AgentType.CONSULTATION);
    }

    /**
     * @return empty = 通过；非 empty = 拦截原因（应直接返回用户，不再路由）
     */
    public SafetyCheckResult preCheck(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return SafetyCheckResult.block("消息不能为空");
        }
        if (EMERGENCY.matcher(userMessage).find()) {
            return SafetyCheckResult.block(
                    "检测到可能的紧急医疗情况。请立即拨打 120 或前往最近急诊，本平台不能替代紧急救治。");
        }
        if (PRESCRIPTION_ABUSE.matcher(userMessage).find()) {
            return SafetyCheckResult.block(
                    "无法协助违规购药或伪造处方。Rx 药品须凭合法有效处方购买，如需用药请先在线问诊。");
        }
        return SafetyCheckResult.pass();
    }

    public String handle(AgentSession session, String userMessage, AgentInvocationContext context) {
        context.addTrace(AgentType.SAFETY);
        SafetyCheckResult check = preCheck(userMessage);
        if (check.blocked()) {
            return check.reason();
        }
        if (!llmEnabled) {
            return "【安全 Agent·框架占位】内容已通过基础规则审查。配置 API Key 后可启用 LLM 深度合规分析。";
        }
        var client = clientFactory.forAgent(AgentType.SAFETY, """
                你是医疗健康平台的「安全 Agent」。
                职责：识别违规请求、用药风险、敏感内容，给出合规提示。
                输出简洁，必要时建议用户联系专业医生或拨打急救电话。
                """);
        String memoryKey = AgentChatClientFactory.memoryKey(session.sessionId(), AgentType.SAFETY);
        return client.prompt()
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, memoryKey))
                .user(userMessage)
                .call()
                .content();
    }

    public record SafetyCheckResult(boolean blocked, String reason) {

        static SafetyCheckResult pass() {
            return new SafetyCheckResult(false, null);
        }

        static SafetyCheckResult block(String reason) {
            return new SafetyCheckResult(true, reason);
        }
    }
}
