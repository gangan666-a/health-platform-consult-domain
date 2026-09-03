package com.example.mall.agentservice.report;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;

/**
 * 报告类型识别：文档级 ReportType 由页级分类结果推导（fromPageTypes），
 * 或在无页可判时退回文件名/文本关键词（detect）。
 */
@Component
public class ReportTypeDetector {

    public enum ReportType { LAB, PHYSICAL, OTHER }

    private static final String[] PHYSICAL_KEYWORDS = {"体检", "健康检查"};
    private static final String[] LAB_KEYWORDS = {
            "化验", "血常规", "尿常规", "便常规", "生化", "肝功", "肾功", "血糖", "血脂", "血检",
            "wbc", "rbc", "hgb"
    };

    /** 由页型集合推导文档级类型：总检结论/封面在 → 体检单；检验明细在 → 化验单；否则 OTHER。 */
    public static ReportType fromPageTypes(Collection<PageType> pageTypes) {
        if (pageTypes.contains(PageType.SUMMARY) || pageTypes.contains(PageType.COVER)) {
            return ReportType.PHYSICAL;
        }
        if (pageTypes.contains(PageType.LAB)) {
            return ReportType.LAB;
        }
        return ReportType.OTHER;
    }

    public ReportType detect(String fileName, String textLayer) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (containsAny(lowerName, PHYSICAL_KEYWORDS)) {
            return ReportType.PHYSICAL;
        }
        if (containsAny(lowerName, LAB_KEYWORDS)) {
            return ReportType.LAB;
        }
        // 文件名不含关键词时，用文本层辅助判断（含英文指标需统一小写再匹配）
        String text = textLayer == null ? "" : textLayer.toLowerCase(Locale.ROOT);
        if (containsAny(text, PHYSICAL_KEYWORDS)) {
            return ReportType.PHYSICAL;
        }
        if (containsAny(text, LAB_KEYWORDS)) {
            return ReportType.LAB;
        }
        return ReportType.OTHER;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
