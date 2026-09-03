package com.example.mall.agentservice.report;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 报告前置 Tool 1（PDFBox）：在 LLM 解析之前按页抽取 PDF 文本层，
 * 供页级分类、规划与文本 LLM 抽取通道使用（plan-execute 的 PROFILE 步骤）。
 * 扫描件无文本层或解析失败时返回空页集，降级走视觉通道（fail-open，不阻断上传）。
 */
@Component
public class PdfBoxTool {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxTool.class);

    public boolean isPdf(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT)
                .startsWith(MediaType.APPLICATION_PDF_VALUE)) {
            return true;
        }
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** 抽取 PDF 全部文本；非 PDF/加密/损坏返回空串，由调用方降级。 */
    public String extractText(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            return text == null ? "" : text.strip();
        } catch (Exception e) {
            log.warn("PDFBox 文本抽取失败，降级原通道: {}", e.getMessage());
            return "";
        }
    }

    /** 逐页抽取文本层 → 页内容列表（image 为 null，图片渲染待真实 VLM 接入时按需启用）；
     *  非 PDF/加密/损坏返回空列表，由调用方降级视觉通道。 */
    public List<PageContent> splitPages(byte[] content) {
        if (content == null || content.length == 0) {
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            int count = document.getNumberOfPages();
            List<PageContent> pages = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                pages.add(new PageContent(i, text == null ? "" : text.strip(), null));
            }
            return pages;
        } catch (Exception e) {
            log.warn("PDFBox 分页抽取失败，降级为空页集: {}", e.getMessage());
            return List.of();
        }
    }
}
