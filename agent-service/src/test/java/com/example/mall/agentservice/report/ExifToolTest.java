package com.example.mall.agentservice.report;

import com.example.mall.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXIF 前置 Tool：元数据读取 + Java2D 方向矫正/降采样 + 格式归一（webp/heic 转 JPEG/PNG）+ fail-closed。
 * 隐私红线：不读取 GPS；端到端用例通过手写 APP1 EXIF 段构造带 Orientation 的真实 JPEG。
 */
class ExifToolTest {

    private final ExifTool tool = new ExifTool(2048);

    // ---------- 元数据读取 ----------

    @Test
    void extract_nonImageOrGarbage_returnsEmpty() {
        assertTrue(tool.extract(new byte[]{1, 2, 3}).isEmpty());
        assertTrue(tool.extract("纯文本不是图片".getBytes()).isEmpty());
        assertTrue(tool.extract(null).isEmpty());
    }

    @Test
    void extract_emptyResultHasNoCaptureDate() {
        ExifTool.ExifInfo info = tool.extract(new byte[]{1, 2, 3});
        assertNull(info.captureDate());
        assertEquals("", info.camera());
    }

    @Test
    void isImage_detectsByContentTypeOrExtension() {
        assertTrue(tool.isImage("photo.jpg", null));
        assertTrue(tool.isImage("photo", "image/jpeg"));
        assertTrue(tool.isImage("photo.PNG", "application/octet-stream"));
        assertTrue(tool.isImage("report.webp", null));
        assertFalse(tool.isImage("report.pdf", "application/pdf"));
        assertFalse(tool.isImage(null, null));
    }

    // ---------- 方向矫正（端到端：真实 JPEG + EXIF Orientation） ----------

    @Test
    void normalize_orientation6_rotates90Clockwise() throws Exception {
        // 横拍照片（30x20，红块在左上角），EXIF Orientation=6 → 需旋转 90° CW 矫正
        byte[] jpeg = jpegWithOrientation(markerImage(30, 20), 6);

        ExifTool.NormalizedImage out = tool.normalize(jpeg, "photo.jpg", "image/jpeg");

        assertTrue(out.transformed());
        assertEquals(6, out.orientation());
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(out.content()));
        assertEquals(20, result.getWidth());
        assertEquals(30, result.getHeight());
        // 原左上红块 → 旋转 90° CW 后落在右上角
        Color rotated = new Color(result.getRGB(result.getWidth() - 3, 3));
        assertTrue(rotated.getRed() > 150 && rotated.getGreen() < 120 && rotated.getBlue() < 120,
                "右上角应为红色块，实际 " + rotated);
    }

    @Test
    void normalize_orientation1_keepsPixelsUnchanged() throws Exception {
        byte[] jpeg = jpegWithOrientation(markerImage(30, 20), 1);

        ExifTool.NormalizedImage out = tool.normalize(jpeg, "photo.jpg", "image/jpeg");

        assertFalse(out.transformed());
        assertEquals(1, out.orientation());
    }

    @Test
    void normalize_noExif_passesThrough() throws Exception {
        byte[] jpeg = jpegWithOrientation(markerImage(30, 20), 0);

        ExifTool.NormalizedImage out = tool.normalize(jpeg, "photo.jpg", "image/jpeg");

        assertFalse(out.transformed());
        assertEquals(1, out.orientation());
    }

    @Test
    void applyOrientation_rotates180() {
        BufferedImage out = ExifTool.applyOrientation(markerImage(20, 10), 3);

        assertEquals(20, out.getWidth());
        assertEquals(10, out.getHeight());
        // 原左上红块 → 旋转 180° 后落在右下角
        Color rotated = new Color(out.getRGB(17, 7));
        assertTrue(rotated.getRed() > 150, "右下角应为红色块，实际 " + rotated);
    }

    // ---------- 降采样 ----------

    @Test
    void normalize_downscalesToMaxDimension() throws Exception {
        ExifTool small = new ExifTool(100);
        BufferedImage big = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        byte[] png = pngBytes(big);

        ExifTool.NormalizedImage out = small.normalize(png, "report.png", "image/png");

        assertTrue(out.transformed());
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(out.content()));
        assertEquals(100, result.getWidth());
        assertEquals(50, result.getHeight());
    }

    @Test
    void normalize_withinMaxDimension_untouched() throws Exception {
        byte[] png = pngBytes(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB));

        ExifTool.NormalizedImage out = tool.normalize(png, "report.png", "image/png");

        assertFalse(out.transformed());
    }

    // ---------- 格式归一 + fail-closed ----------

    @Test
    void normalize_nonJpegPng_transcodesToJpeg() throws Exception {
        // BMP 可被 JDK 解码且非 JPEG/PNG（与 webp/heic 走同一「非 JPEG/PNG 源 → 重编码」分支）
        byte[] bmp = imageBytes(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "bmp");

        ExifTool.NormalizedImage out = tool.normalize(bmp, "report.bmp", "image/bmp");

        assertTrue(out.transformed(), "非 JPEG/PNG 源必须归一为 JPEG/PNG，而非透传裸字节");
        byte[] content = out.content();
        assertTrue((content[0] & 0xFF) == 0xFF && content[1] == (byte) 0xD8, "归一化产物应为 JPEG 魔数");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(content));
        assertNotNull(result);
        assertEquals(30, result.getWidth());
        assertEquals(20, result.getHeight());
    }

    @Test
    void webpReader_isRegisteredByTwelveMonkeys() {
        // imageio-webp 只带 reader（无 writer）：确保解码 SPI 已装载，webp 不再落"解码返回 null → 透传"分支
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext(),
                "webp 读取器应由 twelve-monkeys imageio-webp 提供");
    }

    @Test
    void normalize_undecodableContent_failsClosed() {
        byte[] garbage = {1, 2, 3, 4};
        assertThrows(BusinessException.class, () -> tool.normalize(garbage, "a.jpg", "image/jpeg"));
    }

    @Test
    void normalize_nullOrEmpty_returnsOriginal() {
        ExifTool.NormalizedImage out = tool.normalize(null, "a.jpg", "image/jpeg");
        assertFalse(out.transformed());
        assertNull(out.captureDate());
    }

    // ---------- 测试夹具 ----------

    /** 白底图，左上角画 6x6 红块（JPEG 有损压缩下仍可辨认色块位置）。 */
    private static BufferedImage markerImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 6, 6);
        g.dispose();
        return img;
    }

    /** 生成 JPEG；orientation 1-8 时在 SOI 后手工插入 APP1 EXIF 段（ImageIO 元数据树不透传 APP1），0 表示不写。 */
    static byte[] jpegWithOrientation(BufferedImage image, int orientation) throws Exception {
        ByteArrayOutputStream base = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", base);
        byte[] jpeg = base.toByteArray();
        if (orientation < 1) {
            return jpeg;
        }
        byte[] payload = exifOrientationPayload(orientation);
        int segLen = payload.length + 2; // 段长度含长度字段自身
        byte[] out = new byte[jpeg.length + 2 + segLen];
        out[0] = jpeg[0];
        out[1] = jpeg[1]; // SOI (FFD8)
        out[2] = (byte) 0xFF;
        out[3] = (byte) 0xE1; // APP1
        out[4] = (byte) (segLen >> 8);
        out[5] = (byte) segLen;
        System.arraycopy(payload, 0, out, 6, payload.length);
        System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
        return out;
    }

    /** 最小 TIFF：IFD0 仅一个 SHORT 型 Orientation 条目（26 字节）。 */
    private static byte[] exifOrientationPayload(int orientation) {
        byte[] tiff = new byte[26];
        tiff[0] = 'I';
        tiff[1] = 'I';
        tiff[2] = 0x2A; // TIFF 魔数 42（LE）
        tiff[4] = 8;    // 首个 IFD 偏移 = 8
        tiff[8] = 1;    // IFD 条目数 = 1
        tiff[10] = 0x12;
        tiff[11] = 0x01; // tag 0x0112 Orientation
        tiff[12] = 3;    // 类型 SHORT
        tiff[14] = 1;    // count = 1
        tiff[18] = (byte) orientation; // 值（LE SHORT）
        byte[] payload = new byte[6 + tiff.length]; // "Exif\0\0" + TIFF
        System.arraycopy("Exif".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        return payload;
    }

    private static byte[] pngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] imageBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IllegalStateException(format + " 编码器不可用");
        }
        return out.toByteArray();
    }
}
