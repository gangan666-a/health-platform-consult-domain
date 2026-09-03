package com.example.mall.agentservice.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * 图谱种子灌库（演示级，幂等 MERGE）：与 ES knowledge_relation 同源的种子边
 * （MedicalKnowledgeSeeds）写入 Neo4j。
 *
 * <p><b>P0-1 治理</b>：灌库前先执行 {@link GraphSchema#CONSTRAINTS} 与 {@link GraphSchema#INDEXES}
 * （{@code IF NOT EXISTS} 幂等），把 name 唯一性交给数据库保证，而不是靠 MERGE 的侥幸——
 * 这是「Schema 先于规模」的落地动作。关系类型经 {@link GraphSchema#isKnownRelation} 白名单校验，
 * 拼错的边会被明确拒绝并计入失败日志，而不是静默写进图里。
 *
 * <p><b>P0-3 三类边</b>：
 * <ul>
 *   <li>(Indicator)-[ABNORMAL_HIGH/LOW {source,evidence_grade,version,...}]->(Disease)：支持证据；</li>
 *   <li>(Indicator)-[CONTRAINDICATES {direction,reason,source}]->(Disease)：反向证据；</li>
 *   <li>(Disease)-[REQUIRES_CONTEXT {reason}]->(Indicator)：缺失上下文。</li>
 * </ul>
 * 未部署 Neo4j（连接拒绝）→ 跳过并提示，不阻断启动。
 */
@Component
@Order(20)
public class GraphSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphSeedRunner.class);

    /**
     * 支持证据边（关系类型不能参数化，按方向两条模板，其余结构共享）。
     * 边属性带出处/证据等级/版本/适用人群/条件说明（P0-2）。
     */
    private static final String MERGE_ABNORMAL_TEMPLATE = """
            MERGE (i:Indicator {name: $indicator})
            MERGE (d:Disease {name: $disease})
            MERGE (dep:Department {name: $department})
            MERGE (d)-[:TREATED_AT]->(dep)
            MERGE (i)-[r:%s]->(d)
            SET r.source = $source,
                r.evidence_grade = $grade,
                r.version = $version,
                r.population = $population,
                r.caveat = $caveat,
                r.relation_id = $relationId
            FOREACH (sym IN $symptoms |
              MERGE (s:Symptom {name: sym})
              MERGE (d)-[:HAS_SYMPTOM]->(s))
            """;

    /** 反向证据边：指标异常方向削弱/排除某疾病。 */
    private static final String MERGE_COUNTER = """
            MERGE (i:Indicator {name: $indicator})
            MERGE (d:Disease {name: $disease})
            MERGE (i)-[r:CONTRAINDICATES]->(d)
            SET r.direction = $direction,
                r.reason = $reason,
                r.source = $source,
                r.relation_id = $relationId
            """;

    /** 缺失上下文边：鉴别某疾病还需补做的指标。 */
    private static final String MERGE_CONTEXT = """
            MERGE (d:Disease {name: $disease})
            MERGE (i:Indicator {name: $indicator})
            MERGE (d)-[r:REQUIRES_CONTEXT]->(i)
            SET r.reason = $reason,
                r.relation_id = $relationId
            """;

    private final Neo4jClient neo4jClient;
    private final boolean enabled;

    public GraphSeedRunner(Neo4jClient neo4jClient, Environment environment) {
        this.neo4jClient = neo4jClient;
        this.enabled = environment.getProperty("agent.graph.seed-enabled", Boolean.class, true);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("图谱种子灌库已关闭（agent.graph.seed-enabled=false）");
            return;
        }
        try {
            // 连接探活：未部署时快速失败跳过（本机连接拒绝即时返回）
            neo4jClient.query("RETURN 1").fetchAs(Integer.class)
                    .mappedBy((ts, r) -> r.get(0).asInt()).one();
        } catch (Exception e) {
            log.info("Neo4j 不可用（{}），图谱种子跳过——图推理将降级 ES 关系查表", e.getMessage());
            return;
        }
        applySchema();
        int abnormal = seedAbnormalEdges();
        int counter = seedCounterEdges();
        int context = seedContextEdges();
        log.info("图谱种子灌库完成：支持边 {} 条 / 反向边 {} 条 / 缺失上下文 {} 条（Neo4j，知识版本 {}）",
                abnormal, counter, context, MedicalKnowledgeSeeds.KNOWLEDGE_VERSION);
    }

    /** 建约束与索引（幂等）：唯一约束保证同名实体不会 MERGE 成多个节点。 */
    private void applySchema() {
        for (String ddl : GraphSchema.CONSTRAINTS) {
            try {
                neo4jClient.query(ddl).run();
            } catch (Exception e) {
                log.warn("图约束创建失败（跳过，不影响灌库）: {} -> {}", ddl, e.getMessage());
            }
        }
        for (String ddl : GraphSchema.INDEXES) {
            try {
                neo4jClient.query(ddl).run();
            } catch (Exception e) {
                log.warn("图索引创建失败（跳过，不影响灌库）: {} -> {}", ddl, e.getMessage());
            }
        }
    }

    private int seedAbnormalEdges() {
        int seeded = 0;
        for (MedicalKnowledgeSeeds.SeedEdge edge : MedicalKnowledgeSeeds.EDGES) {
            String relationType = "HIGH".equals(edge.direction())
                    ? GraphSchema.ABNORMAL_HIGH
                    : GraphSchema.ABNORMAL_LOW;
            if (!GraphSchema.isKnownRelation(relationType)) {
                log.warn("关系类型不在白名单，已拒绝写入: {}", relationType);
                continue;
            }
            try {
                neo4jClient.query(MERGE_ABNORMAL_TEMPLATE.formatted(relationType))
                        .bind(edge.indicator()).to("indicator")
                        .bind(edge.disease()).to("disease")
                        .bind(edge.department()).to("department")
                        .bind(edge.symptoms()).to("symptoms")
                        .bind(edge.source()).to("source")
                        .bind(edge.evidenceGrade()).to("grade")
                        .bind(edge.version()).to("version")
                        .bind(nvl(edge.population())).to("population")
                        .bind(nvl(edge.caveat())).to("caveat")
                        .bind(relationId(edge.indicator(), edge.direction(), edge.disease())).to("relationId")
                        .run();
                seeded++;
            } catch (Exception e) {
                log.warn("支持边写入失败（{} - {} -> {}）: {}", edge.indicator(), relationType,
                        edge.disease(), e.getMessage());
            }
        }
        return seeded;
    }

    private int seedCounterEdges() {
        int seeded = 0;
        for (MedicalKnowledgeSeeds.CounterEdge edge : MedicalKnowledgeSeeds.COUNTER_EDGES) {
            try {
                neo4jClient.query(MERGE_COUNTER)
                        .bind(edge.indicator()).to("indicator")
                        .bind(edge.disease()).to("disease")
                        .bind(edge.direction()).to("direction")
                        .bind(edge.reason()).to("reason")
                        .bind(MedicalKnowledgeSeeds.SRC_DEMO).to("source")
                        .bind("c-" + relationId(edge.indicator(), edge.direction(), edge.disease()))
                        .to("relationId")
                        .run();
                seeded++;
            } catch (Exception e) {
                log.warn("反向边写入失败（{} - {} -> {}）: {}", edge.indicator(), edge.direction(),
                        edge.disease(), e.getMessage());
            }
        }
        return seeded;
    }

    private int seedContextEdges() {
        int seeded = 0;
        for (MedicalKnowledgeSeeds.ContextRequirement req : MedicalKnowledgeSeeds.CONTEXT_REQUIREMENTS) {
            try {
                neo4jClient.query(MERGE_CONTEXT)
                        .bind(req.disease()).to("disease")
                        .bind(req.indicator()).to("indicator")
                        .bind(req.reason()).to("reason")
                        .bind("ctx-" + req.disease() + "-" + req.indicator()).to("relationId")
                        .run();
                seeded++;
            } catch (Exception e) {
                log.warn("缺失上下文边写入失败（{} - {}）: {}", req.disease(), req.indicator(), e.getMessage());
            }
        }
        return seeded;
    }

    /** 关系稳定标识：同一 (指标, 方向, 疾病) 三元组重复灌库时复用同一条边。 */
    private static String relationId(String indicator, String direction, String disease) {
        return indicator + "-" + direction + "-" + disease;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
