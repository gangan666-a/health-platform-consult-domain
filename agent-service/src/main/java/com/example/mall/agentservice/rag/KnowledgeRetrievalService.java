package com.example.mall.agentservice.rag;

import com.example.mall.common.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对话 Agent 的知识检索门面：hybrid（BM25 + 向量 + RRF），
 * ES/向量不可用时降级为空上下文（T-2 纪律），不阻断对话。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private static final int TOP_K = 3;

    private final HybridRetrievalService hybrid;

    public KnowledgeRetrievalService(HybridRetrievalService hybrid) {
        this.hybrid = hybrid;
    }

    public String retrieve(AgentType agentType, String query) {
        try {
            return hybrid.retrieve(agentType, query, TOP_K);
        } catch (Exception e) {
            log.warn("知识检索失败，降级为空上下文: {}", e.getMessage());
            return "";
        }
    }
}
