package com.example.mall.agentservice.graph;

import com.example.mall.agentservice.graph.GraphQueryPort.Evidence;
import com.example.mall.agentservice.graph.GraphQueryPort.EvidencePack;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.GraphQueryPort.MissingContext;
import com.example.mall.agentservice.graph.GraphQueryPort.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Neo4j 图查询实现（v2 层）：一条 Cypher 完成多跳推理与三路证据采集（P0-3）——
 * <ol>
 *   <li><b>支持证据</b>：UNWIND 多指标信号 → 匹配方向一致的
 *       {@code (Indicator)-[ABNORMAL_HIGH|ABNORMAL_LOW]->(Disease)} 边 → 按命中指标数排序
 *       （多指标联合定位，关系表做不到的差异化能力）；</li>
 *   <li><b>反向证据</b>：{@code (Indicator)-[CONTRAINDICATES]->(Disease)}，方向一致或 ANY 时命中，
 *       用于削弱/排除（避免「ALT↑+AST↑ 就判脂肪肝」这类把关联边当规则的错）；</li>
 *   <li><b>缺失上下文</b>：{@code (Disease)-[REQUIRES_CONTEXT]->(Indicator)}，
 *       过滤掉本次已检测指标，剩下的即「鉴别还需但没做的检查」。</li>
 * </ol>
 * 每条支持/反向证据附带 {@code source / evidence_grade / version}（P0-2 出处与版本）。
 *
 * <p><b>P0-1 治理</b>：
 * <ul>
 *   <li>探活改用 {@code RETURN 1}（与 {@link GraphSeedRunner} 一致），不再全图 {@code count(n)}——
 *       原写法在图增长后每次解读都做一次全量扫描；</li>
 *   <li>探活结果按 TTL 缓存，避免一秒内多次解读反复建连探活；</li>
 *   <li>Cypher 为固定 1~2 跳（无可变长度路径），天然有跳数上限，不会被超深遍历拖垮；
 *       连接超时由 {@code spring.neo4j.connection-timeout} 兜底。</li>
 * </ul>
 * 图不可用（未部署/断连）→ available()=false / 查询异常由上层降级 ES 关系查表。
 */
@Component
public class Neo4jGraphQueryPort implements GraphQueryPort {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphQueryPort.class);

    /**
     * 支持 + 反向 + 缺失三路证据一次取回（模式推导相比 OPTIONAL MATCH 的额外好处：
     * 无匹配时返回空列表而非含 null 的单元素列表）。
     * 跳数固定：(i)-[r]->(d) 一跳、科室/症状/反向/缺失各一跳，无可变长度路径。
     */
    private static final String INFER_CYPHER = """
            UNWIND $signals AS s
            MATCH (i:Indicator {name: s.indicator})-[r:ABNORMAL_HIGH|ABNORMAL_LOW]->(d:Disease)
            WHERE (s.direction = 'HIGH' AND type(r) = 'ABNORMAL_HIGH')
               OR (s.direction = 'LOW'  AND type(r) = 'ABNORMAL_LOW')
            WITH d,
                 count(DISTINCT i) AS hits,
                 collect(DISTINCT {
                   ev: i.name,
                   dir: CASE type(r) WHEN 'ABNORMAL_HIGH' THEN 'HIGH' ELSE 'LOW' END,
                   src: coalesce(r.source, 'UNKNOWN'),
                   grade: coalesce(r.evidence_grade, 'C'),
                   ver: coalesce(r.version, 'unknown')
                 }) AS support,
                 [(ci:Indicator)-[cr:CONTRAINDICATES]->(d)
                    WHERE ANY(x IN $signals
                              WHERE x.indicator = ci.name
                                AND (cr.direction = 'ANY' OR cr.direction = x.direction))
                  | {ev: ci.name, dir: cr.direction, reason: coalesce(cr.reason, ''),
                     src: coalesce(cr.source, 'UNKNOWN'), grade: coalesce(cr.evidence_grade, 'C'),
                     ver: coalesce(cr.version, 'unknown')}] AS contra,
                 [(d)-[rr:REQUIRES_CONTEXT]->(ri:Indicator) WHERE NOT ri.name IN $observed
                  | {ev: ri.name, reason: coalesce(rr.reason, '')}] AS missing,
                 [(d)-[:TREATED_AT]->(dep:Department) | dep.name][0] AS department,
                 [(d)-[:HAS_SYMPTOM]->(sym:Symptom) | sym.name] AS symptoms
            RETURN d.name AS disease, department, hits, support, contra, missing, symptoms
            ORDER BY hits DESC LIMIT $limit
            """;

    private final Neo4jClient neo4jClient;
    private final long probeCacheMs;

    /** 探活结果缓存：{@code nextProbeAt} 为下次真实探活时间戳，并发重复探活无副作用（最坏多探一次）。 */
    private final AtomicLong nextProbeAt = new AtomicLong(0);
    private volatile boolean lastAvailable = false;

    public Neo4jGraphQueryPort(Neo4jClient neo4jClient,
                               @Value("${agent.graph.probe-cache-ms:5000}") long probeCacheMs) {
        this.neo4jClient = neo4jClient;
        this.probeCacheMs = probeCacheMs;
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now < nextProbeAt.get()) {
            return lastAvailable;
        }
        boolean ok = probe();
        lastAvailable = ok;
        nextProbeAt.set(now + probeCacheMs);
        return ok;
    }

    /** 轻量探活：RETURN 1 不做任何扫描，与种子灌库的探活写法保持一致。 */
    private boolean probe() {
        try {
            return neo4jClient.query("RETURN 1")
                    .fetchAs(Integer.class)
                    .mappedBy((typeSystem, record) -> record.get(0).asInt())
                    .one()
                    .isPresent();
        } catch (Exception e) {
            log.debug("Neo4j 不可用，图谱推理降级关系查表: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<EvidencePack> inferWithEvidence(List<IndicatorSignal> signals, List<String> observed, int limit) {
        List<Map<String, Object>> bound = new ArrayList<>();
        for (IndicatorSignal signal : signals) {
            Map<String, Object> m = new HashMap<>();
            m.put("indicator", signal.indicator());
            m.put("direction", signal.direction());
            bound.add(m);
        }
        List<String> observedNames = observed == null ? List.of() : List.copyOf(observed);
        return new ArrayList<>(neo4jClient.query(INFER_CYPHER)
                .bind(bound).to("signals")
                .bind(observedNames).to("observed")
                .bind(limit).to("limit")
                .fetchAs(EvidencePack.class)
                .mappedBy((typeSystem, record) -> new EvidencePack(
                        record.get("disease").asString(),
                        record.get("department").isNull() ? "" : record.get("department").asString(),
                        record.get("hits").asLong(),
                        evidences(record.get("support")),
                        evidences(record.get("contra")),
                        missingContexts(record.get("missing")),
                        record.get("symptoms").asList(org.neo4j.driver.Value::asString)))
                .all());
    }

    /** 解析 collect 出的证据 map 列表（支持证据与反向证据同构）。 */
    private static List<Evidence> evidences(org.neo4j.driver.Value value) {
        return value.asList(v -> {
            Map<String, Object> m = v.asMap();
            return new Evidence(str(m.get("ev")), str(m.get("dir")),
                    new Provenance(str(m.get("src")), str(m.get("grade")), str(m.get("ver"))));
        });
    }

    private static List<MissingContext> missingContexts(org.neo4j.driver.Value value) {
        return value.asList(v -> {
            Map<String, Object> m = v.asMap();
            return new MissingContext(str(m.get("ev")), str(m.get("reason")));
        });
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
