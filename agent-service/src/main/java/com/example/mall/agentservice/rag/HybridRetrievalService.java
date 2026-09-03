package com.example.mall.agentservice.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.mall.agentservice.config.EmbeddingClient;
import com.example.mall.agentservice.es.KnowledgeIndex;
import com.example.mall.common.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid 检索：BM25 关键词 + kNN 向量（阿里 text-embedding-v3）双路召回 + 客户端 RRF 融合。
 * 设计取舍（面试要点）：
 * ① 指标名/药品名等精确术语 BM25 强，口语化表述（"血脂稠"）靠向量，RRF 融合互补；
 * ② ES 原生 RRF 融合有 license 限制，改为客户端 RRF（两路各查 topN，rank 融合公式 1/(k+rank)）；
 * ③ 向量通道未配置/失败 → BM25-only 降级；ES 整体不可用 → 空上下文（T-2 纪律）。
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    /** RRF 常数（标准值 60）。 */
    private static final int RRF_K = 60;
    private static final int LEG_TOP = 10;
    private static final int SNIPPET_CHARS = 300;

    /** 命中文档（评测与格式化共用）。 */
    public record KnowledgeHit(String index, String docId, String title, String snippet) {
    }

    /** 参与检索的逻辑索引与检索字段（按种子数据覆盖范围选择，未灌数的索引不查省开销）。 */
    private record IndexFields(KnowledgeIndex index, List<String> matchFields, String titleField) {
    }

    private static final List<IndexFields> SEARCHABLE = List.of(
            new IndexFields(KnowledgeIndex.HEALTH_CONTENT, List.of("title^2", "content"), "title"),
            new IndexFields(KnowledgeIndex.LAB_EXAM, List.of("exam_name^2", "abnormal_advice"), "exam_name"),
            new IndexFields(KnowledgeIndex.REPORT_TEMPLATE, List.of("template_name^2", "description"), "template_name"));

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;

    public HybridRetrievalService(ElasticsearchClient esClient, EmbeddingClient embeddingClient) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
    }

    /** 格式化为注入 prompt 的知识片段；无命中/降级返回空串。 */
    public String retrieve(AgentType agentType, String query, int topK) {
        List<KnowledgeHit> hits = search(agentType, query, topK);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeHit hit : hits) {
            sb.append("【知识库·").append(hit.index()).append("】")
                    .append(hit.title()).append('\n')
                    .append(hit.snippet()).append("\n---\n");
        }
        return sb.toString();
    }

    /** 双路召回 + RRF 融合；任何一路失败用另一路结果，全部失败返回空列表。 */
    public List<KnowledgeHit> search(AgentType agentType, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<KnowledgeHit> keywordHits = null;
        List<KnowledgeHit> vectorHits = null;
        try {
            keywordHits = keywordSearch(agentType, query);
        } catch (Exception e) {
            log.warn("hybrid BM25 路失败: {}", e.getMessage());
        }
        if (embeddingClient.enabled()) {
            try {
                float[] vector = embeddingClient.embed(query);
                if (vector != null) {
                    vectorHits = vectorSearch(agentType, vector);
                }
            } catch (Exception e) {
                log.warn("hybrid 向量路失败（降级 BM25-only）: {}", e.getMessage());
            }
        }
        if (keywordHits == null && vectorHits == null) {
            return List.of(); // 双路全失败 → 空上下文（T-2）
        }
        Map<String, KnowledgeHit> byId = new LinkedHashMap<>();
        keywordHits = keywordHits == null ? List.of() : keywordHits;
        vectorHits = vectorHits == null ? List.of() : vectorHits;
        for (List<KnowledgeHit> leg : List.of(keywordHits, vectorHits)) {
            for (KnowledgeHit hit : leg) {
                byId.putIfAbsent(hit.docId(), hit);
            }
        }
        return rrfFuse(ids(keywordHits), ids(vectorHits), topK).stream()
                .map(byId::get)
                .toList();
    }

    private List<KnowledgeHit> keywordSearch(AgentType agentType, String query) throws Exception {
        List<KnowledgeHit> hits = new ArrayList<>();
        for (IndexFields fields : SEARCHABLE) {
            Query filtered = Query.of(q -> q.bool(b -> b
                    .must(m -> m.multiMatch(mm -> mm.fields(fields.matchFields()).query(query)))
                    .filter(f -> f.term(t -> t.field("agent_type").value(agentType.name())))));
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(fields.index().alias())
                    .query(filtered)
                    .size(LEG_TOP), Map.class);
            hits.addAll(toHits(fields, resp));
        }
        return hits.size() > LEG_TOP ? hits.subList(0, LEG_TOP) : hits;
    }

    private List<KnowledgeHit> vectorSearch(AgentType agentType, float[] vector) throws Exception {
        List<Float> queryVector = toFloatList(vector);
        List<KnowledgeHit> hits = new ArrayList<>();
        for (IndexFields fields : SEARCHABLE) {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(fields.index().alias())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVector)
                            .numCandidates(50)
                            .k(LEG_TOP)
                            .filter(f -> f.term(t -> t.field("agent_type").value(agentType.name()))))
                    .size(0), Map.class);
            // knn 命中在 resp.hits() 里带 _score；size(0)+knn 语义：返回 knn 命中文档
            hits.addAll(toHits(fields, resp));
        }
        return hits.size() > LEG_TOP ? hits.subList(0, LEG_TOP) : hits;
    }

    private static List<KnowledgeHit> toHits(IndexFields fields, SearchResponse<Map> resp) {
        List<KnowledgeHit> hits = new ArrayList<>();
        if (resp.hits() == null || resp.hits().hits() == null) {
            return hits;
        }
        for (Hit<Map> hit : resp.hits().hits()) {
            Map<String, Object> src = hit.source();
            if (src == null) {
                continue;
            }
            String docId = str(src.get("doc_id"));
            String title = str(src.get(fields.titleField()));
            String snippet = truncate(str(src.get("content")) + str(src.get("abnormal_advice"))
                    + str(src.get("description")), SNIPPET_CHARS);
            hits.add(new KnowledgeHit(fields.index().logicalName(),
                    docId.isEmpty() ? hit.id() : docId, title, snippet));
        }
        return hits;
    }

    /**
     * 客户端 RRF 融合：score(doc) = Σ 1/(RRF_K + rank)，两路任一路命中即可参与排序。
     * static 便于单测（评测门禁的一部分）。
     */
    static List<String> rrfFuse(List<String> keywordIds, List<String> vectorIds, int topK) {
        Map<String, Double> scores = new HashMap<>();
        accumulate(scores, keywordIds);
        accumulate(scores, vectorIds);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(Math.max(0, topK))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void accumulate(Map<String, Double> scores, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private static List<String> ids(List<KnowledgeHit> hits) {
        return hits.stream().map(KnowledgeHit::docId).toList();
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
