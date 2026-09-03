package com.example.mall.agentservice.report;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 解析门面（plan-execute）：PROFILE（确定性前置工具）→ PLAN（LLM 规划/确定性兜底）
 * → EXECUTE（分类/抽取/聚合/校验，页级并行）→ 组装统一产物。
 * ① PDF：PDFBox 逐页抽文本层；图片：EXIF 方向矫正 + 降采样（不读 GPS，隐私红线）；
 * ② 报告日期兜底链：报告显式日期 > EXIF 拍摄日期（照片件） > 当天。
 */
@Component
public class ReportParser {

    private final PdfBoxTool pdfBoxTool;
    private final ExifTool exifTool;
    private final ReportPlanner planner;
    private final PlanExecutor executor;

    public ReportParser(PdfBoxTool pdfBoxTool, ExifTool exifTool,
                        ReportPlanner planner, PlanExecutor executor) {
        this.pdfBoxTool = pdfBoxTool;
        this.exifTool = exifTool;
        this.planner = planner;
        this.executor = executor;
    }

    public ReportParseResult parse(byte[] content, String fileName, String contentType) {
        return parse(content, fileName, contentType, ProgressListener.noop());
    }

    public ReportParseResult parse(byte[] content, String fileName, String contentType,
                                   ProgressListener progress) {
        // PROFILE：确定性前置工具构建文件画像
        progress.onProgress("PROFILE", 1, 1, fileName);
        FileProfile profile = buildProfile(content, fileName, contentType);
        return parseProfile(profile, progress);
    }

    /** 从文件画像继续（评测门禁直喂中文页文本用，绕过 PDF 字体渲染限制）。 */
    ReportParseResult parseProfile(FileProfile profile, ProgressListener progress) {
        PlanExecutionTrace trace = new PlanExecutionTrace();
        trace.done(ReportPlan.STEP_PROFILE, profile.pages().size() + "页"
                + (profile.pdf() ? "·PDF" : "·图片"));

        // PLAN：LLM 规划（不可用时确定性兜底）
        progress.onProgress("PLAN", 1, 1, "规划解析步骤");
        ReportPlan plan = planner.plan(profile);
        trace.done(ReportPlan.STEP_PLAN, "planner=" + plan.planner());

        // EXECUTE：分类 → 抽取 → 聚合 → 校验（页级并行）
        PageAggregator.AggregatedReport aggregated = executor.execute(profile, plan, trace, progress);

        ParsedReport parsed = aggregated.parsed();
        LocalDate reportDate = resolveReportDate(parsed.reportDate(), profile.exifCaptureDate());
        ParsedReport dated = new ParsedReport(parsed.examName(), reportDate, parsed.source(),
                parsed.rawText(), parsed.indicators(), parsed.findings());
        ReportDoc doc = ReportDoc.from(dated, aggregated.type(), false);
        return new ReportParseResult(doc, parsed.findings(), plan, trace.render());
    }

    /** 文件画像：优先按魔数路由（PDF → 逐页文本层；图片 → EXIF 归一化后单页走视觉通道）；
     *  魔数无法识别时退回扩展名/类型路由（保持损坏 PDF 降级等既有语义）。 */
    private FileProfile buildProfile(byte[] content, String fileName, String contentType) {
        String format = FormatSniffer.detect(content);
        if (format != null && FormatSniffer.isPdf(format)) {
            return pdfProfile(content, fileName, contentType);
        }
        if (format != null && FormatSniffer.isImage(format)) {
            return imageProfile(content, fileName, contentType);
        }
        if (pdfBoxTool.isPdf(fileName, contentType)) {
            return pdfProfile(content, fileName, contentType);
        }
        if (exifTool.isImage(fileName, contentType)) {
            return imageProfile(content, fileName, contentType);
        }
        return new FileProfile(fileName, contentType, false, List.of(), null);
    }

    private FileProfile pdfProfile(byte[] content, String fileName, String contentType) {
        return new FileProfile(fileName, contentType, true, pdfBoxTool.splitPages(content), null);
    }

    private FileProfile imageProfile(byte[] content, String fileName, String contentType) {
        ExifTool.NormalizedImage normalized = exifTool.normalize(content, fileName, contentType);
        return new FileProfile(fileName, contentType, false,
                List.of(new PageContent(1, "", normalized.content())), normalized.captureDate());
    }

    /** 日期兜底链：报告显式日期 > EXIF 拍摄日期（照片件） > 当天。 */
    static LocalDate resolveReportDate(LocalDate parsedDate, LocalDate exifDate) {
        if (parsedDate != null) {
            return parsedDate;
        }
        return exifDate != null ? exifDate : LocalDate.now();
    }
}
