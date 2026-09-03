package com.example.mall.agentservice.report;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报告解读知识库检索：ES 不可用降级为空（E-4 / T-2）+ 异常指标知识解析（lab_exam / report_template）。
 */
class ReportKnowledgeServiceTest {

    private ReportDoc doc(ReportIndicator... indicators) {
        return new ReportDoc("血常规", LocalDate.of(2026, 8, 1), "vision",
                ReportTypeDetector.ReportType.LAB, "", false, List.of(indicators));
    }

    @Test
    void enrich_whenEsUnavailable_degradesToEmpty() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        when(es.search(any(Function.class), eq(Map.class))).thenThrow(new RuntimeException("ES unavailable"));
        ReportKnowledgeService service = new ReportKnowledgeService(es);

        ReportDoc report = doc(new ReportIndicator("ALT", "60", "U/L", "7-40",
                IndicatorStatus.HIGH, LocalDate.of(2026, 8, 1)));

        assertTrue(service.enrich(report).isBlank());
    }

    @Test
    void enrich_withoutAbnormalIndicators_returnsEmpty() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        when(es.search(any(Function.class), eq(Map.class))).thenThrow(new RuntimeException("ES unavailable"));
        ReportKnowledgeService service = new ReportKnowledgeService(es);

        ReportDoc report = doc(new ReportIndicator("WBC", "6.5", "10^9/L", "3.5-9.5",
                IndicatorStatus.NORMAL, LocalDate.of(2026, 8, 1)));

        assertTrue(service.enrich(report).isBlank());
    }

    @Test
    void extract_highIndicator_parsesMeaningAndAdvice() {
        Map<String, Object> src = Map.of(
                "exam_name", "肝功能",
                "indicators", List.of(Map.of(
                        "name", "ALT",
                        "ref_range", "7-40",
                        "unit", "U/L",
                        "high_meaning", "提示肝细胞损伤可能",
                        "low_meaning", "")),
                "abnormal_advice", "建议消化内科复查肝功能");

        String line = ReportKnowledgeService.extractIndicatorKnowledge(src, "ALT", "high_meaning");

        assertTrue(line != null && line.contains("ALT"));
        assertTrue(line.contains("偏高"));
        assertTrue(line.contains("肝细胞损伤"));
        assertTrue(line.contains("消化内科"));
    }

    @Test
    void extract_lowIndicator_parsesMeaning() {
        Map<String, Object> src = Map.of(
                "indicators", List.of(Map.of("name", "HGB", "low_meaning", "提示贫血可能")),
                "abnormal_advice", "建议血常规复查");

        String line = ReportKnowledgeService.extractIndicatorKnowledge(src, "HGB", "low_meaning");

        assertTrue(line != null && line.contains("HGB"));
        assertTrue(line.contains("偏低"));
        assertTrue(line.contains("贫血"));
    }

    @Test
    void extract_missingIndicator_returnsNull() {
        Map<String, Object> src = Map.of(
                "indicators", List.of(Map.of("name", "ALT", "high_meaning", "x")));

        assertNull(ReportKnowledgeService.extractIndicatorKnowledge(src, "WBC", "high_meaning"));
    }
}
