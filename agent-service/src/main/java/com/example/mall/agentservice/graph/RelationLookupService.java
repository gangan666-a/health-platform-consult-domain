package com.example.mall.agentservice.graph;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.mall.agentservice.es.KnowledgeIndex;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标-疾病关系查表（v1 层，ES knowledge_relation 索引）：逐指标查 INDICATES_HIGH/LOW 边，
 * 返回并集（不做跨指标命中排序——这正是 v1 关系表与 v2 图推理的能力差异）。
 * 图谱不可用时的降级载体；ES 不可用时返回空（T-2 纪律）。
 *
 * <p><b>P0-2</b>：查表结果同样带出出处与证据等级（v1/v2 同源知识，两侧都要可溯源），
 * 字段缺失时回退为「UNKNOWN / C」而不是静默丢弃。
 */
@Component
public class RelationLookupService {

    private static final Logger log = LoggerFactory.getLogger(RelationLookupService.class);

    /**
     * v1 查表结果：指标+方向 → 疾病，附出处与证据等级。
     *
     * @param source        知识来源（指南/教科书/演示种子）
     * @param evidenceGrade 证据等级 A/B/C
     */
    public record RelationEdge(String indicator, String direction, String disease,
                               String source, String evidenceGrade) {

        /** 兼容构造：无出处信息的历史数据按 C 级处理。 */
        public RelationEdge(String indicator, String direction, String disease) {
            this(indicator, direction, disease, "UNKNOWN", "C");
        }
    }

    private final ElasticsearchClient esClient;

    public RelationLookupService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public List<RelationEdge> lookup(List<IndicatorSignal> signals) {
        List<RelationEdge> edges = new ArrayList<>();
        for (IndicatorSignal signal : signals) {
            try {
                Query query = Query.of(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("from_type").value("INDICATOR")))
                        .filter(f -> f.term(t -> t.field("from_id").value(signal.indicator())))
                        .filter(f -> f.term(t -> t.field("relation")
                                .value("INDICATES_" + signal.direction())))));
                SearchResponse<Map> resp = esClient.search(s -> s
                        .index(KnowledgeIndex.KNOWLEDGE_RELATION.alias())
                        .query(query)
                        .size(10), Map.class);
                if (resp.hits() == null || resp.hits().hits() == null) {
                    continue;
                }
                for (Hit<Map> hit : resp.hits().hits()) {
                    Map<String, Object> src = hit.source();
                    if (src == null) {
                        continue;
                    }
                    Object toId = src.get("to_id");
                    if (toId != null && !String.valueOf(toId).isBlank()) {
                        edges.add(new RelationEdge(signal.indicator(), signal.direction(),
                                String.valueOf(toId),
                                str(src.get("source"), "UNKNOWN"),
                                str(src.get("evidence_grade"), "C")));
                    }
                }
            } catch (Exception e) {
                log.warn("ES knowledge_relation 查表失败（{} {}）: {}", signal.indicator(),
                        signal.direction(), e.getMessage());
            }
        }
        return edges;
    }

    private static String str(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }
}
