package com.example.mall.agentservice.graph;

import java.util.List;
import java.util.Set;

/**
 * 图 Schema 治理（P0-1）：把「节点标签 / 关系类型 / 约束索引」从散落在 Cypher 字符串里的隐式约定，
 * 提升为代码里的显式契约。教程要点：Schema 先于规模——图越大越需要先把形状钉死，
 * 否则同一语义会出现多种写法（Disease / 疾病 / disease），灌库与查询互相写脏。
 * <ul>
 *   <li>{@link #CONSTRAINTS}：name 唯一约束（幂等 IF NOT EXISTS），防止 MERGE 出重名节点；</li>
 *   <li>{@link #INDEXES}：按标签的检索索引，避免全标签扫描；</li>
 *   <li>{@link #ALL_RELATIONS}：关系类型白名单，灌库与查询共用同一份常量。</li>
 * </ul>
 */
public final class GraphSchema {

    /** 异常方向关系（支持证据）：(Indicator)-[ABNORMAL_HIGH|ABNORMAL_LOW]->(Disease)。 */
    public static final String ABNORMAL_HIGH = "ABNORMAL_HIGH";
    public static final String ABNORMAL_LOW = "ABNORMAL_LOW";

    /** 反向证据：该指标出现该方向时，削弱/排除某疾病（鉴别诊断）。 */
    public static final String CONTRAINDICATES = "CONTRAINDICATES";

    /** 缺失上下文：确诊/鉴别某疾病还需补做的指标（未检测时提示）。 */
    public static final String REQUIRES_CONTEXT = "REQUIRES_CONTEXT";

    /** 就诊科室 / 典型症状（一跳扩展素材）。 */
    public static final String TREATED_AT = "TREATED_AT";
    public static final String HAS_SYMPTOM = "HAS_SYMPTOM";

    /** 全部受控关系类型（新增关系必须先在此登记，否则灌库拒绝执行）。 */
    public static final Set<String> ALL_RELATIONS = Set.of(
            ABNORMAL_HIGH, ABNORMAL_LOW, CONTRAINDICATES, REQUIRES_CONTEXT, TREATED_AT, HAS_SYMPTOM);

    /** 受控节点标签。 */
    public static final Set<String> NODE_LABELS = Set.of("Indicator", "Disease", "Department", "Symptom");

    /** name 唯一约束（Neo4j 5 语法；IF NOT EXISTS 保证幂等，可随每次启动重放）。 */
    public static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT indicator_name IF NOT EXISTS FOR (n:Indicator) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT disease_name IF NOT EXISTS FOR (n:Disease) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT department_name IF NOT EXISTS FOR (n:Department) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT symptom_name IF NOT EXISTS FOR (n:Symptom) REQUIRE n.name IS UNIQUE");

    /** 检索索引（与唯一约束互补：约束保证不重名，索引服务于属性过滤与范围查询）。 */
    public static final List<String> INDEXES = List.of(
            "CREATE INDEX indicator_name_idx IF NOT EXISTS FOR (n:Indicator) ON (n.name)",
            "CREATE INDEX disease_name_idx IF NOT EXISTS FOR (n:Disease) ON (n.name)");

    /** 关系边上的受控属性名（出处/证据等级/版本/适用人群/条件说明——P0-2）。 */
    public static final String PROP_SOURCE = "source";
    public static final String PROP_EVIDENCE_GRADE = "evidence_grade";
    public static final String PROP_VERSION = "version";
    public static final String PROP_POPULATION = "population";
    public static final String PROP_CAVEAT = "caveat";

    private GraphSchema() {
    }

    /** 关系类型是否在白名单内（灌库前校验，挡住拼写错误导致的脏关系）。 */
    public static boolean isKnownRelation(String type) {
        return ALL_RELATIONS.contains(type);
    }
}
