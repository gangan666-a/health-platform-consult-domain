package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plan-execute 解析门面：文本层 PDF（确定性通道）→ 指标/所见抽取；图片件（视觉通道未接入）
 * → 明确未识别不虚构；多页聚合；损坏 PDF 空页集降级（T-7 契约）。
 * PDF 标准字体无法编码中文，夹具用 ASCII；中文页型链路在 ReportEvalTest 以 parseProfile 直喂覆盖。
 */
public class ReportParserTest {

    private final ReportParser parser = newReportParser();

    /** 全确定性组件（无 Key → 规划/分类/抽取全部走兜底通道，评测门禁同构）。 */
    static ReportParser newReportParser() {
        PageTypeRegistry registry = new PageTypeRegistry();
        return new ReportParser(
                new PdfBoxTool(),
                new ExifTool(2048),
                new LlmReportPlanner(null, "", ""),
                new PlanExecutor(
                        new LlmPageClassifier(null, new PageTypeKeywordDetector(), "", ""),
                        new LlmTextPageExtractor(null, registry, "", "", false),
                        new MockVisionAnalyzer(),
                        registry,
                        new PageAggregator(new IndicatorNormalizer(new ObjectMapper())),
                        new com.example.mall.agentservice.config.ReportMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())));
    }

    @Test
    void parse_textLayerLabPdf_extractsIndicators() {
        byte[] pdf = PdfBoxToolTest.pdfWithLine("#xuechanggui\nWBC 6.5 10^9/L 3.5-9.5\nHGB 138 g/L 115-150");

        ReportParseResult result = parser.parse(pdf, "report.pdf", "application/pdf");

        assertEquals(2, result.doc().indicators().size());
        assertEquals("WBC", result.doc().indicators().get(0).name());
        assertEquals("6.5", result.doc().indicators().get(0).value());
        assertEquals("xuechanggui", result.doc().examName());
        assertEquals(LocalDate.now(), result.doc().reportDate());
        assertEquals("deterministic", result.doc().source());
        assertTrue(result.trace().stream().anyMatch(t -> t.contains("planner=deterministic")));
    }

    @Test
    void parse_headerAndTextLines_notCountedAsIndicators() {
        byte[] pdf = PdfBoxToolTest.pdfWithLine(
                "#shenghua\njianyan-xiangmu jieguo danwei cankao-fanwei\nALT 42 U/L 7-40\nbeizhu fuzhen");

        ReportParseResult result = parser.parse(pdf, "report.pdf", "application/pdf");

        assertEquals(1, result.doc().indicators().size());
        assertEquals("ALT", result.doc().indicators().get(0).name());
    }

    @Test
    void parse_multiPagePdf_aggregatesIndicatorsAndFindings() {
        byte[] pdf = PdfBoxToolTest.pdfWithPages(
                "#jiankang-tijian\njianyan-xiangmu jieguo danwei cankao-fanwei\nWBC 6.5 10^9/L 3.5-9.5",
                "#chaosheng\nchaosheng-tishi gannei gaihuazao");

        ReportParseResult result = parser.parse(pdf, "physical.pdf", "application/pdf");

        // ASCII 夹具无中文关键词 → 两页均 OTHER；指标行归指标、文字行归所见
        assertEquals(1, result.doc().indicators().size());
        assertEquals("WBC", result.doc().indicators().get(0).name());
        assertTrue(result.findings().stream().anyMatch(f -> f.text().contains("gannei gaihuazao")));
        assertTrue(result.trace().stream().anyMatch(t -> t.startsWith("AGGREGATE:DONE")));
    }

    @Test
    void parse_imageWithoutVision_emptyAndNoFabrication() {
        byte[] png = tinyPng();

        ReportParseResult result = parser.parse(png, "xuechanggui.png", "image/png");

        // E-3：视觉通道未接入时明确未识别，绝不虚构样例指标
        assertTrue(result.doc().indicators().isEmpty());
        assertTrue(result.findings().isEmpty());
        assertTrue(result.trace().stream().anyMatch(t -> t.contains("vision-unavailable")));
        assertEquals("vision", result.doc().source());
    }

    @Test
    void parse_corruptedPdf_degradesToEmpty() {
        ReportParseResult result = parser.parse(new byte[]{1, 2, 3}, "report.pdf", "application/pdf");

        assertTrue(result.doc().indicators().isEmpty());
        assertTrue(result.trace().stream().anyMatch(t -> t.contains("SKIPPED")));
    }

    @Test
    void parse_progressListener_receivesStages() {
        byte[] pdf = PdfBoxToolTest.pdfWithLine("#xuechanggui\nWBC 6.5 10^9/L 3.5-9.5");
        List<String> stages = new ArrayList<>();

        parser.parse(pdf, "report.pdf", "application/pdf",
                (stage, current, total, detail) -> stages.add(stage));

        assertTrue(stages.contains("PROFILE"));
        assertTrue(stages.contains("PLAN"));
        assertTrue(stages.contains("CLASSIFY"));
        assertTrue(stages.contains("EXTRACT"));
    }

    @Test
    void resolveReportDate_prefersExplicit_thenExif_thenToday() {
        assertEquals(LocalDate.of(2026, 8, 1),
                ReportParser.resolveReportDate(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1)));
        assertEquals(LocalDate.of(2026, 7, 1), ReportParser.resolveReportDate(null, LocalDate.of(2026, 7, 1)));
        assertEquals(LocalDate.now(), ReportParser.resolveReportDate(null, null));
    }

    /** 最小合法 PNG（1×1），供图片通道测试（跨包复用：upload E-3 用例）。 */
    public static byte[] tinyPng() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            assertNotNull(out.toByteArray());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("测试 PNG 生成失败", e);
        }
    }
}
