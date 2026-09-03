package com.example.mall.agentservice.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 结构化事实卡（长期记忆核心载体）：替代「文本追加+截断」旧方案——
 * 截断保最新会静默丢失最旧的过敏史等关键事实，医疗记忆必须按事实合并。
 * 合并语义（merge）：
 * ① ABNORMAL_INDICATOR 按「归一指标名」合并：保留 firstSeen（趋势起点），滚动更新最新值/方向/日期；
 * ② ALLERGY / CHRONIC_FLAG / MEDICATION 首次记录为准、只去重不覆盖（来源暂为预留，由问诊链路写入）；
 * ③ CRITICAL_HISTORY 按 ruleId+名称去重追加；
 * ④ 容量上限只裁剪 ABNORMAL_INDICATOR（保最近 N 条），红线类事实不受裁剪影响。
 * profile_summary 由 deriveSummary() 派生，不再独立追加。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HealthFactCard {

    public enum FactType { ABNORMAL_INDICATOR, CHRONIC_FLAG, ALLERGY, MEDICATION, CRITICAL_HISTORY }

    /** 一条事实。name=指标名/标签名；direction 仅 ABNORMAL_INDICATOR 使用（HIGH/LOW）。 */
    public record FactEntry(FactType type, String name, String value, String direction,
                            LocalDate firstSeen, LocalDate lastSeen, LocalDate recordedAt) {

        LocalDate effectiveDate() {
            if (lastSeen != null) {
                return lastSeen;
            }
            return recordedAt != null ? recordedAt() : firstSeen;
        }
    }

    private static final int MAX_ABNORMAL_ENTRIES = 30;

    /**
     * 事实列表。注意：本类 getter 命名为 {@code facts()}（而非 getFacts()，与流式风格一致），
     * 不符合 JavaBean 命名约定，Jackson 不会自动识别——必须显式 {@code @JsonProperty} 才能序列化/反序列化，
     * 否则 factCard 字段会被写成 {@code {}} 而静默丢失全部事实（此缺陷此前未被发现，因为读取方读的是 profileSummary）。
     */
    @JsonProperty("facts")
    private List<FactEntry> facts = new ArrayList<>();

    public List<FactEntry> facts() {
        return facts;
    }

    public HealthFactCard setFacts(List<FactEntry> facts) {
        this.facts = facts == null ? new ArrayList<>() : new ArrayList<>(facts);
        return this;
    }

    /** 合并一次报告产出的事实（同指标更新不重复；红线类只增不减）。 */
    public HealthFactCard merge(List<FactEntry> incoming, LocalDate today) {
        Map<String, FactEntry> byKey = new LinkedHashMap<>();
        for (FactEntry existing : facts) {
            byKey.putIfAbsent(key(existing), existing);
        }
        for (FactEntry entry : incoming) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                continue;
            }
            byKey.merge(key(entry), entry, (oldE, newE) -> {
                if (newE.type() == FactType.ABNORMAL_INDICATOR) {
                    // 同指标再次异常：保留首次发现（趋势起点），滚动更新最新值与日期
                    LocalDate first = oldE.firstSeen() != null ? oldE.firstSeen() : newE.firstSeen();
                    LocalDate last = latest(newE.effectiveDate(), oldE.effectiveDate());
                    return new FactEntry(FactType.ABNORMAL_INDICATOR, oldE.name(),
                            newE.value(), newE.direction(), first, last, today);
                }
                return oldE; // 过敏/慢病/用药：首次记录为准
            });
        }
        List<FactEntry> all = new ArrayList<>(byKey.values());
        List<FactEntry> abnormal = all.stream()
                .filter(f -> f.type() == FactType.ABNORMAL_INDICATOR)
                .sorted(Comparator.comparing(FactEntry::effectiveDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_ABNORMAL_ENTRIES)
                .toList();
        List<FactEntry> protectedFacts = all.stream()
                .filter(f -> f.type() != FactType.ABNORMAL_INDICATOR)
                .toList();
        List<FactEntry> result = new ArrayList<>(protectedFacts);
        result.addAll(abnormal);
        this.facts = result;
        return this;
    }

    /** 由事实卡派生人类可读摘要（替代旧追加式 summary，幂等可重建）。 */
    public String deriveSummary() {
        if (facts.isEmpty()) {
            return "暂无健康事实记录";
        }
        StringBuilder sb = new StringBuilder();
        List<FactEntry> critical = facts.stream()
                .filter(f -> f.type() == FactType.CRITICAL_HISTORY).toList();
        List<FactEntry> flags = facts.stream()
                .filter(f -> f.type() == FactType.CHRONIC_FLAG
                        || f.type() == FactType.ALLERGY || f.type() == FactType.MEDICATION).toList();
        List<FactEntry> abnormal = facts.stream()
                .filter(f -> f.type() == FactType.ABNORMAL_INDICATOR).toList();
        if (!critical.isEmpty()) {
            sb.append("危急值史 ").append(critical.size()).append(" 次（")
                    .append(joinNames(critical)).append("）。");
        }
        if (!flags.isEmpty()) {
            sb.append("长期标签：").append(joinNames(flags)).append("。");
        }
        if (!abnormal.isEmpty()) {
            sb.append("关注指标 ").append(abnormal.size()).append(" 项：")
                    .append(joinNames(abnormal)).append("。");
        }
        return sb.toString();
    }

    private static String joinNames(List<FactEntry> entries) {
        return entries.stream()
                .map(f -> f.name()
                        + (f.type() == FactType.ABNORMAL_INDICATOR && f.direction() != null
                        ? ("HIGH".equals(f.direction()) ? "偏高" : "偏低") : "")
                        + (f.effectiveDate() != null ? "（" + f.effectiveDate() + "）" : ""))
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static String key(FactEntry e) {
        return e.type() + "|" + e.name().trim();
    }
}
