package com.example.mall.agentservice.report;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 页型分类关键词规则：轻量 LLM 不可用/失败时的确定性兜底。
 * 匹配顺序 = 特征词从专到泛（心电图/超声/放射先于检验表格表头，免责声明最末），
 * 无命中 → OTHER（走通用配方，不阻断）。
 */
@Component
public class PageTypeKeywordDetector {

    public PageType detect(PageContent page, String fileName) {
        String text = page.text() == null ? "" : page.text();
        String lower = text.toLowerCase(Locale.ROOT);
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        // 心电图（先于放射：部分心电报告含"报告"字样但不含放射词）
        if (containsAny(lower, "心电图", "ecg", "窦性心律", "st段", "st段改变", "t波", "p-r间期", "qrs")) {
            return PageType.ECG;
        }
        // 超声
        if (containsAny(lower, "超声", "彩超", "b超", "超声所见", "超声提示")) {
            return PageType.ULTRASOUND;
        }
        // 放射
        if (containsAny(lower, "放射", "dr摄片", "dr 摄影", "ct", "x线", "x-ray", "胸片", "摄影所见", "影像所见", "影像诊断")) {
            return PageType.RADIOLOGY;
        }
        // 总检结论
        if (containsAny(lower, "总检结论", "体检结论", "总检汇总", "体检汇总", "结论与建议", "异常汇总", "主检", "总检")) {
            return PageType.SUMMARY;
        }
        // 检验明细：表格表头特征
        if (containsAny(lower, "参考范围", "参考区间", "检验项目", "检验结果", "项目名称", "化验", "血常规", "尿常规", "生化")
                || containsAny(lowerName, "化验", "血常规", "尿常规", "生化", "检验")) {
            return PageType.LAB;
        }
        // 免责声明
        if (containsAny(lower, "免责", "声明：", "本报告仅供", "最终解释权", "仅供参考，")) {
            return PageType.DISCLAIMER;
        }
        // 封面：首页 + 个人信息字段排版（无指标表格特征）
        if (page.pageNo() == 1
                && containsAny(lower, "姓名", "性别", "年龄", "体检号", "检查号", "条码")
                && !containsAny(lower, "参考范围", "参考区间")) {
            return PageType.COVER;
        }
        // 封面兜底：文件名直指体检报告的首页
        if (page.pageNo() == 1 && containsAny(lowerName, "体检报告", "健康体检")) {
            return PageType.COVER;
        }
        return PageType.OTHER;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
