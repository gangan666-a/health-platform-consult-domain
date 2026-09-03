package com.example.mall.agentservice.report;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.example.mall.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 报告前置 Tool 2（EXIF 读取 + Java2D 像素归一化）：
 * ① metadata-extractor 读 EXIF：拍摄时间（DateTimeOriginal）兜底照片件报告日期，Orientation 标签判定方向；
 * ② JDK 自带 Java2D 按方向标签旋转/翻转像素至正确朝向，并降采样到 max-dimension 内（控制多模态 LLM 输入体积）；
 *    归一化后重编码，顺带剥离原始 EXIF（含 GPS）：方向已烙进像素，避免下游二次旋转，兼得隐私剥离。
 * 格式归一：JDK ImageIO + twelve-monkeys 解码 png/jpeg/webp，非 JPEG/PNG 源一律重编码为 JPEG/PNG
 * （VLM 白名单），杜绝 webp 裸字节以错误 MIME 静默送达 VLM。
 * 隐私红线：不读取 GPS 坐标。无法解码的图片内容 fail-closed 抛 BusinessException（改版前为透传裸字节，属静默失败）。
 */
@Component
public class ExifTool {

    private static final Logger log = LoggerFactory.getLogger(ExifTool.class);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    /** EXIF 关键信息：拍摄日期 + 拍摄设备。captureDate 为空表示无可用的拍摄时间。 */
    public record ExifInfo(LocalDate captureDate, String camera) {

        public static final ExifInfo EMPTY = new ExifInfo(null, "");

        public boolean isEmpty() {
            return captureDate == null && (camera == null || camera.isBlank());
        }
    }

    /**
     * 归一化产物：content 为矫正/降采样后的像素（未变更时为原字节），
     * orientation 为读取到的 EXIF 方向标签（1-8，无 EXIF 时为 1），transformed 表示像素是否被重写。
     */
    public record NormalizedImage(byte[] content, LocalDate captureDate, int orientation, boolean transformed) {
    }

    private final int maxDimension;

    public ExifTool(@Value("${agent.report.image.max-dimension:2048}") int maxDimension) {
        this.maxDimension = maxDimension;
    }

    public boolean isImage(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf('.');
        return idx >= 0 && IMAGE_EXTENSIONS.contains(lower.substring(idx + 1));
    }

    /** 仅读取元数据（不处理像素）；非图片/无 EXIF/损坏返回 EMPTY。 */
    public ExifInfo extract(byte[] content) {
        if (content == null || content.length == 0) {
            return ExifInfo.EMPTY;
        }
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            return new ExifInfo(readCaptureDate(metadata), readCamera(metadata));
        } catch (Exception e) {
            log.debug("EXIF 读取失败，按无元数据处理: {}", e.getMessage());
            return ExifInfo.EMPTY;
        }
    }

    /** 像素归一化：按 Orientation 矫正方向 + 降采样 + 重编码；
     *  JPEG/PNG 且像素未变 → 原样透传；webp 等非 JPEG/PNG 源一律归一为 JPEG/PNG；
     *  无法解码的图片内容 fail-closed 拒绝（不把裸字节交给下游 VLM）。 */
    public NormalizedImage normalize(byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            return new NormalizedImage(content, null, 1, false);
        }
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            int orientation = readOrientation(metadata);
            LocalDate captureDate = readCaptureDate(metadata);

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) { // 解码器仍无法解码（如扩展名伪装/个别编码档）——拒绝而非透传裸字节给 VLM
                throw new BusinessException("无法解析图片内容，请转换为 JPEG/PNG 后重传");
            }
            String srcFormat = FormatSniffer.detect(content);
            BufferedImage normalized = downscale(applyOrientation(image, orientation), maxDimension);
            boolean keepOriginal = (FormatSniffer.isJpeg(srcFormat) || FormatSniffer.isPng(srcFormat))
                    && normalized == image;
            if (keepOriginal) {
                return new NormalizedImage(content, captureDate, orientation, false);
            }
            byte[] encoded = encode(normalized, usePng(normalized, srcFormat));
            return new NormalizedImage(encoded, captureDate, orientation, true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("图像归一化失败，拒绝处理: {}", e.getMessage());
            throw new BusinessException("无法解析图片内容，请转换为 JPEG/PNG 后重传");
        }
    }

    /** 按 EXIF Orientation 旋转/翻转像素；orientation=1 或未知返回原图实例。 */
    static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        AffineTransform transform = orientationTransform(orientation, image.getWidth(), image.getHeight());
        if (transform == null) {
            return image;
        }
        // 不用 AffineTransformOp 自建 dest（其颜色模型常无法被 JPEG 编码器识别），显式绘制到标准类型图
        boolean swapped = orientation >= 5; // 5-8 宽高互换（2/3/4 仅镜像/倒转）
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage dest = new BufferedImage(swapped ? h : w, swapped ? w : h, compatibleType(image));
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR), 0, 0);
        g.dispose();
        return dest;
    }

    private static int compatibleType(BufferedImage image) {
        if (image.getColorModel().hasAlpha()) {
            return BufferedImage.TYPE_INT_ARGB;
        }
        return image.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : image.getType();
    }

    /** EXIF Orientation 1-8 → Java2D 仿射矩阵（x' = m00·x + m01·y + m02）；1/未知返回 null 表示无需变换。 */
    static AffineTransform orientationTransform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // 水平镜像
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // 旋转 180°
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // 垂直镜像
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // 转置（沿主对角线翻转）
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // 旋转 90° 顺时针
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // 反转置（沿副对角线翻转）
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // 旋转 90° 逆时针
            default -> null;
        };
    }

    /** 等比降采样：最长边不超过 maxDimension；无需缩放返回原图实例。 */
    static BufferedImage downscale(BufferedImage image, int maxDimension) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= maxDimension && h <= maxDimension) {
            return image;
        }
        double scale = Math.min((double) maxDimension / w, (double) maxDimension / h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh,
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** Orientation 标签：1-8 之外一律按 1（不旋转）处理。 */
    private static int readOrientation(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 == null || !ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return 1;
        }
        Integer orientation = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        return orientation == null || orientation < 2 || orientation > 8 ? 1 : orientation;
    }

    /** 拍摄时间 DateTimeOriginal；部分设备只写 ModifyDate，依次兜底。 */
    private static LocalDate readCaptureDate(Metadata metadata) {
        ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (sub != null) {
            Date date = sub.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (date == null) {
                date = sub.getDate(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
            }
            if (date != null) {
                return toLocalDate(date);
            }
        }
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            Date date = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
            if (date != null) {
                return toLocalDate(date);
            }
        }
        return null;
    }

    private static String readCamera(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 == null) {
            return "";
        }
        String make = ifd0.getString(ExifIFD0Directory.TAG_MAKE);
        String model = ifd0.getString(ExifIFD0Directory.TAG_MODEL);
        String camera = ((make == null ? "" : make.trim()) + " " + (model == null ? "" : model.trim())).trim();
        return camera.isBlank() ? "" : camera;
    }

    /** 输出格式决策：源为 PNG 或图像带透明度（webp 无损/PNG）→ PNG，否则 JPEG（VLM 白名单）。 */
    private static boolean usePng(BufferedImage image, String srcFormat) {
        return FormatSniffer.isPng(srcFormat) || image.getColorModel().hasAlpha();
    }

    /** PNG 保透明度用原格式重编码；其余（JPEG 照片/转码的 webp、heic）转 JPEG。 */
    private static byte[] encode(BufferedImage image, boolean png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, png ? "png" : "jpg", out)) {
            throw new IOException("无可用 ImageIO 编码器");
        }
        return out.toByteArray();
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
