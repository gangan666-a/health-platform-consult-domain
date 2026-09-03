package com.example.mall.agentservice.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指标单位归一（跨机构对比的隐形坑）：不同机构对同一指标用不同单位制
 * （血糖/胆固醇 mmol/L vs mg/dL，尿酸/肌酐 μmol/L vs mg/dL 或 μmol/L vs mmol/L），
 * 直接比对会得出错误结论。归一到每个指标的标准单位，换算后保留 2 位小数（保真不虚构精度）。
 * 未知单位/未知指标原样返回（fail-open，不猜）。converted 标记进执行轨迹与指标数（可审计）。
 */
public final class UnitConverter {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d*\\.?\\d+");

    /** 指标 → 标准单位 + 换算规则（fromUnit 归一化匹配 → 乘以 factor 得标准单位值）。 */
    private record Rule(String canonicalUnit, Map<String, Double> factors) {
    }

    private static final Map<String, Rule> RULES = Map.of(
            "GLU", new Rule("mmol/L", Map.of("mg/dl", 1 / 18.02)),
            "TC", new Rule("mmol/L", Map.of("mg/dl", 1 / 38.67)),
            "TG", new Rule("mmol/L", Map.of("mg/dl", 1 / 88.57)),
            "HDL", new Rule("mmol/L", Map.of("mg/dl", 1 / 38.67)),
            "LDL", new Rule("mmol/L", Map.of("mg/dl", 1 / 38.67)),
            "UA", new Rule("umol/L", Map.of("mg/dl", 59.48, "mmol/l", 1000.0)),
            "CRE", new Rule("umol/L", Map.of("mg/dl", 88.4, "mmol/l", 1000.0)),
            "HBA1C", new Rule("%", Map.of()));

    /** 单位串归一化 key（μ/µ → u，去空格，小写）。 */
    private static String unitKey(String unit) {
        if (unit == null) {
            return "";
        }
        return unit.trim().toLowerCase(Locale.ROOT)
                .replace('μ', 'u')
                .replace('µ', 'u')
                .replace(" ", "");
    }

    /**
     * 把指标（归一名）的数值+单位换算到标准单位；参考范围（与值同单位制）同步换算，
     * 避免「值换了、范围没换」的比对错误。无需换算或不可换算时原样返回。
     */
    public static WithRange convertWithRange(String canonicalIndicator, String value, String unit, String refRange) {
        Converted base = convert(canonicalIndicator, value, unit);
        if (!base.converted()) {
            return new WithRange(value, unit, refRange, false);
        }
        Rule rule = RULES.get(canonicalIndicator);
        double factor = rule.factors().get(unitKey(unit));
        String convertedRange = refRange;
        if (refRange != null && !refRange.isBlank()) {
            Matcher m = NUMBER.matcher(refRange);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                double scaled = Double.parseDouble(m.group()) * factor;
                m.appendReplacement(sb, String.format(Locale.ROOT, "%.2f", scaled));
            }
            m.appendTail(sb);
            convertedRange = sb.toString();
        }
        return new WithRange(base.value(), base.unit(), convertedRange, true);
    }

    public record Converted(String value, String unit, boolean converted) {
    }

    public record WithRange(String value, String unit, String refRange, boolean converted) {
    }

    private UnitConverter() {
    }

    /** 单指标换算（不含范围）：内部与外部共用。 */
    static Converted convert(String canonicalIndicator, String value, String unit) {
        Rule rule = RULES.get(canonicalIndicator);
        if (rule == null) {
            return new Converted(value, unit, false);
        }
        String key = unitKey(unit);
        if (key.isEmpty()) {
            return new Converted(value, unit, false);
        }
        Double factor = rule.factors().get(key);
        if (factor == null) {
            return new Converted(value, unit, false); // 已是标准单位或未知单位：不猜
        }
        Matcher m = NUMBER.matcher(value == null ? "" : value);
        if (!m.find()) {
            return new Converted(value, unit, false); // 非数值（定性结果）不换算
        }
        double numeric = Double.parseDouble(m.group());
        double converted = numeric * factor;
        return new Converted(String.format(Locale.ROOT, "%.2f", converted), rule.canonicalUnit(), true);
    }
}
