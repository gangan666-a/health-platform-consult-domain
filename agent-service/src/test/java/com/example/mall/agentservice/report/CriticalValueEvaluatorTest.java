package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 危急值规则引擎（防线3/B-7）：阈值触发 + 别名匹配 + 所见关键词触发 + 不误触。 */
class CriticalValueEvaluatorTest {

    private final CriticalValueEvaluator evaluator =
            new CriticalValueEvaluator(new ObjectMapper(), new IndicatorNormalizer(new ObjectMapper()));

    private static ReportIndicator indicator(String name, String value) {
        return new ReportIndicator(name, value, "", "", IndicatorStatus.UNKNOWN, LocalDate.now());
    }

    @Test
    void criticalHighGlucose_triggers() {
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("GLU", "35.2")), List.of());

        assertEquals(1, alerts.size());
        assertEquals("GLU-CRIT-HIGH", alerts.get(0).ruleId());
        assertTrue(alerts.get(0).isCritical());
    }

    @Test
    void aliasIndicatorName_triggers() {
        // 报告写「葡萄糖」，规则配置写「血糖」——归一后同为 GLU 命中
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("葡萄糖", "2.5")), List.of());

        assertEquals(1, alerts.size());
        assertEquals("GLU-CRIT-LOW", alerts.get(0).ruleId());
    }

    @Test
    void severeAnemia_triggers() {
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("血红蛋白", "55")), List.of());

        assertEquals("HGB-CRIT-LOW", alerts.get(0).ruleId());
    }

    @Test
    void findingKeywords_trigger() {
        List<CriticalAlert> alerts = evaluator.evaluate(List.of(),
                List.of(new ReportFinding("超声报告", "超声提示：肝内占位，建议进一步检查", 3)));

        assertTrue(alerts.stream().anyMatch(a -> "IMG-REDFLAG".equals(a.ruleId())));
    }

    @Test
    void ecgRedFlag_triggersCritical() {
        List<CriticalAlert> alerts = evaluator.evaluate(List.of(),
                List.of(new ReportFinding("心电图", "诊断：房颤", 2)));

        assertEquals("ECG-REDFLAG", alerts.get(0).ruleId());
        assertTrue(alerts.get(0).isCritical());
    }

    @Test
    void normalValues_noAlerts() {
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("GLU", "5.2"), indicator("WBC", "6.5"), indicator("HGB", "138")), List.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void nonNumericValue_noAlerts() {
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("GLU", "见备注")), List.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void criticalSortedBeforeHigh() {
        List<CriticalAlert> alerts = evaluator.evaluate(
                List.of(indicator("GLU", "35.0"), indicator("WBC", "31")),
                List.of(new ReportFinding("超声报告", "考虑占位", 1)));

        assertEquals("CRITICAL", alerts.get(0).severity());
    }
}
