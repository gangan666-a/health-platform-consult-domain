package com.example.mall.agentservice.eval;

import com.example.mall.agentservice.graph.GraphQueryPort;
import com.example.mall.agentservice.graph.GraphQueryPort.EvidencePack;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.RelationLookupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱 vs 关系表对比评测（env-gated）：设置 AGENT_EVAL_NEO4J=true 启用（需本机 Neo4j，
 * 种子由 GraphSeedRunner 在上下文启动时幂等灌入）。
 * 金标 = 多指标联合推理题（accuracy@1）：图推理按「命中指标数」排序，
 * 关系表 v1 只做逐指标并集（不排序）——多指标命中且噪声边在前时，图 top1 显著更准。
 * ES（knowledge_relation 种子）可用时一并给出对照数据；不可用则只校验图门禁。
 *
 * <p>除 accuracy@1 外，本评测同时校验 P0-2/P0-3 的落地：
 * 每条支持证据必须带出处与证据等级；未检测的鉴别指标必须出现在「缺失上下文」里。
 */
@SpringBootTest(properties = {
        // 测试资源 properties 整文件遮蔽主配置，这里显式带上 Neo4j 连接（与本地实例一致）
        "spring.neo4j.uri=bolt://localhost:7687",
        "spring.neo4j.authentication.username=neo4j",
        "spring.neo4j.authentication.password=neo4j-password"
})
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL_NEO4J", matches = "true")
class GraphEvalTest {

    record Golden(List<IndicatorSignal> signals, String expectTop1, String expectDepartment,
                  String expectMissing) {
    }

    /** 噪声单命中指标排前：v1 并集首结果会被噪声占据，图排序不受信号顺序影响。 */
    private static final List<Golden> GOLDEN = List.of(
            // 动脉粥样硬化需 HDL 才算完整血脂谱，本次未检测 → 必出现缺失上下文
            new Golden(List.of(sig("ALT", "HIGH"), sig("LDL", "HIGH"), sig("TC", "HIGH")),
                    "动脉粥样硬化", "心内科", "HDL"),
            // 缺铁性贫血的鉴别指标（FE/HGB）本次均已检测 → 不应再提示补做
            new Golden(List.of(sig("WBC", "HIGH"), sig("HGB", "LOW"), sig("FE", "LOW")),
                    "缺铁性贫血", "血液科", null),
            new Golden(List.of(sig("UA", "HIGH"), sig("GLU", "HIGH"), sig("HBA1C", "HIGH")),
                    "糖尿病", "内分泌科", null));

    private static IndicatorSignal sig(String indicator, String direction) {
        return new IndicatorSignal(indicator, direction);
    }

    @Autowired
    GraphQueryPort graphPort;
    @Autowired
    RelationLookupService relationLookup;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void graphInference_accuracyAt1_beatsFlatRelationTable() {
        assertTrue(graphPort.available(), "Neo4j 不可用：请先启动并让 GraphSeedRunner 灌入种子");

        long graphHits = 0;
        long flatHits = 0;
        StringBuilder report = new StringBuilder();
        for (Golden golden : GOLDEN) {
            List<String> observed = golden.signals().stream().map(IndicatorSignal::indicator).toList();
            EvidencePack top1 = graphPort.inferWithEvidence(golden.signals(), observed, 5).get(0);
            if (golden.expectTop1().equals(top1.disease())) {
                graphHits++;
            }
            assertEquals(golden.expectDepartment(), top1.department(), "图推理应带出正确就诊科室");
            assertTrue(top1.hits() >= 2, "金标均为多指标命中场景");

            // P0-2：出处是一等属性——每条支持证据都要能溯源、能分级，未标注来源的边视为治理缺失
            assertFalse(top1.supporting().isEmpty(), top1.disease() + " 应有支持证据");
            top1.supporting().forEach(ev -> {
                assertFalse(ev.provenance().source().isBlank()
                                || "UNKNOWN".equals(ev.provenance().source()),
                        "支持证据必须带出处: " + ev.indicator());
                assertFalse(ev.provenance().evidenceGrade().isBlank(),
                        "支持证据必须带证据等级: " + ev.indicator());
            });

            // P0-3：缺失上下文只在指标未检测时出现
            List<String> missing = top1.missing().stream()
                    .map(GraphQueryPort.MissingContext::indicator).toList();
            if (golden.expectMissing() == null) {
                assertTrue(missing.isEmpty(),
                        top1.disease() + " 的鉴别指标均已检测，不应提示补做，实际: " + missing);
            } else {
                assertTrue(missing.contains(golden.expectMissing()),
                        top1.disease() + " 缺失上下文应包含 " + golden.expectMissing() + "，实际: " + missing);
            }

            report.append(golden.expectTop1()).append(" → 图top1=").append(top1.disease())
                    .append("(hits=").append(top1.hits()).append(")；");

            // v1 对照：逐指标并集首结果（依赖查询顺序，无跨指标排序）
            List<RelationLookupService.RelationEdge> edges = relationLookup.lookup(golden.signals());
            if (!edges.isEmpty() && golden.expectTop1().equals(edges.get(0).disease())) {
                flatHits++;
            }
        }
        assertEquals(GOLDEN.size(), graphHits, "图推理 accuracy@1 必须 3/3: " + report);
        // 对照结论（非硬断言：ES 种子未灌时不惩罚图门禁）
        System.out.println("[GraphEval] accuracy@1 —— 图推理 " + graphHits + "/" + GOLDEN.size()
                + " vs 关系表 " + flatHits + "/" + GOLDEN.size() + "；" + report);
    }

    /**
     * 反向证据：细菌感染由 WBC↑ 支持，但 PLT↓ 反向削弱它（更提示血液系统疾病）。
     * 注意范围——反向证据只对<b>已有支持证据的候选疾病</b>生效：
     * 为零支持疾病也召回反向证据会让候选集爆炸，且对解读无增益。
     */
    @Test
    void counterEvidence_isRecalledForSupportedCandidate() {
        assertTrue(graphPort.available(), "Neo4j 不可用");

        List<EvidencePack> packs = graphPort.inferWithEvidence(
                List.of(sig("WBC", "HIGH"), sig("PLT", "LOW")), List.of("WBC", "PLT"), 10);

        EvidencePack infection = packs.stream()
                .filter(p -> "细菌感染".equals(p.disease()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WBC↑ 应支持细菌感染方向"));
        assertTrue(infection.supporting().stream().anyMatch(e -> "WBC".equals(e.indicator())),
                "WBC↑ 为细菌感染的支持证据");
        assertTrue(infection.contradicting().stream().anyMatch(e -> "PLT".equals(e.indicator())),
                "PLT↓ 应作为细菌感染的反向证据被召回，实际反向: " + infection.contradicting());
    }
}
