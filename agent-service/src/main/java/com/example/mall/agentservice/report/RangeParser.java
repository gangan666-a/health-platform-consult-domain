package com.example.mall.agentservice.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参考范围判定工具：解析数值原文 + 参考范围字符串 → HIGH/NORMAL/LOW/UNKNOWN。
 * 规则（E-7/B-4）：数值或范围解析不出 → UNKNOWN，绝不猜默认。
 * isRangeUsable 供参考范围优先级决策使用（B-8：报告自带范围优先，ES 仅兜底）。
 */
public final class RangeParser {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d*\\.?\\d+");

    private RangeParser() {
    }

    /** 边界语义：lowerOnly（>x 型）/ upperOnly（<x 型）/ 双边（a-b 型）。 */
    private record Bounds(Double lower, Double upper) {

        boolean lowerOnly() {
            return lower != null && upper == null;
        }

        boolean upperOnly() {
            return upper != null && lower == null;
        }
    }

    public static IndicatorStatus computeStatus(String valueStr, String refRange) {
        Double value = parseDouble(valueStr);
        Bounds bounds = parseBounds(refRange);
        if (value == null || bounds == null) {
            return IndicatorStatus.UNKNOWN;
        }
        if (bounds.lowerOnly()) {
            return value < bounds.lower() ? IndicatorStatus.LOW : IndicatorStatus.NORMAL;
        }
        if (bounds.upperOnly()) {
            return value > bounds.upper() ? IndicatorStatus.HIGH : IndicatorStatus.NORMAL;
        }
        if (value < bounds.lower()) {
            return IndicatorStatus.LOW;
        }
        if (value > bounds.upper()) {
            return IndicatorStatus.HIGH;
        }
        return IndicatorStatus.NORMAL;
    }

    /** 参考范围是否携带可解析的上/下界语义（B-8 决策依据）。 */
    public static boolean isRangeUsable(String refRange) {
        return parseBounds(refRange) != null;
    }

    private static Bounds parseBounds(String refRange) {
        String range = normalize(refRange);
        if (range.isBlank()) {
            return null;
        }
        if (range.startsWith(">=")) {
            Double lower = tailDouble(range, 2);
            return lower == null ? null : new Bounds(lower, null);
        }
        if (range.startsWith(">") || range.startsWith("≥")) {
            Double lower = tailDouble(range, 1);
            return lower == null ? null : new Bounds(lower, null);
        }
        if (range.startsWith("<=")) {
            Double upper = tailDouble(range, 2);
            return upper == null ? null : new Bounds(null, upper);
        }
        if (range.startsWith("<") || range.startsWith("≤")) {
            Double upper = tailDouble(range, 1);
            return upper == null ? null : new Bounds(null, upper);
        }
        String[] bounds = range.split("-");
        if (bounds.length == 2) {
            Double lower = parseDouble(bounds[0]);
            Double upper = parseDouble(bounds[1]);
            if (lower == null || upper == null) {
                return null;
            }
            return new Bounds(lower, upper);
        }
        return null;
    }

    /** 提取字符串中的首个数值（如 "6.5"、"138"、">5"、"5.0×10^9/L" → 5.0） */
    private static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从 index 处取子串再解析数值 */
    private static Double tailDouble(String range, int from) {
        if (range.length() <= from) {
            return null;
        }
        return parseDouble(range.substring(from));
    }

    /** 归一化参考范围：去括号/空格/单位，统一全角符号；保留数字、'.'、'-'、'>'、'<' */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim()
                .replaceAll("[（(]", "")
                .replaceAll("[）)]", "")
                .replace('，', ',')
                .replace('～', '-')
                .replace('~', '-')
                .replaceAll("[^0-9.\\-<>≥≤, ]", "")
                .trim();
    }
}
