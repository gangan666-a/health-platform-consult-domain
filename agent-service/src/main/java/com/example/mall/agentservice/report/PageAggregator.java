package com.example.mall.agentservice.report;

import com.example.mall.agentservice.report.ReportTypeDetector.ReportType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 页抽取结果聚合（AGGREGATE + VALIDATE 步骤）：把各页 PageExtraction 归并为
 * 统一数据模型（canonical：指标 + 检查所见），异构性到此为止，下游只见统一 schema。
 * 确定性校验：指标名归一（跨机构对比前提）、重名去重保留首次、缺值丢弃并记录 note。
 */
@Component
public class PageAggregator {

    /** 聚合产物：统一中间结果 + 文档级类型 + 校验备注（进执行轨迹）。 */
    public record AggregatedReport(ParsedReport parsed, ReportType type, List<String> notes) {
    }

    private final IndicatorNormalizer normalizer;

    public PageAggregator(IndicatorNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public AggregatedReport aggregate(FileProfile profile, List<PageExtraction> extractions) {
        List<String> notes = new ArrayList<>();

        String examName = pickExamName(extractions);
        LocalDate reportDate = pickReportDate(extractions);
        List<ReportIndicator> indicators = mergeIndicators(extractions, notes);
        List<ReportFinding> findings = mergeFindings(extractions);
        String rawText = profile.pages().stream()
                .map(PageContent::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
        String source = pickSource(extractions);
        ReportType type = ReportTypeDetector.fromPageTypes(
                extractions.stream().map(PageExtraction::pageType).collect(Collectors.toSet()));

        ParsedReport parsed = new ParsedReport(examName, reportDate, source, rawText, indicators, findings);
        return new AggregatedReport(parsed, type, notes);
    }

    /** 检查名优先级：封面刊头 > 总检结论 > 检验明细 > 其他非空页。 */
    private static String pickExamName(List<PageExtraction> extractions) {
        String fallback = null;
        for (PageType priority : List.of(PageType.COVER, PageType.SUMMARY, PageType.LAB)) {
            for (PageExtraction e : extractions) {
                if (e.pageType() == priority && e.sectionTitle() != null && !e.sectionTitle().isBlank()
                        && !e.sectionTitle().equals(e.pageType().display())) {
                    return e.sectionTitle();
                }
            }
        }
        for (PageExtraction e : extractions) {
            if (e.sectionTitle() != null && !e.sectionTitle().isBlank()
                    && !e.sectionTitle().equals(e.pageType().display())) {
                fallback = e.sectionTitle();
                break;
            }
        }
        return fallback != null ? fallback : "检查报告";
    }

    /** 报告日期优先级：总检结论/封面 > 任意页。 */
    private static LocalDate pickReportDate(List<PageExtraction> extractions) {
        LocalDate any = null;
        for (PageExtraction e : extractions) {
            LocalDate date = parseDate(e.reportDate());
            if (date == null) {
                continue;
            }
            if (e.pageType() == PageType.SUMMARY || e.pageType() == PageType.COVER) {
                return date;
            }
            if (any == null) {
                any = date;
            }
        }
        return any;
    }

    private List<ReportIndicator> mergeIndicators(List<PageExtraction> extractions, List<String> notes) {
        List<ReportIndicator> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PageExtraction e : extractions) {
            for (ReportIndicator in : e.indicators()) {
                if (in.value() == null || in.value().isBlank()) {
                    notes.add("丢弃缺值指标（第" + e.pageNo() + "页）: " + in.name());
                    continue;
                }
                String canonical = normalizer.normalize(in.name());
                if (!seen.add(canonical)) {
                    notes.add("重复指标保留首次出现: " + canonical + "（第" + e.pageNo() + "页）");
                    continue;
                }
                // 单位归一（跨机构对比前提）：mg/dL↔mmol/L 等，值与参考范围联动换算
                UnitConverter.WithRange unit = UnitConverter.convertWithRange(
                        canonical, in.value(), in.unit(), in.refRange());
                if (unit.converted()) {
                    notes.add("单位归一 " + canonical + ": " + in.unit() + " → " + unit.unit());
                }
                merged.add(new ReportIndicator(canonical, unit.value(), unit.unit(), unit.refRange(),
                        in.status(), in.examDate()));
            }
        }
        return merged;
    }

    private static List<ReportFinding> mergeFindings(List<PageExtraction> extractions) {
        List<ReportFinding> findings = new ArrayList<>();
        for (PageExtraction e : extractions) {
            if (e.pageType() == PageType.DISCLAIMER) {
                continue;
            }
            for (String text : e.findings()) {
                if (text != null && !text.isBlank()) {
                    findings.add(new ReportFinding(e.pageType().display(), text, e.pageNo()));
                }
            }
        }
        return findings;
    }

    /** 解析通道优先级：llm（含交叉校验通道）> deterministic > 视觉（用于 source 落库与展示）。 */
    private static String pickSource(List<PageExtraction> extractions) {
        boolean anyLlm = false;
        boolean anyDeterministic = false;
        for (PageExtraction e : extractions) {
            if (PageExtraction.CHANNEL_LLM.equals(e.channel())
                    || PageExtraction.CHANNEL_LLM_VERIFIED.equals(e.channel())
                    || PageExtraction.CHANNEL_LLM_VERIFIED_CORRECTED.equals(e.channel())) {
                anyLlm = true;
            } else if (PageExtraction.CHANNEL_DETERMINISTIC.equals(e.channel())
                    || PageExtraction.CHANNEL_DETERMINISTIC_FALLBACK.equals(e.channel())) {
                anyDeterministic = true;
            }
        }
        if (anyLlm) {
            return "llm";
        }
        return anyDeterministic ? "deterministic" : "vision";
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().replace("/", "-").replace(".", "-"));
        } catch (Exception e) {
            return null;
        }
    }
}
