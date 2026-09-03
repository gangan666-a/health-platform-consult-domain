package com.example.mall.agentservice.agent.specialist;

import com.example.mall.agentservice.agent.AbstractSubAgent;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.common.model.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScienceAgent extends AbstractSubAgent {

    public ScienceAgent(AgentChatClientFactory clientFactory,
                        KnowledgeRetrievalService retrievalService,
                        @Value("${spring.ai.openai.api-key:}") String apiKey) {
        super(clientFactory, retrievalService, apiKey);
    }

    @Override
    public AgentType type() {
        return AgentType.SCIENCE;
    }

    @Override
    public List<AgentType> handoffTargets() {
        return List.of(AgentType.CONSULTATION, AgentType.MEDICINE_QA);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是医疗健康平台的「科普 Agent」。
                职责：用通俗易懂的语言讲解疾病原理、预防、生活方式建议。
                禁止：诊断具体个人病情；个人化问题应 handoff 到问诊 Agent。
                引用知识库内容时注明「仅供参考，不能替代医疗建议」。
                """;
    }
}
