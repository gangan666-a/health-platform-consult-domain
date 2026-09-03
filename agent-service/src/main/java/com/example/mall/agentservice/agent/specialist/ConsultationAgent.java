package com.example.mall.agentservice.agent.specialist;

import com.example.mall.agentservice.agent.AbstractSubAgent;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.agentservice.service.HealthMemoryService;
import com.example.mall.common.model.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConsultationAgent extends AbstractSubAgent {

    private final HealthMemoryService healthMemory;

    public ConsultationAgent(AgentChatClientFactory clientFactory,
                             KnowledgeRetrievalService retrievalService,
                             HealthMemoryService healthMemory,
                             @Value("${spring.ai.openai.api-key:}") String apiKey) {
        super(clientFactory, retrievalService, apiKey);
        this.healthMemory = healthMemory;
    }

    @Override
    public AgentType type() {
        return AgentType.CONSULTATION;
    }

    @Override
    public List<AgentType> handoffTargets() {
        return List.of(AgentType.MEDICINE_QA, AgentType.SHOPPING_GUIDE, AgentType.SCIENCE, AgentType.REPORT);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是医疗健康平台的「问诊 Agent」。
                职责：收集用户症状、追问关键信息、给出就医建议等级（居家观察/门诊/急诊）。
                禁止：直接确诊、开具处方、替代线下医生。
                若用户明确需要购药，handoff 到问药或导购 Agent；若涉及化验报告，handoff 到报告解读 Agent。
                回答简洁、结构化，必要时列出需补充的问题。
                """;
    }

    @Override
    protected String memoryContext(AgentSession session) {
        if (session.userId() == null) {
            return "";
        }
        return healthMemory.redlineFactsSection(session.userId());
    }
}
