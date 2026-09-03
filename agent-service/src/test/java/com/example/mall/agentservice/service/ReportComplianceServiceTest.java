package com.example.mall.agentservice.service;

import com.example.mall.agentservice.report.FormatSniffer;
import com.example.mall.agentservice.storage.ReportFileStorage;
import com.example.mall.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容门禁 prepareContent（E-2 纵深防御 + HEIC 转码或定向拒绝）：
 * 魔数是字节事实、扩展名可伪造；HEIC 在存储具备转码能力（OSS）时转 JPEG 供解析，否则拒绝并提示转码。
 */
class ReportComplianceServiceTest {

    /** 伪装成 .jpg 的 HEIC 头（ftyp + heic brand），与上传门禁的伪造用例一致。 */
    private static byte[] heicMagic() {
        byte[] heic = new byte[12];
        "ftyp".getBytes(0, 4, heic, 4);
        "heic".getBytes(0, 4, heic, 8);
        return heic;
    }

    private static byte[] jpegMagic() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    }

    /** 存储桩：返回给定转码产物；null 表示不支持转码（等价 LocalReportFileStorage）。 */
    private static ReportFileStorage storageTranscodingTo(byte[] converted) {
        return new ReportFileStorage() {
            @Override
            public StoredFile store(Long userId, String fileHash, byte[] content, String fileName, String contentType) {
                return null;
            }

            @Override
            public byte[] load(String key) {
                return null;
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public Optional<byte[]> transcodeHeic(byte[] content) {
                return converted == null ? Optional.empty() : Optional.of(converted);
            }

            @Override
            public String type() {
                return "stub";
            }
        };
    }

    @Test
    void heic_withTranscodeCapability_returnsJpegForParse() {
        byte[] jpeg = jpegMagic();
        ReportComplianceService compliance = new ReportComplianceService(storageTranscodingTo(jpeg));

        ReportComplianceService.PreparedContent prepared = compliance.prepareContent(heicMagic());

        assertArrayEquals(jpeg, prepared.content(), "解析输入应为转码后的 JPEG，而非原始 HEIC 裸字节");
        assertEquals(FormatSniffer.JPEG, prepared.format());
    }

    @Test
    void heic_withoutTranscodeCapability_failsClosedWithHint() {
        ReportComplianceService compliance = new ReportComplianceService(storageTranscodingTo(null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> compliance.prepareContent(heicMagic()));

        assertTrue(ex.getMessage().contains("HEIC"), ex.getMessage());
        assertTrue(ex.getMessage().contains("JPEG/PNG"), ex.getMessage());
    }

    @Test
    void unknownContent_failsClosed() {
        ReportComplianceService compliance = new ReportComplianceService(storageTranscodingTo(null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> compliance.prepareContent("不是图片的纯文本".getBytes(StandardCharsets.UTF_8)));

        assertTrue(ex.getMessage().contains("无法识别文件内容格式"), ex.getMessage());
    }

    @Test
    void jpegContent_passesThrough() {
        byte[] jpeg = jpegMagic();
        ReportComplianceService compliance = new ReportComplianceService(storageTranscodingTo(null));

        ReportComplianceService.PreparedContent prepared = compliance.prepareContent(jpeg);

        assertArrayEquals(jpeg, prepared.content());
        assertEquals(FormatSniffer.JPEG, prepared.format());
    }
}
