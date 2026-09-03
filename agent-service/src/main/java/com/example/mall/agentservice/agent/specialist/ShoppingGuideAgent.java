package com.example.mall.agentservice.agent.specialist;

import com.example.mall.agentservice.agent.AbstractSubAgent;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.common.model.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShoppingGuideAgent extends AbstractSubAgent {

    public ShoppingGuideAgent(AgentChatClientFactory clientFactory,
                              KnowledgeRetrievalService retrievalService,
                              @Value("${spring.ai.openai.api-key:}") String apiKey) {
        super(clientFactory, retrievalService, apiKey);
    }

    @Override
    public AgentType type() {
        return AgentType.SHOPPING_GUIDE;
    }

    @Override
    public List<AgentType> handoffTargets() {
        return List.of(AgentType.MEDICINE_QA, AgentType.CONSULTATION, AgentType.SAFETY);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是医疗健康平台的「导购 Agent」。
                职责：根据用户需求推荐合适的 OTC 药品或引导 Rx 处方药走在线问诊开方后购药。
                可调用药品目录工具查询库存与价格（后续完善）；当前先给出选药建议与购药路径说明。
                Rx 药品必须强调：须凭有效处方购买，引导用户先问诊开方。
                """;
    }
}
