package com.example.mall.agentservice.report;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 参考范围比对：ES 不可用降级本地规则 + referenceVerified 标注（E-4 / T-6 / T-2）。 */
class ReportReferenceServiceTest {

    @Test
    void align_whenEsUnavailable_degradesToLocalRules_andFlagsNotVerified() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        when(es.search(any(Function.class), eq(Map.class))).thenThrow(new RuntimeException("ES unavailable"));
        ReportReferenceService service = new ReportReferenceService(es);

        ReportDoc doc = new ReportDoc("血常规", LocalDate.of(2026, 8, 1), "vision",
                ReportTypeDetector.ReportType.LAB, "", false,
                List.of(new ReportIndicator("WBC", "12.0", "10^9/L", "3.5-9.5",
                        IndicatorStatus.UNKNOWN, LocalDate.of(2026, 8, 1))));

        ReportDoc aligned = service.align(doc);

        assertFalse(aligned.referenceVerified());
        // 本地规则：12.0 超出 3.5-9.5 → HIGH
        assertEquals(IndicatorStatus.HIGH, aligned.indicators().get(0).status());
        // ES 不可用但未命中权威范围时，沿用报告自带 refRange
        assertEquals("3.5-9.5", aligned.indicators().get(0).refRange());
    }

    @Test
    void align_marksNormal_whenValueInReportRange() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        when(es.search(any(Function.class), eq(Map.class))).thenThrow(new RuntimeException("ES unavailable"));
        ReportReferenceService service = new ReportReferenceService(es);

        ReportDoc doc = new ReportDoc("血常规", LocalDate.of(2026, 8, 1), "vision",
                ReportTypeDetector.ReportType.LAB, "", false,
                List.of(new ReportIndicator("HGB", "138", "g/L", "115-150",
                        IndicatorStatus.UNKNOWN, LocalDate.of(2026, 8, 1))));

        ReportDoc aligned = service.align(doc);

        assertEquals(IndicatorStatus.NORMAL, aligned.indicators().get(0).status());
    }

    // ---- 防线1（B-8）：范围优先级决策（static 便于直测） ----

    @Test
    void resolveRange_ownRangeUsable_winsOverEs() {
        // 自带范围可用（3.5-9.5）→ 即使 ES 给出不同范围（4.0-10.0）也以报告自带为准
        ReportReferenceService.Resolved resolved =
                ReportReferenceService.resolveRange("12.0", "3.5-9.5", "4.0-10.0");

        assertEquals("3.5-9.5", resolved.ref());
        assertEquals(IndicatorStatus.HIGH, resolved.status());
    }

    @Test
    void resolveRange_ownMissing_fallsBackToEs() {
        ReportReferenceService.Resolved resolved =
                ReportReferenceService.resolveRange("5.0", "", "3.9-6.1");

        assertEquals("3.9-6.1", resolved.ref());
        assertEquals(IndicatorStatus.NORMAL, resolved.status());
    }

    @Test
    void resolveRange_ownUnparseable_fallsBackToEs() {
        ReportReferenceService.Resolved resolved =
                ReportReferenceService.resolveRange("5.0", "以本院标准", "3.9-6.1");

        assertEquals("3.9-6.1", resolved.ref());
        assertEquals(IndicatorStatus.NORMAL, resolved.status());
    }

    @Test
    void resolveRange_bothUnavailable_unknownAndKeepsOwn() {
        ReportReferenceService.Resolved resolved =
                ReportReferenceService.resolveRange("5.0", "以本院标准", null);

        assertEquals("以本院标准", resolved.ref());
        assertEquals(IndicatorStatus.UNKNOWN, resolved.status());
    }
}
