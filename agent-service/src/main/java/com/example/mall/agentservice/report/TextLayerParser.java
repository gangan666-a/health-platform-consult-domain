package com.example.mall.agentservice.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性文本层解析器（LLM 不可用/失败时的兜底通道，评测门禁的基线实现）。
 * 支持的最简行文法：`#检查名` 标题行、`检查/报告日期: yyyy-MM-dd` 日期行、
 * `指标名 数值 [单位] [参考范围]` 指标行（≥3 个空白分隔 token）。
 * 非检验页（总检结论/超声/放射/心电等）整页文本收敛为一条检查所见，绝不虚构指标。
 */
public final class TextLayerParser {

    private static final int MAX_FINDING_CHARS = 1000;

    /** 检验表表头词：命中即跳过该行，防止「检验项目 结果 单位 参考范围」被当成指标。 */
    private static final Set<String> HEADER_WORDS = Set.of(
            "检验项目", "项目名称", "项目", "序号", "编号", "结果", "单位", "参考范围", "参考区间", "提示", "检验结果");

    private TextLayerParser() {
    }

    public static PageExtraction parse(PageContent page, PageType pageType) {
        String text = page.text() == null ? "" : page.text();
        if (text.isBlank()) {
            return empty(page, pageType);
        }

        String examName = null;
        LocalDate reportDate = null;
        List<ReportIndicator> indicators = new ArrayList<>();
        List<String> findingLines = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                examName = trimmed.substring(1).trim();
                continue;
            }
            if (isDateLine(trimmed)) {
                LocalDate date = parseDate(splitAfterColon(trimmed));
                if (date != null) {
                    reportDate = date;
                }
                continue;
            }
            if (pageType.yieldsIndicators() || pageType == PageType.OTHER) {
                ReportIndicator indicator = parseIndicatorLine(trimmed, reportDate);
                if (indicator != null) {
                    indicators.add(indicator);
                    continue;
                }
            }
            findingLines.add(trimmed);
        }

        List<String> findings = new ArrayList<>();
        if (!findingLines.isEmpty() && pageType != PageType.DISCLAIMER) {
            findings.add(condense(findingLines));
        }
        return new PageExtraction(page.pageNo(), pageType,
                examName == null ? pageType.display() : examName,
                reportDate == null ? null : reportDate.toString(),
                indicators, findings, PageExtraction.CHANNEL_DETERMINISTIC);
    }

    private static PageExtraction empty(PageContent page, PageType pageType) {
        return new PageExtraction(page.pageNo(), pageType, pageType.display(), null,
                List.of(), List.of(), PageExtraction.CHANNEL_DETERMINISTIC);
    }

    private static ReportIndicator parseIndicatorLine(String line, LocalDate date) {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        if (HEADER_WORDS.contains(parts[0]) || HEADER_WORDS.contains(parts[1])) {
            return null; // 表头行（检验项目/结果/单位/参考范围…），不是指标
        }
        if (!looksLikeValue(parts[1])) {
            return null; // 值形态不像检验结果（数值/↑↓/定性），按文字行处理
        }
        String name = parts[0];
        String value = parts[1];
        if (parts.length >= 4) {
            return new ReportIndicator(name, value, parts[2], parts[3], IndicatorStatus.UNKNOWN, date);
        }
        return new ReportIndicator(name, value, "", parts[2], IndicatorStatus.UNKNOWN, date);
    }

    /** 检验值形态：数字开头（含 +/−/./↑/↓/</>）或常见定性结果。 */
    private static boolean looksLikeValue(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char c = token.charAt(0);
        if (Character.isDigit(c) || "+-.↑↓<>".indexOf(c) >= 0) {
            return true;
        }
        return token.equals("阴性") || token.equals("阳性") || token.equals("弱阳性");
    }

    private static String condense(List<String> lines) {
        String joined = String.join("\n", lines);
        return joined.length() <= MAX_FINDING_CHARS ? joined : joined.substring(0, MAX_FINDING_CHARS) + "…";
    }

    /** 日期行：中文「检查/报告日期」或 ASCII「Date:」（PDF 标准字体无法编码中文）。 */
    private static boolean isDateLine(String line) {
        return line.startsWith("检查日期") || line.startsWith("报告日期") || line.startsWith("体检日期")
                || line.toLowerCase(Locale.ROOT).startsWith("date");
    }

    private static String splitAfterColon(String line) {
        int idx = line.indexOf(':');
        if (idx < 0) {
            idx = line.indexOf('：');
        }
        return idx < 0 ? "" : line.substring(idx + 1).trim();
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.replace("/", "-").replace(".", "-"));
        } catch (Exception e) {
            return null;
        }
    }
}
