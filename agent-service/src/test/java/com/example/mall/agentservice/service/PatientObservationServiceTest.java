package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.ReportIndicatorRecord;
import com.example.mall.agentservice.report.IndicatorNormalizer;
import com.example.mall.agentservice.repository.ReportIndicatorRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 患者观测图（趋势投影）：派生方向 / 变化量 / 最近正常日期。
 * 不依赖图或 ES——纯 MySQL report_indicator 的查询时投影。
 */
class PatientObservationServiceTest {

    private final ReportIndicatorRecordRepository repo = mock(ReportIndicatorRecordRepository.class);
    private final IndicatorNormalizer normalizer = new IndicatorNormalizer(new com.fasterxml.jackson.databind.ObjectMapper());
    private final PatientObservationService service = new PatientObservationService(repo, normalizer);

    private static ReportIndicatorRecord row(String value, String status, LocalDate date) {
        ReportIndicatorRecord r = new ReportIndicatorRecord();
        r.setUserId(1L);
        r.setIndicatorName("LDL");
        r.setValue(value);
        r.setUnit("mmol/L");
        r.setStatus(status);
        r.setExamDate(date);
        return r;
    }

    @Test
    void risingHighValues_areWorseningWithPositiveDelta() {
        List<ReportIndicatorRecord> rows = List.of(
                row("5.1", "HIGH", LocalDate.of(2026, 1, 10)),
                row("5.4", "HIGH", LocalDate.of(2026, 4, 10)),
                row("5.8", "HIGH", LocalDate.of(2026, 7, 10)));
        when(repo.findByUserIdAndIndicatorNameOrderByExamDateAsc(anyLong(), eq("LDL"))).thenReturn(rows);

        PatientObservationService.IndicatorTrend t = service.trend(1L, "LDL");
        assertEquals(3, t.examCount());
        assertEquals(PatientObservationService.Direction.WORSENING, t.direction());
        assertTrue(t.summary().contains("+0.4"), "变化量应为 +0.4，实际: " + t.summary());
        assertTrue(t.summary().contains("恶化"), "偏高且上升应判为恶化: " + t.summary());
    }

    @Test
    void fallingHighValues_areImproving() {
        List<ReportIndicatorRecord> rows = List.of(
                row("6.0", "HIGH", LocalDate.of(2026, 1, 10)),
                row("5.2", "HIGH", LocalDate.of(2026, 4, 10)));
        when(repo.findByUserIdAndIndicatorNameOrderByExamDateAsc(anyLong(), eq("LDL"))).thenReturn(rows);

        PatientObservationService.IndicatorTrend t = service.trend(1L, "LDL");
        assertEquals(PatientObservationService.Direction.IMPROVING, t.direction());
        assertTrue(t.summary().contains("改善"));
    }

    @Test
    void lastNormalDate_isReported() {
        List<ReportIndicatorRecord> rows = List.of(
                row("5.8", "HIGH", LocalDate.of(2026, 1, 10)),
                row("5.2", "NORMAL", LocalDate.of(2026, 4, 10)),
                row("6.1", "HIGH", LocalDate.of(2026, 7, 10)));
        when(repo.findByUserIdAndIndicatorNameOrderByExamDateAsc(anyLong(), eq("LDL"))).thenReturn(rows);

        PatientObservationService.IndicatorTrend t = service.trend(1L, "LDL");
        assertEquals(LocalDate.of(2026, 4, 10), t.lastNormalDate());
        assertTrue(t.summary().contains("最近正常 2026-04-10"));
    }

    @Test
    void noHistory_reportsAbsent() {
        when(repo.findByUserIdAndIndicatorNameOrderByExamDateAsc(anyLong(), eq("LDL")))
                .thenReturn(List.of());
        PatientObservationService.IndicatorTrend t = service.trend(1L, "LDL");
        assertEquals(0, t.examCount());
        assertEquals(PatientObservationService.Direction.UNKNOWN, t.direction());
        assertTrue(t.summary().contains("无该指标的历史数据"));
    }
}
