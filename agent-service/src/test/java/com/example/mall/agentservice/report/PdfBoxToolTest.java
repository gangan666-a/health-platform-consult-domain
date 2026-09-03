package com.example.mall.agentservice.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PDFBox 前置 Tool：文本层抽取 + 降级（T-7 契约：失败不阻断解析）。 */
public class PdfBoxToolTest {

    private final PdfBoxTool tool = new PdfBoxTool();

    @Test
    void extractText_readsTextLayer() {
        byte[] pdf = pdfWithLine("WBC 6.5 10^9/L 3.5-9.5");

        String text = tool.extractText(pdf);

        assertTrue(text.contains("WBC 6.5 10^9/L 3.5-9.5"));
    }

    @Test
    void extractText_corruptedPdf_returnsEmpty() {
        assertEquals("", tool.extractText(new byte[]{1, 2, 3}));
        assertEquals("", tool.extractText(null));
        assertEquals("", tool.extractText(new byte[0]));
    }

    @Test
    void isPdf_detectsByNameOrContentType() {
        assertTrue(tool.isPdf("report.pdf", null));
        assertTrue(tool.isPdf("report", "application/pdf"));
        assertTrue(tool.isPdf("report.PDF", "application/pdf"));
        assertFalse(tool.isPdf("report.png", "image/png"));
        assertFalse(tool.isPdf(null, null));
    }

    /** 供其他测试复用：生成仅含 ASCII 文本的最小 PDF（\n 分行，showText 不接受换行符）。 */
    public static byte[] pdfWithLine(String text) {
        return pdfWithPages(text);
    }

    /** 供其他测试复用：多页 PDF（每个元素一页，页内 \n 分行）。 */
    public static byte[] pdfWithPages(String... pageTexts) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                String[] lines = pageText.split("\n");
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    for (int i = 0; i < lines.length; i++) {
                        cs.beginText();
                        cs.newLineAtOffset(50, 700 - i * 20);
                        cs.showText(lines[i]);
                        cs.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("测试 PDF 生成失败", e);
        }
    }
}
