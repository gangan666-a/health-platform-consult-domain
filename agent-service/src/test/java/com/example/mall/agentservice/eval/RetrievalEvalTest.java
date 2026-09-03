package com.example.mall.agentservice.eval;

import com.example.mall.agentservice.rag.HybridRetrievalService;
import com.example.mall.common.model.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索质量门禁（L2，env-gated）：需本机 ES + IK 插件 + 已启动应用灌入种子
 * （运行前置：起 agent-service 让 KnowledgeSeedRunner 灌库，或手动 POST /api/es/init-indexes）。
 * 设置 AGENT_EVAL_ES=true 启用。recall@5 门槛：每个 golden 查询的期望文档必须出现在 top5。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL_ES", matches = "true")
class RetrievalEvalTest {

    /// golden 查询（query, agentType, expectDocId, needEmbedding）
    private static final List<String[]> GOLDEN = List.of(
            new String[]{"血脂稠了是怎么回事", "SCIENCE", "hc-lipid", "true"},
            new String[]{"血红蛋白偏低是什么意思", "REPORT", "le-cbc", "false"},
            new String[]{"转氨酶高要注意什么", "REPORT", "le-liver", "false"},
            new String[]{"血糖高就是糖尿病吗", "REPORT", "le-glucose", "false"},
            new String[]{"感冒药怎么选", "SHOPPING_GUIDE", "hc-otc", "false"},
            new String[]{"布洛芬和对乙酰氨基酚能一起吃吗", "MEDICINE_QA", "hc-ibuprofen", "false"});

    @Autowired
    HybridRetrievalService hybrid;
    @Autowired
    com.example.mall.agentservice.config.EmbeddingClient embeddingClient;

    @Test
    void goldenQueries_recallAt5_meetsGate() {
        assertFalse(hybrid.search(AgentType.SCIENCE, "血脂", 1).isEmpty()
                        && hybrid.search(AgentType.REPORT, "血常规", 1).isEmpty(),
                "ES 无数据：请先启动应用灌入种子数据（KnowledgeSeedRunner）再运行本评测");

        List<String[]> failures = new java.util.ArrayList<>();
        for (String[] golden : GOLDEN) {
            String query = golden[0];
            AgentType agentType = AgentType.valueOf(golden[1]);
            String expectDocId = golden[2];
            boolean needEmbedding = Boolean.parseBoolean(golden[3]);
            if (needEmbedding && !embeddingClient.enabled()) {
                continue; // 语义型查询依赖向量通道，未配 embedding key 时跳过该条
            }
            List<HybridRetrievalService.KnowledgeHit> hits = hybrid.search(agentType, query, 5);
            boolean hit = hits.stream().anyMatch(h -> expectDocId.equals(h.docId()));
            if (!hit) {
                failures.add(new String[]{query, expectDocId,
                        hits.stream().map(HybridRetrievalService.KnowledgeHit::docId).toList().toString()});
            }
        }
        assertTrue(failures.isEmpty(),
                () -> "检索 recall@5 未达标: " + failures.stream()
                        .map(f -> f[0] + " 期望 " + f[1] + " 实际 " + f[2])
                        .reduce("", (a, b) -> a + "\n" + b));
    }
}
