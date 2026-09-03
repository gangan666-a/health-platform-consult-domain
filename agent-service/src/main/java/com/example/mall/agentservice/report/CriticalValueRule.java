package com.example.mall.agentservice.report;

import java.util.List;

/**
 * 危急值规则（classpath report/critical-rules.json 反序列化）：
 * kind=indicator 为指标阈值规则（indicators 匹配名列表 + op/value 阈值），
 * kind=finding 为检查所见关键词规则（keywords 命中即触发）。
 * 两种字段互斥使用，未用字段保持 null，便于单一 JSON 数组承载。
 */
public record CriticalValueRule(String id, String kind, List<String> indicators, List<String> keywords,
                                String op, Double value, String severity, String message) {

    public static final String KIND_INDICATOR = "indicator";
    public static final String KIND_FINDING = "finding";
}
