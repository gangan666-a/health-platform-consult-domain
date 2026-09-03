package com.example.mall.agentservice.graph;

import com.example.mall.agentservice.graph.GraphQueryPort.Evidence;
import com.example.mall.agentservice.graph.GraphQueryPort.EvidencePack;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.GraphQueryPort.MissingContext;
import com.example.mall.agentservice.graph.RuleVerdict.Outcome;
import com.example.mall.agentservice.report.IndicatorNormalizer;
import com.example.mall.agentservice.report.IndicatorStatus;
import com.example.mall.agentservice.report.ReportIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识关联服务（v1→v2→v3 演进的门面）：由异常指标生成「可疑疾病方向」解读素材。
 *
 * <p><b>职责切分（P1-1，教程：GraphRAG 是中间层，不是总控）</b>：
 * <pre>
 *   图（Neo4j / ES）  → 找证据：哪些疾病与本次异常相关，支持/反向/待补充各有哪些
 *   规则引擎（Java）  → 做判定：必须项是否满足、是否命中排除项、上下文是否齐全
 * </pre>
 * 排序权不再交给图上 {@code count(hits)} 投票——投票无法表达「排除」与「信息缺口」，
 * 会把 {@code ALT↑+AST↑} 单纯按连边数排到「脂肪肝」而压掉「肝损伤」方向。
 * 现在先由规则给出 {@code SATISFIED / INSUFFICIENT / EXCLUDED} 三态，再按三态分档、
 * 档内才用 hits 做次要排序。规则引擎纯 Java + classpath JSON，图与 ES 全不可用仍可判定。
 *
 * <p><b>P0-3 证据包</b>：逐疾病给出支持 / 反向 / 待补充三路证据，让 LLM 有条件输出
 * 「更像 X，但 Y 不支持，还需 Z 才能区分」，而不是把关联边当诊断规则。
 */
@Service
public class GraphKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeService.class);

    private static final int INFERENCE_LIMIT = 5;

    /** 证据包渲染上限：控制注入 prompt 的体积，避免长报告时上下文膨胀。 */
    private static final int MAX_SUPPORTING = 6;
    private static final int MAX_CONTRA = 3;
    private static final int MAX_MISSING = 3;
    private static final int MAX_SYMPTOMS = 5;
    /** 未被证据包覆盖的独立规则判定渲染上限。 */
    private static final int MAX_STANDALONE_VERDICTS = 4;

    private final GraphQueryPort graphPort;
    private final RelationLookupService relationLookup;
    private final IndicatorNormalizer normalizer;
    private final CombinationRuleEngine ruleEngine;
    private final boolean graphEnabled;

    public GraphKnowledgeService(GraphQueryPort graphPort, RelationLookupService relationLookup,
                                 IndicatorNormalizer normalizer, CombinationRuleEngine ruleEngine,
                                 @Value("${agent.graph.enabled:true}") boolean graphEnabled) {
        this.graphPort = graphPort;
        this.relationLookup = relationLookup;
        this.normalizer = normalizer;
        this.ruleEngine = ruleEngine;
        this.graphEnabled = graphEnabled;
    }

    /**
     * 由报告指标构建关联上下文（注入解读 prompt；无异常返回空串）。
     *
     * <p>输出两段：<b>关联证据</b>（图三路证据包；图不可用降级 ES 关系查表）+ <b>规则判定</b>（始终输出）。
     * 两段互补——证据回答「有什么依据」，规则回答「依据够不够、方向上成不成立」。
     */
    public String buildAbnormalContext(List<ReportIndicator> indicators) {
        List<IndicatorSignal> signals = extractSignals(indicators);
        if (signals.isEmpty()) {
            return "";
        }
        List<String> observed = extractObserved(indicators);
        List<RuleVerdict> verdicts = ruleEngine.evaluate(signals, observed);

        StringBuilder sb = new StringBuilder();
        String evidence = buildEvidenceSection(signals, observed, verdicts);
        if (!evidence.isEmpty()) {
            sb.append(evidence);
        }
        String rules = formatStandaloneVerdicts(verdicts, coveredDiseases(evidence));
        if (!rules.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rules);
        }
        return sb.toString();
    }

    /** 关联证据段：图三路证据包优先，图不可用降级 ES 关系查表。 */
    private String buildEvidenceSection(List<IndicatorSignal> signals, List<String> observed,
                                        List<RuleVerdict> verdicts) {
        if (graphEnabled && graphPort.available()) {
            try {
                List<EvidencePack> packs = graphPort.inferWithEvidence(signals, observed, INFERENCE_LIMIT);
                if (!packs.isEmpty()) {
                    return formatEvidencePacks(rankByRule(packs, verdicts), verdicts);
                }
            } catch (Exception e) {
                log.warn("图推理失败，降级关系查表: {}", e.getMessage());
            }
        }
        List<RelationLookupService.RelationEdge> edges = relationLookup.lookup(signals);
        return edges.isEmpty() ? "" : formatRelations(edges);
    }

    /**
     * 按规则判定给证据包分档排序（P1-1）：
     * 成立 → 证据不足 → 无规则覆盖 → 已排除；同档内按图上命中数降序作为次要排序键。
     * 这是「图找证据、规则定序」的落点，替代原先纯 {@code hits} 投票。
     */
    List<EvidencePack> rankByRule(List<EvidencePack> packs, List<RuleVerdict> verdicts) {
        Function<String, Integer> rankOf = disease -> {
            RuleVerdict v = CombinationRuleEngine.bestFor(verdicts, disease);
            return v == null ? RuleVerdict.RANK_NONE : v.rank();
        };
        List<EvidencePack> out = new ArrayList<>(packs);
        out.sort(Comparator
                .comparingInt((EvidencePack p) -> rankOf.apply(p.disease()))
                .thenComparing(Comparator.comparingLong(EvidencePack::hits).reversed()));
        return out;
    }

    /** 提取异常信号（归一名 + HIGH/LOW；UNKNOWN 不参与推理）。 */
    List<IndicatorSignal> extractSignals(List<ReportIndicator> indicators) {
        List<IndicatorSignal> signals = new ArrayList<>();
        for (ReportIndicator in : indicators) {
            if (in.status() == IndicatorStatus.HIGH || in.status() == IndicatorStatus.LOW) {
                signals.add(new IndicatorSignal(normalizer.normalize(in.name()),
                        in.status() == IndicatorStatus.HIGH ? "HIGH" : "LOW"));
            }
        }
        return signals;
    }

    /**
     * 提取本次报告<b>全部已检测</b>指标的归一名（含正常项）。
     * 用途：判定「缺失上下文」时，只有未检测的指标才算信息缺口——
     * 已检测（哪怕正常）本身就是证据，不应再提示「还需补做」。
     */
    List<String> extractObserved(List<ReportIndicator> indicators) {
        Set<String> observed = new LinkedHashSet<>();
        for (ReportIndicator in : indicators) {
            if (in.status() == IndicatorStatus.UNKNOWN) {
                continue; // 未识别项不算已检测
            }
            String name = normalizer.normalize(in.name());
            if (name != null && !name.isBlank()) {
                observed.add(name);
            }
        }
        return List.copyOf(observed);
    }

    /** 渲染证据包：支持 / 反向 / 待补充三路 + 出处；若该疾病有规则判定则附在末行。 */
    private String formatEvidencePacks(List<EvidencePack> packs, List<RuleVerdict> verdicts) {
        StringBuilder sb = new StringBuilder("【图谱关联证据（支持/反向/待补充，仅供参考不作诊断）】\n");
        for (EvidencePack pack : packs) {
            sb.append("- ").append(pack.disease());
            if (pack.department() != null && !pack.department().isBlank()) {
                sb.append("（建议就诊：").append(pack.department()).append('）');
            }
            sb.append('\n');
            sb.append("  支持：").append(joinEvidences(pack.supporting(), MAX_SUPPORTING)).append('\n');
            if (pack.contradicting() != null && !pack.contradicting().isEmpty()) {
                sb.append("  反向：").append(joinEvidences(pack.contradicting(), MAX_CONTRA)).append('\n');
            }
            if (pack.missing() != null && !pack.missing().isEmpty()) {
                sb.append("  待补充：").append(joinMissing(pack.missing(), MAX_MISSING)).append('\n');
            }
            if (pack.symptoms() != null && !pack.symptoms().isEmpty()) {
                sb.append("  典型症状：").append(joinNames(pack.symptoms(), MAX_SYMPTOMS)).append('\n');
            }
            RuleVerdict verdict = CombinationRuleEngine.bestFor(verdicts, pack.disease());
            if (verdict != null) {
                sb.append("  规则判定：").append(verdict.render()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 渲染未被证据包覆盖的规则判定（图/ES 无该疾病证据，但规则仍成立或提示信息缺口）。
     * 只输出「成立」与「证据不足」——「已排除」对一个压根没进候选的疾病没有解读价值。
     */
    private String formatStandaloneVerdicts(List<RuleVerdict> verdicts, Set<String> covered) {
        List<RuleVerdict> standalone = verdicts.stream()
                .filter(v -> v.outcome() != Outcome.EXCLUDED)
                .filter(v -> !covered.contains(v.disease()))
                .limit(MAX_STANDALONE_VERDICTS)
                .toList();
        if (standalone.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【组合规则判定（确定性规则，非诊断结论）】\n");
        for (RuleVerdict v : standalone) {
            sb.append("- ").append(v.disease())
                    .append("：").append(v.render()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 从已渲染的证据段里回抽已覆盖的疾病名（避免重复渲染规则判定）。 */
    private static Set<String> coveredDiseases(String evidenceSection) {
        if (evidenceSection == null || evidenceSection.isEmpty()) {
            return Set.of();
        }
        return evidenceSection.lines()
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2))
                .map(line -> {
                    int idx = line.indexOf('（');
                    return idx > 0 ? line.substring(0, idx) : line;
                })
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private static String formatRelations(List<RelationLookupService.RelationEdge> edges) {
        StringBuilder sb = new StringBuilder("【指标-疾病关联（关系表查询，仅供参考不作诊断）】\n");
        for (RelationLookupService.RelationEdge edge : edges) {
            sb.append("- ").append(edge.indicator())
                    .append("HIGH".equals(edge.direction()) ? "↑" : "↓")
                    .append(" 相关疾病：").append(edge.disease())
                    .append("（来源：").append(edge.source()).append("，证据等级 ").append(edge.evidenceGrade())
                    .append("）\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String joinEvidences(List<Evidence> evidences, int max) {
        if (evidences == null || evidences.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(evidences.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(evidences.get(i).render());
        }
        if (evidences.size() > max) {
            sb.append(" 等 ").append(evidences.size()).append(" 项");
        }
        return sb.toString();
    }

    private static String joinMissing(List<MissingContext> missing, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(missing.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("；");
            }
            MissingContext m = missing.get(i);
            sb.append(m.indicator());
            if (m.reason() != null && !m.reason().isBlank()) {
                sb.append("（").append(m.reason()).append("）");
            }
        }
        if (missing.size() > max) {
            sb.append(" 等 ").append(missing.size()).append(" 项");
        }
        return sb.toString();
    }

    private static String joinNames(List<String> names, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(names.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
