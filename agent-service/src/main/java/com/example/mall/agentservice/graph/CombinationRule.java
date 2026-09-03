package com.example.mall.agentservice.graph;

import java.util.List;

/**
 * 组合规则（P1-1）：把「多指标组合模式」表达为一份<b>逐项核对的检查单</b>，
 * 而不是「指标 A 连指标 B 再连疾病」的共现边。
 *
 * <p>教程 D08 的要点：多指标组合是「必须项满足 + 排除项不命中 + 上下文齐全」的判定，
 * 图上 {@code count(hits)} 投票无法表达「排除」与「上下文是否齐全」——
 * 这正是 v2 的 {@code ALT↑+AST↑ → 脂肪肝 hits=2} 会把肝损伤方向压下去的根因。
 *
 * <p>判定语义（{@link CombinationRuleEngine}）：
 * <ol>
 *   <li>{@code required}：列表内<b>全部</b>成立（AND）；</li>
 *   <li>{@code anyOf}：N 项中<b>至少 K 项</b>成立（K-of-N，{@code anyOf.k <= anyOf.of.size}）；</li>
 *   <li>两者可并存，需同时满足；均为空视为无效规则，加载时剔除；</li>
 *   <li>{@code excluded}：命中任意一项即判定 {@code EXCLUDED}（优先级高于上下文）；</li>
 *   <li>{@code context}：必须<b>已检测</b>（不要求异常）的指标，缺项则判定 {@code INSUFFICIENT}。</li>
 * </ol>
 */
public record CombinationRule(String id, String version, String disease, String department,
                              List<Criterion> required, AnyOf anyOf, List<Criterion> excluded,
                              List<String> context, String rationale, String source) {

    /** 单项判据：指标归一名 + 异常方向（HIGH / LOW）。 */
    public record Criterion(String indicator, String direction) {
    }

    /** K-of-N 判据：{@code of} 中至少 {@code k} 项成立。 */
    public record AnyOf(int k, List<Criterion> of) {
    }

    /** 规则未声明版本时的兜底（渲染为 @0.0.0 以便一眼看出缺元数据）。 */
    public static final String VERSION_FALLBACK = "0.0.0";

    /** 净版本号（去空白，缺省回退）。 */
    public String safeVersion() {
        return version == null || version.isBlank() ? VERSION_FALLBACK : version.trim();
    }

    /** 规则标识：{@code ruleId@semver}，用于解读输出与事后复现「依据哪版规则」。 */
    public String ref() {
        return id + "@" + safeVersion();
    }

    /** 是否具备可执行条件（required 或 anyOf 至少其一有效）。 */
    public boolean hasCondition() {
        return (required != null && !required.isEmpty())
                || (anyOf() != null && anyOf().of() != null && !anyOf().of().isEmpty() && anyOf().k() > 0);
    }
}
