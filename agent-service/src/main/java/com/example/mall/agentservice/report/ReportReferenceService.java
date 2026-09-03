package com.example.mall.agentservice.report;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.NestedQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.mall.agentservice.es.KnowledgeIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参考范围比对（防线1，B-8）：**报告自带参考范围优先**（不同机构/方法学参考范围不同，
 * 用通用范围比对可能误判），ES lab_exam 权威范围仅在报告自带范围缺失/不可解析时兜底，
 * 两者同时存在且不一致时记录交叉核验日志、仍以报告自带为准。
 * 异常标记由 RangeParser 纯 Java 判定（不经过 LLM）；referenceVerified=false 表示
 * 「ES 不可达、未经知识库交叉核验」（E-4）。
 */
@Component
public class ReportReferenceService {

    private static final Logger log = LoggerFactory.getLogger(ReportReferenceService.class);

    private final ElasticsearchClient esClient;

    public ReportReferenceService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public ReportDoc align(ReportDoc doc) {
        boolean esOk = true;
        List<ReportIndicator> aligned = new ArrayList<>(doc.indicators().size());
        for (ReportIndicator in : doc.indicators()) {
            LookupResult result = lookupRefRange(in.name());
            if (!result.esOk()) {
                esOk = false;
            }
            Resolved resolved = resolveRange(in.value(), in.refRange(), result.range());
            crossCheck(in, result.range(), resolved);
            aligned.add(new ReportIndicator(in.name(), in.value(), in.unit(),
                    resolved.ref(), resolved.status(), in.examDate()));
        }
        return new ReportDoc(doc.examName(), doc.reportDate(), doc.source(), doc.type(),
                doc.rawText(), esOk, aligned);
    }

    /**
     * 范围优先级决策（static 便于单测）：自带可用 → 用自带；
     * 自带缺失/不可解析且 ES 命中可用 → 用 ES；否则保留原文范围、状态 UNKNOWN（不猜）。
     */
    static Resolved resolveRange(String value, String ownRange, String esRange) {
        if (RangeParser.isRangeUsable(ownRange)) {
            return new Resolved(ownRange.trim(), RangeParser.computeStatus(value, ownRange));
        }
        if (esRange != null && RangeParser.isRangeUsable(esRange)) {
            return new Resolved(esRange.trim(), RangeParser.computeStatus(value, esRange));
        }
        String ref = ownRange == null ? "" : ownRange.trim();
        if (ref.isBlank() && esRange != null) {
            ref = esRange.trim();
        }
        return new Resolved(ref, IndicatorStatus.UNKNOWN);
    }

    /** 交叉核验：两者都在且判定口径不一致时告警（以报告自带为准，医疗安全口径）。 */
    private static void crossCheck(ReportIndicator in, String esRange, Resolved resolved) {
        if (esRange == null || esRange.isBlank() || !RangeParser.isRangeUsable(in.refRange())) {
            return;
        }
        IndicatorStatus byEs = RangeParser.computeStatus(in.value(), esRange);
        if (byEs != resolved.status()) {
            log.info("参考范围交叉核验不一致（以报告自带为准）: {}={} 自带[{}]→{} ES[{}]→{}",
                    in.name(), in.value(), in.refRange(), resolved.status(), esRange, byEs);
        }
    }

    private LookupResult lookupRefRange(String indicatorName) {
        try {
            Query nested = Query.of(q -> q.bool(b -> b.filter(f -> f.term(
                    TermQuery.of(t -> t.field("indicators.name").value(indicatorName))))));
            Query query = Query.of(q -> q.bool(b -> b.filter(f -> f.nested(
                    NestedQuery.of(n -> n.path("indicators").query(nested))))));
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(KnowledgeIndex.LAB_EXAM.alias())
                    .query(query)
                    .size(5), Map.class);
            if (resp.hits() == null || resp.hits().hits().isEmpty()) {
                return new LookupResult(null, true);
            }
            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) {
                    continue;
                }
                String range = findRefRange(src, indicatorName);
                if (range != null) {
                    return new LookupResult(range, true);
                }
            }
            return new LookupResult(null, true);
        } catch (Exception e) {
            log.warn("ES lab_exam 参考范围查询失败，仅用报告自带范围: {}", e.getMessage());
            return new LookupResult(null, false);
        }
    }

    @SuppressWarnings("unchecked")
    private static String findRefRange(Map<String, Object> src, String indicatorName) {
        Object indicators = src.get("indicators");
        if (!(indicators instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object name = m.get("name");
                if (name != null && String.valueOf(name).equalsIgnoreCase(indicatorName)) {
                    Object ref = m.get("ref_range");
                    return ref == null ? null : String.valueOf(ref);
                }
            }
        }
        return null;
    }

    /** range=null 表示未命中；esOk=false 表示 ES 不可用（E-4 标注） */
    private record LookupResult(String range, boolean esOk) {
    }

    /** 范围优先级决策结果：最终生效的参考范围 + 依据它判定的状态。 */
    record Resolved(String ref, IndicatorStatus status) {
    }
}
