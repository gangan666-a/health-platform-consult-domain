package com.example.mall.agentservice.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.mall.agentservice.config.EmbeddingClient;
import com.example.mall.agentservice.config.EmbeddingProperties;
import com.example.mall.common.model.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hybrid 检索单测（CI）：RRF 融合数学行为 + ES/向量双路降级（T-2 纪律）。
 * 真实 ES 的检索质量门禁见 RetrievalEvalTest（env-gated）。
 */
class HybridRetrievalServiceTest {

    private static EmbeddingClient disabledEmbedding() {
        return new EmbeddingClient(new EmbeddingProperties());
    }

    @SuppressWarnings("unchecked")
    private static ElasticsearchClient throwingEs() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        when(es.search(any(Function.class), eq(Map.class))).thenThrow(new RuntimeException("ES unavailable"));
        return es;
    }

    @Test
    void rrf_bothLegsHit_fusesByRank() {
        List<String> keyword = List.of("a", "b", "c");
        List<String> vector = List.of("b", "d");

        List<String> fused = HybridRetrievalService.rrfFuse(keyword, vector, 3);

        // b 两路命中得分最高；a/c 与 d 单路命中按名次比较
        assertEquals("b", fused.get(0));
        assertTrue(fused.contains("a"));
        assertTrue(fused.contains("d"));
        assertEquals(3, fused.size());
    }

    @Test
    void rrf_emptyLeg_returnsOtherLeg() {
        assertEquals(List.of("x", "y"), HybridRetrievalService.rrfFuse(List.of("x", "y"), List.of(), 5));
        assertEquals(List.of("x"), HybridRetrievalService.rrfFuse(List.of(), List.of("x"), 5));
        assertEquals(List.of(), HybridRetrievalService.rrfFuse(List.of(), List.of(), 5));
    }

    @Test
    void esDown_degradesToEmptyContext() throws Exception {
        HybridRetrievalService service = new HybridRetrievalService(throwingEs(), disabledEmbedding());

        assertTrue(service.search(AgentType.SCIENCE, "高血脂", 3).isEmpty());
        assertEquals("", service.retrieve(AgentType.SCIENCE, "高血脂", 3));
    }

    @Test
    void blankQuery_returnsEmpty() {
        HybridRetrievalService service = new HybridRetrievalService(mock(ElasticsearchClient.class),
                disabledEmbedding());

        assertTrue(service.search(AgentType.REPORT, "  ", 3).isEmpty());
    }
}
