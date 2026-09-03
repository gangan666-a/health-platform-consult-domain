package com.example.mall.agentservice.service;

import com.example.mall.agentservice.dto.ReportCompareResponse;
import com.example.mall.agentservice.dto.ReportUploadResponse;
import com.example.mall.agentservice.model.ReportRecord;
import com.example.mall.agentservice.repository.HealthProfileRepository;
import com.example.mall.agentservice.repository.ReportIndicatorRecordRepository;
import com.example.mall.agentservice.repository.ReportRecordRepository;
import com.example.mall.agentservice.report.PdfBoxToolTest;
import com.example.mall.agentservice.report.ReportParserTest;
import com.example.mall.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告上传全链路（R-5/T-1/T-7）+ 同指标对比（R-6/T-4/T-5）+ 合规 fail-closed（E-1/E-2）。
 * 测试用 H2 内存库 + 占位 API Key（不真调 LLM，走占位解读）。
 */
@SpringBootTest
class ReportUploadServiceTest {

    @Autowired
    ReportUploadService uploadService;
    @Autowired
    ReportCompareService compareService;
    @Autowired
    ReportRecordRepository recordRepository;
    @Autowired
    ReportIndicatorRecordRepository indicatorRepository;
    @Autowired
    HealthProfileRepository profileRepository;

    private MockMultipartFile labReport(String date, String altValue) {
        // 多模态架构下，文本报告经 PDF 文本层进入解析（PDF 标准字体不支持中文，报告名/日期行用 ASCII）
        String text = "#xuechanggui\nDate: " + date + "\n"
                + "WBC 6.5 10^9/L 3.5-9.5\n"
                + "RBC 4.8 10^12/L 3.8-5.8\n"
                + "ALT " + altValue + " U/L 7-40\n";
        return new MockMultipartFile("file", "血常规.pdf", "application/pdf",
                PdfBoxToolTest.pdfWithLine(text));
    }

    @Test
    void upload_persistsReportIndicatorsAndSummary_andReturnsInterpretation() {
        ReportUploadResponse resp = uploadService.upload(1001L, "sess-1", labReport("2026-08-01", "32"), true);

        assertNotNull(resp.reportId());
        assertEquals("xuechanggui", resp.examName());
        assertFalse(resp.indicators().isEmpty());
        assertEquals("正常", resp.indicators().get(0).status());
        assertNotNull(resp.summary());
        assertNotNull(resp.interpretation());
        assertFalse(resp.interpretation().isBlank());
        assertNotNull(resp.disclaimer());
        assertTrue(resp.disclaimer().contains("仅供参考"));
        // ALT 32 正常 → 无异常 handoff 建议
        assertTrue(resp.suggestedHandoffs().isEmpty());

        assertEquals(1, recordRepository.findByUserIdOrderByCreatedAtDesc(1001L).size());
        assertEquals(1, indicatorRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc(1001L, "WBC").size());
        assertTrue(profileRepository.findByUserId(1001L).isPresent());
        assertNotNull(profileRepository.findByUserId(1001L).get().getProfileSummary());
    }

    @Test
    void upload_abnormalIndicator_suggestsConsultationHandoff() {
        ReportUploadResponse resp = uploadService.upload(1002L, null, labReport("2026-08-02", "60"), true);
        // ALT 60 超出 7-40 → HIGH → 建议问诊
        assertTrue(resp.suggestedHandoffs().contains("CONSULTATION"));
    }

    @Test
    void upload_withoutConsent_failsClosed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.upload(1003L, null, labReport("2026-08-03", "32"), false));
        assertTrue(ex.getMessage().contains("同意"));
    }

    @Test
    void upload_invalidFileExtension_rejected() {
        MockMultipartFile bad = new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[]{1});
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.upload(1004L, null, bad, true));
        assertTrue(ex.getMessage().contains("不支持的文件格式"), ex.getMessage());
    }

    @Test
    void upload_unknownMagicContent_failsClosed() {
        // 扩展名 .jpg 合法但内容为文本：魔数校验拦下，不让解析器/VLM 拿到未知字节
        MockMultipartFile textAsImage = new MockMultipartFile("file", "x.jpg", "image/jpeg",
                "不是图片的纯文本".getBytes());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.upload(1013L, null, textAsImage, true));
        assertTrue(ex.getMessage().contains("无法识别文件内容格式"), ex.getMessage());
    }

    @Test
    void upload_heicContentRenamedToJpg_rejectedWithConversionHint() {
        // 伪装成 .jpg 的 HEIC 头：魔数识别为 heic → 定向拒绝并提示转 JPEG/PNG（fail-closed，不落库）
        byte[] fakeHeic = new byte[12];
        "ftyp".getBytes(0, 4, fakeHeic, 4);
        "heic".getBytes(0, 4, fakeHeic, 8);
        MockMultipartFile renamed = new MockMultipartFile("file", "report.jpg", "image/jpeg", fakeHeic);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.upload(1014L, null, renamed, true));
        assertTrue(ex.getMessage().contains("HEIC"), ex.getMessage());
        assertTrue(indicatorRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc(1014L, "WBC").isEmpty(),
                "fail-closed：不落库");
    }

    @Test
    void upload_imageWithoutVision_failsClosed_noFabrication() {
        // E-3：无文本层图片件在视觉模型未接入时不虚构数据，明确拒绝且不落库
        MockMultipartFile image = new MockMultipartFile("file", "xuechanggui.png", "image/png",
                ReportParserTest.tinyPng());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.upload(1006L, null, image, true));

        assertTrue(ex.getMessage().contains("未能自动识别"));
        assertTrue(indicatorRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc(1006L, "WBC").isEmpty());
    }

    @Test
    void upload_criticalValue_returnsForcedAlert() {
        // 防线3：血糖危急值 → 响应必带强制就医提醒 + 建议问诊 handoff
        String text = "#shenghua\nGLU 35.2 mmol/L 3.9-6.1\n";
        MockMultipartFile report = new MockMultipartFile("file", "shenghua.pdf", "application/pdf",
                PdfBoxToolTest.pdfWithLine(text));

        ReportUploadResponse resp = uploadService.upload(1007L, null, report, true);

        assertFalse(resp.criticalAlerts().isEmpty());
        assertTrue(resp.criticalAlerts().get(0).contains("GLU"));
        assertTrue(resp.suggestedHandoffs().contains("CONSULTATION"));
        assertFalse(resp.parseTrace().isEmpty());
    }

    @Test
    void upload_sameFileTwice_idempotentReplay() {
        byte[] pdf = PdfBoxToolTest.pdfWithLine("#xuechanggui\nWBC 6.5 10^9/L 3.5-9.5\nHGB 138 g/L 115-150");

        ReportUploadResponse first = uploadService.upload(1010L, null,
                new MockMultipartFile("file", "a.pdf", "application/pdf", pdf), true);
        ReportUploadResponse second = uploadService.upload(1010L, null,
                new MockMultipartFile("file", "a.pdf", "application/pdf", pdf), true);

        assertEquals(first.reportId(), second.reportId(), "同文件重复上传回放同一报告");
        assertEquals(first.indicators(), second.indicators(), "快照回放内容一致");
        assertEquals(1, recordRepository.findByUserIdOrderByCreatedAtDesc(1010L).size(),
                "不产生重复落库");
    }

    @Test
    void upload_unitConverted_beforePersistAndCompare() throws Exception {
        // 单位归一：GLU 100 mg/dL → 5.55 mmol/L（范围联动 70-110 → 3.88-6.10）后落库
        String text = "#shenghua\nGLU 100 mg/dL 70-110\n";
        ReportUploadResponse resp = uploadService.upload(1011L, null,
                new MockMultipartFile("file", "u.pdf", "application/pdf", PdfBoxToolTest.pdfWithLine(text)), true);

        assertEquals("5.55", resp.indicators().get(0).value());
        assertEquals("mmol/L", resp.indicators().get(0).unit());
        assertEquals("3.88-6.10", resp.indicators().get(0).refRange());
    }

    @Test
    void deleteReport_removesRowsAndFile() {
        byte[] pdf = PdfBoxToolTest.pdfWithLine("#xuechanggui\nWBC 6.5 10^9/L 3.5-9.5");
        ReportUploadResponse resp = uploadService.upload(1012L, null,
                new MockMultipartFile("file", "d.pdf", "application/pdf", pdf), true);

        uploadService.deleteReport(1012L, resp.reportId());

        assertTrue(recordRepository.findByIdAndUserId(resp.reportId(), 1012L).isEmpty(), "被遗忘权：记录删除");
        assertTrue(indicatorRepository.findByReportId(resp.reportId()).isEmpty(), "指标明细连带删除");
    }

    @Test
    void compare_sameIndicatorMultipleDates_returnsChronologicalEntries() {
        uploadService.upload(1005L, null, labReport("2026-06-01", "25"), true);
        uploadService.upload(1005L, null, labReport("2026-08-01", "32"), true);

        ReportCompareResponse resp = compareService.compare(1005L, "WBC");
        assertEquals("WBC", resp.indicatorName());
        assertEquals(2, resp.entries().size());
        assertEquals(LocalDate.of(2026, 6, 1), resp.entries().get(0).examDate());
        assertEquals(LocalDate.of(2026, 8, 1), resp.entries().get(1).examDate());
        assertNotNull(resp.note());
    }

    @Test
    void compare_unknownUserOrIndicator_failsClosed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> compareService.compare(9999L, "WBC"));
        assertTrue(ex.getMessage().contains("无该指标"));
    }
}
