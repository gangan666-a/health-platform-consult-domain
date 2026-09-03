package com.example.mall.agentservice.graph;

import com.example.mall.agentservice.graph.CombinationRule.AnyOf;
import com.example.mall.agentservice.graph.CombinationRule.Criterion;
import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.RuleVerdict.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 组合规则引擎（P1-1）：确定性判定「多指标组合模式」是否成立，替代图上 {@code count(hits)} 投票。
 *
 * <p><b>职责边界</b>（教程：GraphRAG 是中间层，不是总控）：
 * 图负责「找与候选疾病相关的证据」，规则引擎负责「判定成立 / 证据不足 / 已排除」。
 * 判定完全由 Java 完成，不依赖 LLM，也不依赖 Neo4j——即使图与 ES 全不可用，
 * 组合规则仍能提供确定性结论（纯 classpath JSON + 内存计算）。
 *
 * <p><b>与 {@code CriticalValueEvaluator} 的差异</b>：
 * 危急值是<b>安全兜底</b>，词典缺失必须启动失败；组合规则是<b>质量增强</b>，
 * 规则文件异常时按 T-2 降级为「无规则判定」，仅告警、不阻断启动。
 *
 * <p><b>版本化</b>：每条规则带 semver，文件带 {@code bundleVersion}，
 * 判定结果输出 {@code ruleId@version}，使历史回答可复现、规则可回滚。
 */
@Component
public class CombinationRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(CombinationRuleEngine.class);

    private static final String RULES_PATH = "graph/combination-rules.json";

    /** 规则包：整体版本 + 规则列表。包可见（非 private）以便 Jackson 反序列化。 */
    record RulesFile(String bundleVersion, List<CombinationRule> rules) {
    }

    private final List<CombinationRule> rules = new ArrayList<>();
    private String bundleVersion = "unknown";

    public CombinationRuleEngine(ObjectMapper objectMapper) {
        load(objectMapper);
    }

    private void load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RULES_PATH).getInputStream()) {
            RulesFile file = objectMapper.readValue(in, RulesFile.class);
            if (file.bundleVersion() != null && !file.bundleVersion().isBlank()) {
                bundleVersion = file.bundleVersion().trim();
            }
            if (file.rules() != null) {
                for (CombinationRule rule : file.rules()) {
                    if (accept(rule)) {
                        rules.add(rule);
                    }
                }
            }
            log.info("组合规则加载完成：{} 条（bundle {}），路径 {}", rules.size(), bundleVersion, RULES_PATH);
        } catch (IOException e) {
            // T-2：组合规则是质量增强而非安全兜底，缺失时降级为无规则判定
            log.warn("组合规则文件缺失或不可读（{}），组合判定降级为「无规则」：{}", RULES_PATH, e.getMessage());
        }
    }

    /** 加载期校验：拒绝无 id / 无条件 / K>N 的规则；缺出处仅告警（教程：无来源的声明应被拒，但先不阻断上线）。 */
    private boolean accept(CombinationRule rule) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            log.warn("组合规则缺少 id，已剔除");
            return false;
        }
        if (!rule.hasCondition()) {
            log.warn("组合规则 {} 未声明 required/anyOf 条件，已剔除", rule.id());
            return false;
        }
        AnyOf anyOf = rule.anyOf();
        if (anyOf != null && anyOf.of() != null && anyOf.k() > anyOf.of().size()) {
            log.warn("组合规则 {} 的 K({}) > N({})，永不可满足，已剔除",
                    rule.id(), anyOf.k(), anyOf.of().size());
            return false;
        }
        if (rule.source() == null || rule.source().isBlank()) {
            log.warn("组合规则 {} 未声明出处，建议补充后重新发布", rule.id());
        }
        return true;
    }

    /**
     * 按规则判定各疾病方向。
     *
     * @param signals  异常信号（归一名 + HIGH/LOW）
     * @param observed 本次报告全部已检测指标（含正常项），用于判定上下文是否齐全
     * @return 按 {@link RuleVerdict#rank()} 升序（成立 → 证据不足 → 已排除）；
     *         同一疾病命中多条规则时保留最优判定
     */
    public List<RuleVerdict> evaluate(List<IndicatorSignal> signals, List<String> observed) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        Set<String> signalKeys = new LinkedHashSet<>();
        for (IndicatorSignal s : signals) {
            signalKeys.add(key(s.indicator(), s.direction()));
        }
        Set<String> observedSet = observed == null ? Set.of() : Set.copyOf(observed);

        Map<String, RuleVerdict> bestByDisease = new LinkedHashMap<>();
        for (CombinationRule rule : rules) {
            RuleVerdict verdict = evaluateRule(rule, signalKeys, observedSet);
            if (verdict == null) {
                continue;
            }
            bestByDisease.merge(rule.disease(), verdict,
                    (a, b) -> a.rank() <= b.rank() ? a : b);
        }
        List<RuleVerdict> out = new ArrayList<>(bestByDisease.values());
        out.sort(Comparator.comparingInt(RuleVerdict::rank)
                .thenComparing(RuleVerdict::disease));
        return out;
    }

    /** 规则未命中条件时返回 null（不输出判定）。 */
    private RuleVerdict evaluateRule(CombinationRule rule, Set<String> signalKeys, Set<String> observedSet) {
        Set<String> matched = new LinkedHashSet<>();

        if (rule.required() != null) {
            for (Criterion c : rule.required()) {
                if (!signalKeys.contains(key(c.indicator(), c.direction()))) {
                    return null; // 必须项未满足 → 规则不成立
                }
                matched.add(render(c));
            }
        }
        AnyOf anyOf = rule.anyOf();
        if (anyOf != null && anyOf.of() != null && !anyOf.of().isEmpty()) {
            List<String> hit = new ArrayList<>();
            for (Criterion c : anyOf.of()) {
                if (signalKeys.contains(key(c.indicator(), c.direction()))) {
                    hit.add(render(c));
                }
            }
            if (hit.size() < anyOf.k()) {
                return null; // K-of-N 未达门槛 → 规则不成立
            }
            matched.addAll(hit);
        }

        List<String> excludedHits = new ArrayList<>();
        if (rule.excluded() != null) {
            for (Criterion c : rule.excluded()) {
                if (signalKeys.contains(key(c.indicator(), c.direction()))) {
                    excludedHits.add(render(c));
                }
            }
        }
        List<String> missingContext = new ArrayList<>();
        if (rule.context() != null) {
            for (String indicator : rule.context()) {
                if (indicator != null && !indicator.isBlank() && !observedSet.contains(indicator.trim())) {
                    missingContext.add(indicator.trim());
                }
            }
        }

        Outcome outcome;
        if (!excludedHits.isEmpty()) {
            outcome = Outcome.EXCLUDED;
        } else if (!missingContext.isEmpty()) {
            outcome = Outcome.INSUFFICIENT;
        } else {
            outcome = Outcome.SATISFIED;
        }
        return new RuleVerdict(rule.id(), rule.safeVersion(), rule.disease(), rule.department(),
                outcome, List.copyOf(matched), excludedHits, missingContext,
                rule.rationale(), rule.source());
    }

    /** 同一疾病的最优判定（无判定返回 null）。 */
    public static RuleVerdict bestFor(List<RuleVerdict> verdicts, String disease) {
        RuleVerdict best = null;
        for (RuleVerdict v : verdicts) {
            if (v.disease().equals(disease) && (best == null || v.rank() < best.rank())) {
                best = v;
            }
        }
        return best;
    }

    /** 规则包版本：随判定一并落盘，用于复现历史回答。 */
    public String bundleVersion() {
        return bundleVersion;
    }

    private static String key(String indicator, String direction) {
        return indicator + "|" + (direction == null ? "" : direction.toUpperCase(Locale.ROOT));
    }

    private static String render(Criterion c) {
        return c.indicator() + ("HIGH".equalsIgnoreCase(c.direction()) ? "↑" : "↓");
    }
}
