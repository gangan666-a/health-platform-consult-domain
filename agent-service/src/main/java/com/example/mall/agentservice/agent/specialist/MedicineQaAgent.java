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
public class MedicineQaAgent extends AbstractSubAgent {

    private final HealthMemoryService healthMemory;

    public MedicineQaAgent(AgentChatClientFactory clientFactory,
                           KnowledgeRetrievalService retrievalService,
                           HealthMemoryService healthMemory,
                           @Value("${spring.ai.openai.api-key:}") String apiKey) {
        super(clientFactory, retrievalService, apiKey);
        this.healthMemory = healthMemory;
    }

    @Override
    public AgentType type() {
        return AgentType.MEDICINE_QA;
    }

    @Override
    public List<AgentType> handoffTargets() {
        return List.of(AgentType.CONSULTATION, AgentType.SHOPPING_GUIDE, AgentType.SCIENCE, AgentType.REPORT);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是医疗健康平台的「问药 Agent」。
                职责：解答药品用法用量、注意事项、常见相互作用、OTC 与 Rx 区别。
                禁止：对处方药做最终用药决策；遇高风险组合必须建议咨询医生或药师。
                若用户要购买具体药品，handoff 到导购 Agent；若出现紧急症状，handoff 到问诊 Agent。
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
