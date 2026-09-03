package com.example.mall.agentservice.report;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 文件格式魔数嗅探（fail-closed 门禁共用）：
 * 按字节内容识别 pdf/jpeg/png/webp/heic，不信任扩展名/Content-Type（可伪造）。
 * 上传校验（ReportComplianceService.prepareContent）与解析路由（ReportParser）共用同一事实源，
 * 保证「识别出的格式」与「实际解码器」一致，杜绝未知格式字节静默送达 VLM。
 * 私有性：不读取任何正文，仅头部魔数。
 */
public final class FormatSniffer {

    public static final String PDF = "pdf";
    public static final String JPEG = "jpeg";
    public static final String PNG = "png";
    public static final String WEBP = "webp";
    public static final String HEIC = "heic";

    private static final Set<String> IMAGE_FORMATS = Set.of(JPEG, PNG, WEBP, HEIC);

    private FormatSniffer() {
    }

    /** 内容魔数识别：pdf/jpeg/png/webp/heic，或 null（无法识别，如 GIF/BMP/TIFF/AVIF/文本）。 */
    public static String detect(byte[] content) {
        if (content == null || content.length < 4) {
            return null;
        }
        int b0 = content[0] & 0xFF;
        int b1 = content[1] & 0xFF;
        int b2 = content[2] & 0xFF;
        int b3 = content[3] & 0xFF;
        // PDF: %PDF
        if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') {
            return PDF;
        }
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') {
            return PNG;
        }
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return JPEG;
        }
        // WebP: RIFF .... WEBP
        if (content.length >= 12 && b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return WEBP;
        }
        // HEIC/HEIF: ISO-BMFF ftyp box，major brand 限 HEVC 系（iPhone/安卓手机默认拍摄格式）
        if (isHeicBrand(content)) {
            return HEIC;
        }
        return null;
    }

    public static boolean isPdf(String format) {
        return PDF.equals(format);
    }

    public static boolean isImage(String format) {
        return format != null && IMAGE_FORMATS.contains(format);
    }

    public static boolean isJpeg(String format) {
        return JPEG.equals(format);
    }

    public static boolean isHeic(String format) {
        return HEIC.equals(format);
    }

    public static boolean isPng(String format) {
        return PNG.equals(format);
    }

    private static boolean isHeicBrand(byte[] content) {
        if (content.length < 12 || content[4] != 'f' || content[5] != 't' || content[6] != 'y' || content[7] != 'p') {
            return false;
        }
        String brand = new String(content, 8, 4, StandardCharsets.US_ASCII);
        return switch (brand) {
            case "heic", "heix", "hevc", "hevx", "heif" -> true;
            default -> false;
        };
    }
}
