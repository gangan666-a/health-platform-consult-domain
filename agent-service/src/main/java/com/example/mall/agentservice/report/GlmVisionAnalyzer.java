package com.example.mall.agentservice.report;

import com.example.mall.agentservice.config.VisionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 视觉通道真实实现（GLM-4.6V 系列，OpenAI 兼容协议）：图片/扫描页一次调用完成
 * 「页型判定 + 按页型抽取」，输出结构化结果（页型自分类，关键词分类仅作提示）。
 * 分层策略（面试要点）：
 * ① 免费档 glm-4.6v-flash 为主力，信号量对齐平台并发额度（如 2 并发）；
 * ② 并发溢出的请求直接升级付费档 glm-4.6v（保吞吐，成本可控：¥1/¥3 每百万）；
 * ③ 免费档调用失败/输出不可解析 → 升级付费档重试一次；
 * ④ 质量门禁（VisionEvalTest，字段级 recall ≥ 0.90）不达标 → 运维切换主力模型为付费档。
 * 未配置 key 时保持 Mock 语义：明确未识别，绝不虚构（E-3/B-7 纪律）。
 */
public class GlmVisionAnalyzer implements VisionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GlmVisionAnalyzer.class);

    private static final String SYSTEM_PROMPT = """
            你是医疗报告页面解析器（视觉通道）。输入是一张报告页面图片，任务分两步：
            1. 判断页型 pageType，取值：COVER（封面）、SUMMARY（总检结论）、LAB（检验明细：指标数值表格）、
               ULTRASOUND（超声）、RADIOLOGY（放射/CT）、ECG（心电图）、DISCLAIMER（免责声明）、OTHER。
            2. 按页型抽取：LAB → indicators 逐项（指标名/数值/单位/参考范围照抄图中原文）；
               SUMMARY/ULTRASOUND/RADIOLOGY/ECG → findings 逐条转述文字结论；
               DISCLAIMER → 内容全部为空；COVER/OTHER → 有指标表格抽指标，有文字结论转 findings。
            硬规则：数值与参考范围必须逐字照抄图中原文，禁止推断/换算/补全；看不清的内容置空，绝不编造；
            心电波形与影像图像本身不判读，只处理图中已印出的文字结论；不抽取姓名证件等个人信息；
            日期统一 yyyy-MM-dd，无法解析置空。
            """;

    /** 一次调用的结构化输出：页型（自分类）+ 抽取内容。 */
    public record VisionExtraction(String pageType, String examName, String reportDate,
                                    List<LlmTextPageExtractor.LlmIndicator> indicators, List<String> findings) {
    }

    private final boolean enabled;
    private final ChatClient freeClient;
    private final ChatClient fallbackClient;
    private final Semaphore freePermits;

    /** 生产构造：按配置构建免费/付费两个模型（同 key 同端点、不同模型）。 */
    public GlmVisionAnalyzer(VisionProperties properties) {
        this(buildChatModel(properties, properties.getModel()),
                buildChatModel(properties, properties.getFallbackModel()),
                Math.max(1, properties.getFreeConcurrency()),
                properties.enabled() && isNotBlank(properties.getFallbackModel()));
    }

    /** 测试构造：注入桩模型与并发额度。 */
    GlmVisionAnalyzer(ChatModel freeModel, ChatModel fallbackModel, int freeConcurrency, boolean fallbackUsable) {
        this.enabled = freeModel != null;
        this.freeClient = freeModel == null ? null : ChatClient.create(freeModel);
        this.fallbackClient = fallbackUsable && fallbackModel != null ? ChatClient.create(fallbackModel) : null;
        this.freePermits = new Semaphore(Math.max(0, freeConcurrency));
    }

    private static ChatModel buildChatModel(VisionProperties properties, String model) {
        if (!properties.enabled() || !isNotBlank(model)) {
            return null;
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    @Override
    public PageExtraction analyze(PageContent page, ExtractionRecipe recipe) {
        if (!enabled || page.image() == null || page.image().length == 0) {
            return unavailable(page, recipe);
        }
        // 免费档并发额度：拿不到许可 → 溢出直接走付费档（保吞吐）
        boolean onFreeTier = freePermits.tryAcquire();
        if (!onFreeTier && fallbackClient == null) {
            // 无付费档兜底：阻塞排队等免费档（演示级可接受慢，不失败）
            try {
                freePermits.acquire();
                onFreeTier = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return unavailable(page, recipe);
            }
        }
        try {
            ChatClient target = onFreeTier ? freeClient : fallbackClient;
            String channel = onFreeTier ? "vision" : "vision-overflow-paid";
            try {
                return callVision(target, page, recipe, channel);
            } catch (Exception e) {
                if (onFreeTier && fallbackClient != null) {
                    log.warn("免费档视觉抽取失败（第{}页），升级付费档重试: {}", page.pageNo(), e.getMessage());
                    return callVision(fallbackClient, page, recipe, "vision-retry-paid");
                }
                log.warn("视觉抽取失败（第{}页），降级未识别: {}", page.pageNo(), e.getMessage());
                return unavailable(page, recipe);
            }
        } finally {
            if (onFreeTier) {
                freePermits.release();
            }
        }
    }

    private PageExtraction callVision(ChatClient client, PageContent page, ExtractionRecipe recipe, String channel) {
        Media media = new Media(imageMimeType(page.image()), new ByteArrayResource(page.image()));
        String hint = recipe.pageType() == null ? "" : "（文件名/版式提示：" + recipe.pageType().display() + "，仅供参考）";
        VisionExtraction parsed = client.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text("解析这张报告页面图片。" + hint).media(media))
                .call()
                .entity(VisionExtraction.class);
        return toExtraction(page, parsed, channel);
    }

    private static PageExtraction toExtraction(PageContent page, VisionExtraction parsed, String channel) {
        if (parsed == null) {
            return new PageExtraction(page.pageNo(), PageType.OTHER, PageType.OTHER.display(),
                    null, List.of(), List.of(), channel);
        }
        PageType pageType = PageType.parse(parsed.pageType());
        if (pageType == null) {
            pageType = PageType.OTHER;
        }
        LocalDate date = parseDate(parsed.reportDate());
        List<ReportIndicator> indicators = new ArrayList<>();
        if (parsed.indicators() != null) {
            for (var in : parsed.indicators()) {
                if (in == null || isBlank(in.name()) || isBlank(in.value())) {
                    continue; // 契约：名称/数值缺失丢弃，不猜
                }
                indicators.add(new ReportIndicator(in.name().trim(), in.value().trim(),
                        in.unit() == null ? "" : in.unit().trim(),
                        in.refRange() == null ? "" : in.refRange().trim(),
                        IndicatorStatus.UNKNOWN, date));
            }
        }
        List<String> findings = new ArrayList<>();
        if (parsed.findings() != null) {
            for (String f : parsed.findings()) {
                if (!isBlank(f)) {
                    findings.add(f.trim());
                }
            }
        }
        String title = isBlank(parsed.examName()) ? pageType.display() : parsed.examName().trim();
        return new PageExtraction(page.pageNo(), pageType, title,
                parsed.reportDate(), indicators, findings, channel);
    }

    private static PageExtraction unavailable(PageContent page, ExtractionRecipe recipe) {
        PageType pageType = recipe == null || recipe.pageType() == null ? PageType.OTHER : recipe.pageType();
        return new PageExtraction(page.pageNo(), pageType, pageType.display(), null,
                List.of(), List.of(), PageExtraction.CHANNEL_VISION_UNAVAILABLE);
    }

    /** 按魔数识别图片 MIME（ExifTool 归一化后应为 PNG/JPEG；webp 嗅探为纵深防御，防透传裸字节误标 JPEG）。 */
    private static org.springframework.util.MimeType imageMimeType(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        return MimeTypeUtils.IMAGE_JPEG; // 其余按 JPEG（ExifTool 归一化默认转 JPEG）
    }

    private static LocalDate parseDate(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().replace("/", "-").replace(".", "-"));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }
}
