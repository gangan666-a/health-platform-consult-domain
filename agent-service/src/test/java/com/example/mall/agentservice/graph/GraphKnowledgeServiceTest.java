package com.example.mall.agentservice.graph;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.mall.agentservice.graph.GraphQueryPort.Evidence;
import com.example.mall.agentservice.graph.GraphQueryPort.EvidencePack;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.GraphQueryPort.MissingContext;
import com.example.mall.agentservice.graph.GraphQueryPort.Provenance;
import com.example.mall.agentservice.report.IndicatorNormalizer;
import com.example.mall.agentservice.report.IndicatorStatus;
import com.example.mall.agentservice.report.ReportIndicator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识关联服务：信号提取（归一+方向）→ 图优先 / 降级关系查表 / 全降级空（T-2 纪律）。
 * P1-1 后追加：规则判定段始终输出、且不依赖图（图不可用时规则仍给出结论）。
 */
class GraphKnowledgeServiceTest {

    private static final Provenance GUIDE = new Provenance("中国血脂管理指南(2023)", "A", "2026.09");
    private static final Provenance DEMO = new Provenance("演示种子(待医学审核)", "C", "2026.09");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReportIndicator indicator(String name, IndicatorStatus status) {
        return new ReportIndicator(name, "1.0", "", "", status, LocalDate.now());
    }

    private static CombinationRuleEngine ruleEngine() {
        return new CombinationRuleEngine(MAPPER);
    }

    private static GraphQueryPort unavailablePort() {
        return new GraphQueryPort() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public List<EvidencePack> inferWithEvidence(List<IndicatorSignal> signals,
                                                        List<String> observed, int limit) {
                throw new IllegalStateException("不应调用");
            }
        };
    }

    @Test
    void extractsSignals_withNormalizationAndDirection() {
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(),
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        List<IndicatorSignal> signals = service.extractSignals(List.of(
                indicator("白细胞", IndicatorStatus.HIGH),
                indicator("空腹血糖", IndicatorStatus.LOW),
                indicator("HGB", IndicatorStatus.NORMAL),
                indicator("PLT", IndicatorStatus.UNKNOWN)));

        assertEquals(List.of(
                new IndicatorSignal("WBC", "HIGH"),
                new IndicatorSignal("GLU", "LOW")), signals, "归一+方向；NORMAL/UNKNOWN 不参与推理");
    }

    @Test
    void extractsObserved_includesNormalAndExcludesUnknown() {
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(),
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        List<String> observed = service.extractObserved(List.of(
                indicator("LDL", IndicatorStatus.HIGH),
                indicator("HDL", IndicatorStatus.NORMAL),
                indicator("PLT", IndicatorStatus.UNKNOWN)));

        assertEquals(List.of("LDL", "HDL"), observed,
                "已检测=异常+正常（用于判定缺失上下文）；UNKNOWN 未识别不算已检测");
    }

    @Test
    void graphAvailable_formatsInferenceWithDepartmentAndEvidence() {
        GraphQueryPort port = mock(GraphQueryPort.class);
        when(port.available()).thenReturn(true);
        when(port.inferWithEvidence(any(), any(), anyInt())).thenReturn(List.of(
                new EvidencePack("动脉粥样硬化", "心内科", 2,
                        List.of(new Evidence("LDL", "HIGH", GUIDE), new Evidence("TC", "HIGH", GUIDE)),
                        List.of(), List.of(), List.of("头晕"))));
        GraphKnowledgeService service = new GraphKnowledgeService(port,
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(
                indicator("LDL", IndicatorStatus.HIGH), indicator("TC", IndicatorStatus.HIGH)));

        assertTrue(context.contains("图谱关联证据"));
        assertTrue(context.contains("动脉粥样硬化"));
        assertTrue(context.contains("心内科"));
        assertTrue(context.contains("LDL↑"), "支持证据带方向箭头");
        assertTrue(context.contains("中国血脂管理指南(2023)"), "P0-2：证据必须带出处");
        assertTrue(context.contains("A@2026.09"), "P0-2：证据必须带证据等级与知识版本");
    }

    @Test
    void evidencePack_rendersCounterAndMissingEvidence() {
        GraphQueryPort port = mock(GraphQueryPort.class);
        when(port.available()).thenReturn(true);
        when(port.inferWithEvidence(any(), any(), anyInt())).thenReturn(List.of(
                new EvidencePack("脂肪肝", "消化内科", 2,
                        List.of(new Evidence("ALT", "HIGH", DEMO)),
                        List.of(new Evidence("TBIL", "HIGH", new Provenance("内科学(第9版)", "B", "2026.09"))),
                        List.of(new MissingContext("GGT", "GGT 可鉴别酒精性肝损伤")),
                        List.of("乏力"))));
        GraphKnowledgeService service = new GraphKnowledgeService(port,
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(indicator("ALT", IndicatorStatus.HIGH)));

        assertTrue(context.contains("反向："), "P0-3：必须输出反向证据，而不是只有支持方向");
        assertTrue(context.contains("TBIL↑"));
        assertTrue(context.contains("待补充："), "P0-3：必须输出缺失上下文");
        assertTrue(context.contains("GGT"));
        assertTrue(context.contains("待医学审核"), "C 级占位知识必须显式标注，避免被当作结论引用");
    }

    @Test
    void missingContext_omittedWhenIndicatorAlreadyObserved() {
        GraphQueryPort port = mock(GraphQueryPort.class);
        when(port.available()).thenReturn(true);
        when(port.inferWithEvidence(any(), any(), anyInt())).thenReturn(List.of());
        GraphKnowledgeService service = new GraphKnowledgeService(port,
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        // 正常项也要算已检测：缺失指的是「没做这项检查」而非「这项异常」
        service.buildAbnormalContext(List.of(
                indicator("ALT", IndicatorStatus.HIGH), indicator("GGT", IndicatorStatus.NORMAL)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(port).inferWithEvidence(any(), captor.capture(), anyInt());
        assertTrue(captor.getValue().contains("GGT"), "已检测的正常指标应进入 observed，不再提示补做");
    }

    /** P1-1：图找证据、规则定序——被规则排除的疾病即使图上命中更多，也必须排到后面。 */
    @Test
    void ruleVerdict_reordersPacksAheadOfRawHitCount() {
        GraphQueryPort port = mock(GraphQueryPort.class);
        when(port.available()).thenReturn(true);
        // 胆汁淤积 hits=3（图上最多），脂肪肝 hits=2；但 ALT↑+TBIL↑ 命中脂肪肝规则的排除项
        when(port.inferWithEvidence(any(), any(), anyInt())).thenReturn(List.of(
                new EvidencePack("胆汁淤积", "消化内科", 3,
                        List.of(new Evidence("TBIL", "HIGH", GUIDE)), List.of(), List.of(), List.of()),
                new EvidencePack("脂肪肝", "消化内科", 2,
                        List.of(new Evidence("ALT", "HIGH", GUIDE)), List.of(), List.of(), List.of()),
                new EvidencePack("动脉粥样硬化", "心内科", 1,
                        List.of(new Evidence("LDL", "HIGH", GUIDE)), List.of(), List.of(), List.of())));
        GraphKnowledgeService service = new GraphKnowledgeService(port,
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(
                indicator("ALT", IndicatorStatus.HIGH),
                indicator("AST", IndicatorStatus.HIGH),
                indicator("TBIL", IndicatorStatus.HIGH),
                indicator("LDL", IndicatorStatus.HIGH),
                indicator("ALP", IndicatorStatus.HIGH)));

        int fatty = context.indexOf("脂肪肝");
        int cholestasis = context.indexOf("胆汁淤积");
        assertTrue(cholestasis > 0 && fatty > 0, "两个方向都应输出");
        assertTrue(cholestasis < fatty,
                "脂肪肝被规则排除（TBIL↑），须排在胆汁淤积之后——纯 hits 投票会排反，实测:\n" + context);
        assertTrue(context.contains("已排除"), "排除态必须显式渲染，而不是悄悄降权");
    }

    /** P1-1：规则引擎纯 Java，图与 ES 全不可用仍要给出判定——这是「规则下沉」的意义。 */
    @Test
    void ruleVerdict_survivesTotalGraphAndEsOutage() {
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(),
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(
                indicator("HGB", IndicatorStatus.LOW), indicator("FERR", IndicatorStatus.LOW)));

        assertTrue(context.contains("组合规则判定"), "图与 ES 全不可用，规则仍须给出结论");
        assertTrue(context.contains("缺铁性贫血"));
        assertTrue(context.contains("iron-deficiency-anemia@1.0.0"), "判定带 ruleId@版本，可复现可回滚");
        assertTrue(context.contains("成立"), "条件满足且上下文（FERR）已检测 → 成立");
    }

    /** 上下文未检测 → INSUFFICIENT，而不是当作成立。教程：拒答是主流程。 */
    @Test
    void ruleVerdict_insufficientWhenContextNotTested() {
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(),
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(
                indicator("HGB", IndicatorStatus.LOW), indicator("HCT", IndicatorStatus.LOW)));

        assertTrue(context.contains("证据不足"), "未测铁蛋白 → 信息缺口，不能当结论");
        assertTrue(context.contains("FERR"), "必须点明还缺哪一项才能确认");
    }

    @Test
    void graphUnavailable_fallsBackToRelationTable() {
        @SuppressWarnings("unchecked")
        RelationLookupService lookup = mock(RelationLookupService.class);
        when(lookup.lookup(any())).thenReturn(List.of(
                new RelationLookupService.RelationEdge("LDL", "HIGH", "动脉粥样硬化",
                        "中国血脂管理指南(2023)", "A")));
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(), lookup,
                new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        String context = service.buildAbnormalContext(List.of(indicator("LDL", IndicatorStatus.HIGH)));

        assertTrue(context.contains("关系表"));
        assertTrue(context.contains("动脉粥样硬化"));
        assertTrue(context.contains("证据等级 A"), "降级路径同样带出处与等级");
    }

    @Test
    void allDegraded_returnsEmpty() {
        GraphKnowledgeService service = new GraphKnowledgeService(unavailablePort(),
                relationLookupThrowing(), new IndicatorNormalizer(MAPPER), ruleEngine(), true);

        assertEquals("", service.buildAbnormalContext(List.of(indicator("LDL", IndicatorStatus.HIGH))),
                "LDL 单项异常不满足任何组合规则的门槛，无证据亦无判定时输出空");
        assertEquals("", service.buildAbnormalContext(List.of(indicator("HGB", IndicatorStatus.NORMAL))));
    }

    @Test
    void graphDisabled_neverProbesGraph() {
        GraphQueryPort port = mock(GraphQueryPort.class);
        GraphKnowledgeService service = new GraphKnowledgeService(port, relationLookupThrowing(),
                new IndicatorNormalizer(MAPPER), ruleEngine(), false);

        assertEquals("", service.buildAbnormalContext(List.of(indicator("LDL", IndicatorStatus.HIGH))));
        verify(port, never()).available();
    }

    @SuppressWarnings("unchecked")
    private static RelationLookupService relationLookupThrowing() {
        try {
            ElasticsearchClient es = mock(ElasticsearchClient.class);
            when(es.search(any(Function.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("ES unavailable"));
            return new RelationLookupService(es);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
