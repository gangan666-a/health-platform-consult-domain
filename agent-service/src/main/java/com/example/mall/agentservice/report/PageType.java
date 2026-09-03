package com.example.mall.agentservice.report;

/**
 * 报告页型（页级分类）：不同机构版式各异，但页面职责可枚举。
 * 各页型绑定独立的抽取 prompt 配方（PageTypeRegistry）与目标产物：
 * 检验明细页产出结构化指标，其余页产出「检查所见」文本，均不虚构内容。
 */
public enum PageType {

    COVER("封面", false),
    SUMMARY("总检结论", false),
    LAB("检验明细", true),
    ULTRASOUND("超声报告", false),
    RADIOLOGY("放射报告", false),
    ECG("心电图", false),
    DISCLAIMER("免责声明", false),
    OTHER("其他", false);

    private final String display;
    private final boolean yieldsIndicators;

    PageType(String display, boolean yieldsIndicators) {
        this.display = display;
        this.yieldsIndicators = yieldsIndicators;
    }

    public String display() {
        return display;
    }

    /** 是否预期产出结构化检验指标（仅检验明细页）。 */
    public boolean yieldsIndicators() {
        return yieldsIndicators;
    }

    /** LLM 输出词 → 页型；不可解析时返回 null（调用方降级关键词规则）。 */
    public static PageType parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim().toUpperCase().split("[\\s,，。;；]+")[0];
        for (PageType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
