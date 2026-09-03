package com.example.mall.agentservice;

import com.example.mall.agentservice.orchestration.AgentOrchestrator;
import com.example.mall.common.model.AgentChatRequest;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(properties = {
        // placeholder 前缀键：Spring AI 校验通过（非空），AgentLlmSupport 判定为未启用 LLM → 走占位路由
        "spring.ai.openai.api-key=placeholder-local-dev",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration,"
                + "org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreAutoConfiguration"
})
class AgentOrchestratorTest {

    @Autowired
    AgentOrchestrator orchestrator;

    @Test
    void chat_withoutApiKey_usesStubRouting() {
        AgentChatRequest req = new AgentChatRequest();
        req.setMessage("我咳嗽发烧怎么办");

        AgentChatResponse resp = orchestrator.chat(req);

        assertNotNull(resp.getSessionId());
        assertNotNull(resp.getContent());
        assertFalse(resp.isBlocked());
        assertEquals(AgentType.CONSULTATION, resp.getActiveAgent());
    }
}
