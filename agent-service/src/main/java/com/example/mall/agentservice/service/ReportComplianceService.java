package com.example.mall.agentservice.service;

import com.example.mall.agentservice.report.FormatSniffer;
import com.example.mall.agentservice.report.PromptGuard;
import com.example.mall.agentservice.report.ReportDoc;
import com.example.mall.agentservice.report.ReportFinding;
import com.example.mall.agentservice.report.ReportIndicator;
import com.example.mall.agentservice.storage.ReportFileStorage;
import com.example.mall.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 合规服务（合规红线）：授权 fail-closed、文件白名单/大小、内容魔数门禁 + HEIC 转码或拒绝、脱敏、数据最小化。
 * 隐私红线：报告数据不用于 LLM 训练（buildLlmInput 只含必要结构化指标，剥离 PII）。
 */
@Service
public class ReportComplianceService {

    private static final long MAX_SIZE = 10L * 1024 * 1024; // 10MB
    // 扩展名白名单：webp 由 twelve-monkeys 解码后归一为 JPEG/PNG（见 ExifTool.normalize）；
    // heic/heif 接受扩展名但内容必须过魔数门禁——真实 HEIC 由存储转码（OSS）或定向拒绝，伪装扩展名的其他内容照样拦截
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "webp", "heic", "heif");
    private static final String FORMAT_HINT = "仅支持 PDF/JPEG/PNG/WebP 报告（iPhone 照片 HEIC 将自动转码为 JPEG）";
    private static final String HEIC_REJECT = "检测到 HEIC/HEIF 图片格式（iPhone 默认拍照格式），当前环境无法转码，请先转换为 JPEG/PNG 后重传";
    private static final List<String> PII_KEYWORDS = List.of("姓名", "身份证", "证件号", "医院", "手机", "电话", "住址", "卡号");
    private static final Pattern ID_CARD =
            Pattern.compile("\\b[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b");

    private final ReportFileStorage fileStorage;

    public ReportComplianceService(ReportFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** E-1：consent=false 拒绝上传（fail-closed，默认拒绝）。 */
    public void requireConsent(boolean consent) {
        if (!consent) {
            throw new BusinessException("需先同意报告存储与解读协议");
        }
    }

    /** E-2：白名单 + ≤10MB + 非空；返回清洗后的文件名（错误消息区分空/超限/格式，提示用户重传）。 */
    public String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件为空，请重新上传");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("文件超过 10MB 上限，请压缩后重传");
        }
        String name = file.getOriginalFilename();
        String ext = extensionOf(name);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的文件格式 ." + ext + "，" + FORMAT_HINT);
        }
        return name == null ? "report" : name;
    }

    /** E-2 内容准备（fail-closed 纵深防御，同步与异步上传共用）：不信任扩展名/Content-Type，按魔数识别。
     *  HEIC/HEIF → 存储具备转码能力（OSS）时转 JPEG 供解析，否则定向拒绝并提示；
     *  识别不出（GIF/BMP/文本等）即拒。返回解析输入（可能为转码后的 JPEG）与规范格式。 */
    public PreparedContent prepareContent(byte[] content) {
        String format = FormatSniffer.detect(content);
        if (FormatSniffer.isHeic(format)) {
            byte[] converted = fileStorage.transcodeHeic(content).orElse(null);
            if (converted == null) {
                throw new BusinessException(HEIC_REJECT);
            }
            return new PreparedContent(converted, FormatSniffer.JPEG);
        }
        if (format == null) {
            throw new BusinessException("无法识别文件内容格式，" + FORMAT_HINT + "，请转换后重传");
        }
        return new PreparedContent(content, format);
    }

    /** 内容准备产物：content 为解析输入（HEIC 转码后为 JPEG），format 为魔数识别的规范格式。 */
    public record PreparedContent(byte[] content, String format) {
    }

    /** 脱敏：掩码身份证号，剔除含姓名/证件号/医院等 PII 行（防御性，原始文本不入库不进 prompt）。 */
    public String desensitize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        return rawText.lines()
                .map(line -> ID_CARD.matcher(line).replaceAll("********"))
                .filter(line -> PII_KEYWORDS.stream().noneMatch(line::contains))
                .collect(Collectors.joining("\n"));
    }

    /** B-6 数据最小化 + Prompt 注入隔离：指标名/值经 scrub、检查所见包进数据围栏后进入 prompt。 */
    public String buildLlmInput(ReportDoc doc, List<ReportFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("检查项目：").append(PromptGuard.scrub(doc.examName()));
        sb.append("；检查日期：").append(doc.reportDate()).append('\n');
        for (ReportIndicator in : doc.indicators()) {
            sb.append("- ").append(PromptGuard.scrub(in.name())).append(": ").append(PromptGuard.scrub(in.value()));
            if (in.unit() != null && !in.unit().isBlank()) {
                sb.append(' ').append(in.unit());
            }
            sb.append("，参考范围 ").append(nullToDash(in.refRange()));
            sb.append("，结果 ").append(in.status().display()).append('\n');
        }
        if (findings != null && !findings.isEmpty()) {
            StringBuilder findingBlock = new StringBuilder();
            for (ReportFinding finding : findings) {
                findingBlock.append("- [").append(PromptGuard.scrub(finding.section())).append("] ")
                        .append(finding.text()).append('\n');
            }
            sb.append('\n').append(PromptGuard.fence(findingBlock.toString()));
        }
        return sb.toString();
    }

    private static String extensionOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
