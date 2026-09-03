package com.example.mall.agentservice.graph;

import java.util.List;

/**
 * 组合规则判定结果（P1-1）：对某个疾病方向，规则引擎给出的确定性判定。
 *
 * <p>三态而非二态是刻意的——医疗解读里「证据不足」和「不成立」必须分开：
 * 二态会逼着系统在信息不全时二选一，而 {@code INSUFFICIENT} 让系统能诚实地说
 * 「目前证据支持这个方向，但还缺 XX 才能确认」，这正是教程要求的「拒答是主流程」。
 *
 * @param outcome       判定结果
 * @param matched       命中并计入成立的判据（已渲染为「指标↑」形式）
 * @param excludedHits  命中的排除项（仅 EXCLUDED 时有值）
 * @param missingContext 缺失的上下文指标（仅 INSUFFICIENT 时有值）
 */
public record RuleVerdict(String ruleId, String version, String disease, String department,
                          Outcome outcome, List<String> matched, List<String> excludedHits,
                          List<String> missingContext, String rationale, String source) {

    public enum Outcome {
        /** 条件满足、无排除项、上下文齐全。 */
        SATISFIED,
        /** 条件满足、无排除项，但上下文指标未检测——信息缺口，不是结论。 */
        INSUFFICIENT,
        /** 命中排除项，该方向被削弱或排除。 */
        EXCLUDED
    }

    /** 排序权重（升序）：成立 → 证据不足 → 排除。数值越小越靠前。 */
    public int rank() {
        return switch (outcome) {
            case SATISFIED -> 0;
            case INSUFFICIENT -> 1;
            case EXCLUDED -> 2;
        };
    }

    /** 无规则覆盖时的排序权重：介于「证据不足」与「排除」之间（无判定优于被排除）。 */
    public static final int RANK_NONE = 1;

    public String ref() {
        return ruleId + "@" + (version == null || version.isBlank()
                ? CombinationRule.VERSION_FALLBACK : version.trim());
    }

    /** 渲染为解读用的一行说明。 */
    public String render() {
        String head = switch (outcome) {
            case SATISFIED -> "成立";
            case INSUFFICIENT -> "证据不足";
            case EXCLUDED -> "已排除";
        };
        StringBuilder sb = new StringBuilder(head).append("（").append(ref());
        if (source != null && !source.isBlank()) {
            sb.append("，").append(source);
        }
        sb.append('）');
        if (outcome == Outcome.EXCLUDED && excludedHits != null && !excludedHits.isEmpty()) {
            sb.append("：").append(String.join("、", excludedHits)).append(" 不支持该方向");
        } else if (outcome == Outcome.INSUFFICIENT && missingContext != null && !missingContext.isEmpty()) {
            sb.append("：待补 ").append(String.join("、", missingContext));
        }
        if (rationale != null && !rationale.isBlank()) {
            sb.append("；").append(rationale);
        }
        return sb.toString();
    }
}
