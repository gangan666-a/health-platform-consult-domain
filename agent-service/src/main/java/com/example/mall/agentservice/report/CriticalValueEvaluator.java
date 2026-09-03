package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 危急值规则引擎（防线3，B-7）：纯 Java 确定性规则，不依赖 LLM——LLM 负责理解与生成，
 * 规则负责安全兜底。两类规则（resources/report/critical-rules.json）：
 * ① indicator：指标阈值规则（归一名匹配 + GT/GE/LT/LE 比较）；
 * ② finding：检查所见关键词规则（占位/恶性可能等描述命中即触发）。
 * 触发的告警必须出现在最终响应并强制注入解读 prompt（由 ReportUploadService 保证）。
 * 词典缺失属部署错误：启动失败，绝不静默地失去安全兜底。
 */
@Component
public class CriticalValueEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CriticalValueEvaluator.class);

    private static final String RULES_PATH = "report/critical-rules.json";
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d*\\.?\\d+");

    private record RulesFile(List<CriticalValueRule> rules) {
    }

    private final ObjectMapper objectMapper;
    private final IndicatorNormalizer normalizer;
    private final List<CriticalValueRule> rules = new ArrayList<>();

    public CriticalValueEvaluator(ObjectMapper objectMapper, IndicatorNormalizer normalizer) {
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
        load();
    }

    private void load() {
        try (InputStream in = new ClassPathResource(RULES_PATH).getInputStream()) {
            RulesFile file = objectMapper.readValue(in, RulesFile.class);
            if (file.rules() != null) {
                rules.addAll(file.rules());
            }
            log.info("危急值规则加载完成：{} 条", rules.size());
        } catch (IOException e) {
            throw new UncheckedIOException("危急值规则文件缺失或不可读: " + RULES_PATH, e);
        }
    }

    /** 评估指标 + 检查所见，产出告警列表（CRITICAL 在前）。 */
    public List<CriticalAlert> evaluate(List<ReportIndicator> indicators, List<ReportFinding> findings) {
        List<CriticalAlert> alerts = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        for (CriticalValueRule rule : rules) {
            if (CriticalValueRule.KIND_INDICATOR.equals(rule.kind())) {
                evaluateIndicatorRule(rule, indicators, alerts, dedupe);
            } else if (CriticalValueRule.KIND_FINDING.equals(rule.kind())) {
                evaluateFindingRule(rule, findings, alerts, dedupe);
            }
        }
        alerts.sort((a, b) -> Boolean.compare(b.isCritical(), a.isCritical()));
        return alerts;
    }

    private void evaluateIndicatorRule(CriticalValueRule rule, List<ReportIndicator> indicators,
                                       List<CriticalAlert> alerts, Set<String> dedupe) {
        if (rule.value() == null || rule.op() == null || rule.indicators() == null) {
            return;
        }
        Set<String> targets = new HashSet<>();
        for (String name : rule.indicators()) {
            targets.add(normalizer.normalize(name));
        }
        for (ReportIndicator in : indicators) {
            String canonical = normalizer.normalize(in.name());
            if (!targets.contains(canonical)) {
                continue;
            }
            Double value = parseFirstNumber(in.value());
            if (value == null || !compare(value, rule.op(), rule.value())) {
                continue;
            }
            if (dedupe.add(rule.id() + ":" + canonical)) {
                alerts.add(new CriticalAlert(rule.severity(), rule.id(), canonical, rule.message()));
            }
        }
    }

    private void evaluateFindingRule(CriticalValueRule rule, List<ReportFinding> findings,
                                     List<CriticalAlert> alerts, Set<String> dedupe) {
        if (rule.keywords() == null || rule.keywords().isEmpty()) {
            return;
        }
        for (ReportFinding finding : findings) {
            String text = finding.text() == null ? "" : finding.text();
            for (String keyword : rule.keywords()) {
                if (keyword != null && !keyword.isBlank() && text.contains(keyword.trim())
                        && dedupe.add(rule.id() + ":" + keyword)) {
                    alerts.add(new CriticalAlert(rule.severity(), rule.id(), finding.section(), rule.message()));
                    break;
                }
            }
        }
    }

    private static boolean compare(double value, String op, double threshold) {
        return switch (op.toUpperCase(Locale.ROOT)) {
            case "GT" -> value > threshold;
            case "GE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LE" -> value <= threshold;
            default -> false;
        };
    }

    /** 提取数值原文中的首个数字（"35.2 ↑" → 35.2）；无数字不触发（不猜）。 */
    private static Double parseFirstNumber(String s) {
        if (s == null || s.isBlank()) {
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
}
