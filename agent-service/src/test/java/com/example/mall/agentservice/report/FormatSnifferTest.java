package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 格式魔数嗅探（E-2 内容级门禁 + 解析路由共用）：扩展名可伪造，魔数是字节事实。
 */
class FormatSnifferTest {

    @Test
    void detect_recognizesAllowedFormatsByMagic() {
        assertEquals(FormatSniffer.PDF, FormatSniffer.detect("%PDF-1.4\n...".getBytes()));
        assertEquals(FormatSniffer.JPEG, FormatSniffer.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals(FormatSniffer.PNG, FormatSniffer.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D}));
        byte[] webp = new byte[12];
        "RIFF".getBytes(0, 4, webp, 0);
        "WEBP".getBytes(0, 4, webp, 8);
        assertEquals(FormatSniffer.WEBP, FormatSniffer.detect(webp));
    }

    @Test
    void detect_heicBrand_recognized() {
        byte[] heic = new byte[12];
        "ftyp".getBytes(0, 4, heic, 4);
        "heic".getBytes(0, 4, heic, 8);
        assertEquals(FormatSniffer.HEIC, FormatSniffer.detect(heic));
        // 其他 HEVC 系品牌同属 HEIC 家族
        byte[] heix = new byte[12];
        "ftyp".getBytes(0, 4, heix, 4);
        "heix".getBytes(0, 4, heix, 8);
        assertEquals(FormatSniffer.HEIC, FormatSniffer.detect(heix));
    }

    @Test
    void detect_avifOrUnknown_returnsNull() {
        // AVIF（major brand avif）不支持：识别不出 → 门禁拒绝，不静默进 VLM
        byte[] avif = new byte[12];
        "ftyp".getBytes(0, 4, avif, 4);
        "avif".getBytes(0, 4, avif, 8);
        assertNull(FormatSniffer.detect(avif));
        // GIF/BMP/TIFF/纯文本/随机字节均无法识别
        assertNull(FormatSniffer.detect("GIF89a...".getBytes()));
        assertNull(FormatSniffer.detect("not an image at all".getBytes()));
        assertNull(FormatSniffer.detect(new byte[]{1, 2, 3}));
        assertNull(FormatSniffer.detect(null));
    }

    @Test
    void classifiers_matchFamily() {
        assertTrue(FormatSniffer.isPdf("pdf"));
        assertTrue(FormatSniffer.isImage("heic"));
        assertTrue(FormatSniffer.isImage("webp"));
        assertTrue(FormatSniffer.isJpeg("jpeg"));
        assertTrue(FormatSniffer.isPng("png"));
        assertTrue(FormatSniffer.isHeic("heic"));
        assertFalse(FormatSniffer.isHeic("jpeg"));
        assertFalse(FormatSniffer.isHeic(null));
        assertFalse(FormatSniffer.isImage("pdf"));
        assertFalse(FormatSniffer.isImage(null));
    }
}
