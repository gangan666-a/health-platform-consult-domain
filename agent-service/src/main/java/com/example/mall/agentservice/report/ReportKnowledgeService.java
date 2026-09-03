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

import java.util.List;
import java.util.Map;

/**
 * 报告解读知识库检索（T-2 降级纪律）：从报告相关的 ES 知识索引检索解释性片段，
 * 注入 REPORT 解读上下文（数据最小化：仅指标相关片段，无 PII）。
 * <p>
 * 本期启用的关联索引：
 * <ul>
 *   <li>{@link KnowledgeIndex#LAB_EXAM}：异常指标含义（high_meaning/low_meaning）+ 异常建议（abnormal_advice）；</li>
 *   <li>{@link KnowledgeIndex#REPORT_TEMPLATE}：检查项目模板与字段映射（template_name/description/layout）。</li>
 * </ul>
 * 其余报告相关索引（symptom/disease/department/drug/drug_interaction/health_content/faq_compliance/knowledge_relation）
 * 作为关系图谱与就诊/用药建议的知识来源，待知识库灌入后按需启用（见 PRD §14.5.4）。
 * <p>
 * 任一索引不可用或未命中 → 该片段为空，绝不阻断报告解读与落库（对齐 E-4 / T-2）。
 */
@Component
public class ReportKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(ReportKnowledgeService.class);

    private final ElasticsearchClient esClient;

    public ReportKnowledgeService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * 生成报告解读的知识片段：检查模板 + 各异常指标的含义与建议。
     *
     * @return 知识片段文本；无任何命中或 ES 不可用时返回空串。
     */
    public String enrich(ReportDoc doc) {
        StringBuilder sb = new StringBuilder();
        String template = templateKnowledge(doc);
        if (template != null && !template.isBlank()) {
            sb.append(template).append('\n');
        }
        for (ReportIndicator in : doc.indicators()) {
            if (in.status() == IndicatorStatus.HIGH || in.status() == IndicatorStatus.LOW) {
                String knowledge = indicatorKnowledge(in);
                if (knowledge != null && !knowledge.isBlank()) {
                    sb.append(knowledge).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 查 report_template：按检查项目名匹配模板，返回模板名/说明/版式。 */
    private String templateKnowledge(ReportDoc doc) {
        try {
            Query query = Query.of(q -> q.bool(b -> b
                    .must(m -> m.match(t -> t.field("template_name").query(doc.examName())))
                    .filter(f -> f.term(TermQuery.of(t -> t.field("agent_type").value("REPORT"))))));
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(KnowledgeIndex.REPORT_TEMPLATE.alias())
                    .query(query)
                    .size(1), Map.class);
            if (resp.hits() == null || resp.hits().hits().isEmpty()) {
                return null;
            }
            Map<String, Object> src = resp.hits().hits().get(0).source();
            if (src == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("【报告模板】");
            sb.append(str(src.get("template_name"))).append("：").append(str(src.get("description")));
            if (src.get("layout") != null) {
                sb.append("（版式 ").append(src.get("layout")).append('）');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("ES report_template 模板检索失败，降级为空: {}", e.getMessage());
            return null;
        }
    }

    /** 查 lab_exam：命中异常指标 → 返回「含义 + 建议」知识行。 */
    private String indicatorKnowledge(ReportIndicator in) {
        try {
            Query nested = Query.of(q -> q.bool(b -> b.filter(f -> f.term(
                    TermQuery.of(t -> t.field("indicators.name").value(in.name()))))));
            Query query = Query.of(q -> q.bool(b -> b.filter(f -> f.nested(
                    NestedQuery.of(n -> n.path("indicators").query(nested))))));
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(KnowledgeIndex.LAB_EXAM.alias())
                    .query(query)
                    .size(3), Map.class);
            if (resp.hits() == null || resp.hits().hits().isEmpty()) {
                return null;
            }
            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) {
                    continue;
                }
                String line = extractIndicatorKnowledge(src, in.name(),
                        in.status() == IndicatorStatus.HIGH ? "high_meaning" : "low_meaning");
                if (line != null) {
                    return line;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("ES lab_exam 异常指标知识检索失败，降级为空: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 lab_exam 文档中提取指定指标的含义字段并拼接异常建议。static 便于单测。
     *
     * @param src        ES lab_exam 文档（Map 形式）
     * @param name       指标名（如 ALT）
     * @param meaningKey high_meaning / low_meaning
     * @return 格式化知识行；指标未命中返回 null
     */
    @SuppressWarnings("unchecked")
    static String extractIndicatorKnowledge(Map<String, Object> src, String name, String meaningKey) {
        Object indicators = src.get("indicators");
        if (!(indicators instanceof List<?> list)) {
            return null;
        }
        String meaning = null;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object n = m.get("name");
                if (n != null && String.valueOf(n).equalsIgnoreCase(name)) {
                    Object meaningVal = m.get(meaningKey);
                    if (meaningVal != null) {
                        meaning = String.valueOf(meaningVal);
                    }
                    break;
                }
            }
        }
        if (meaning == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【异常指标知识】").append(name)
                .append("（").append(IndicatorStatus.valueOf(meaningKey.equals("high_meaning") ? "HIGH" : "LOW").display())
                .append("）").append('：').append(meaning);
        Object advice = src.get("abnormal_advice");
        if (advice != null && !String.valueOf(advice).isBlank()) {
            sb.append("；建议：").append(advice);
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
