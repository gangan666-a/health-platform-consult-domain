package com.example.mall.agentservice.service;

import com.example.mall.agentservice.config.ReportMetrics;
import com.example.mall.agentservice.dto.ReportUploadResponse;
import com.example.mall.agentservice.model.ReportIndicatorRecord;
import com.example.mall.agentservice.model.ReportRecord;
import com.example.mall.agentservice.orchestration.AgentOrchestrator;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.orchestration.AgentSessionStore;
import com.example.mall.agentservice.report.CriticalAlert;
import com.example.mall.agentservice.report.CriticalValueEvaluator;
import com.example.mall.agentservice.report.IndicatorStatus;
import com.example.mall.agentservice.report.ProgressListener;
import com.example.mall.agentservice.report.PromptGuard;
import com.example.mall.agentservice.report.ReportDoc;
import com.example.mall.agentservice.report.ReportFinding;
import com.example.mall.agentservice.report.ReportKnowledgeService;
import com.example.mall.agentservice.report.ReportParseResult;
import com.example.mall.agentservice.report.ReportParser;
import com.example.mall.agentservice.report.ReportReferenceService;
import com.example.mall.agentservice.repository.ReportIndicatorRecordRepository;
import com.example.mall.agentservice.repository.ReportRecordRepository;
import com.example.mall.agentservice.storage.ReportFileStorage;
import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.AgentChatRequest;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 报告上传编排（P-1..P-11 门面）：
 * 授权 → 文件 hash 幂等检查（命中回放响应快照）→ plan-execute 解析 → 脱敏 →
 * 参考范围比对（自带优先）→ 危急值规则 → 原文留档（OSS/本地，可降级）→ 事务落库（收窄）→
 * 事实卡合并（L1 长期记忆）→ LLM 解读（事务外，输出侧注入检测）→ 响应快照落库。
 * 事务边界（B-10）：落库段 TransactionTemplate 收窄，慢速 LLM 调用在事务外。
 */
@Service
public class ReportUploadService {

    private static final Logger log = LoggerFactory.getLogger(ReportUploadService.class);

    public static final String DISCLAIMER =
            "本报告解读由 AI 生成，仅供参考，不能替代专业医疗诊断。如有异常或不适，请及时线下就医。";

    private final ReportComplianceService compliance;
    private final ReportParser reportParser;
    private final ReportReferenceService referenceService;
    private final ReportKnowledgeService reportKnowledge;
    private final CriticalValueEvaluator criticalEvaluator;
    private final com.example.mall.agentservice.graph.GraphKnowledgeService graphKnowledge;
    private final ReportRecordRepository recordRepository;
    private final ReportIndicatorRecordRepository indicatorRepository;
    private final HealthMemoryService healthMemory;
    private final AgentOrchestrator orchestrator;
    private final AgentSessionStore sessionStore;
    private final TransactionTemplate transactionTemplate;
    private final ReportFileStorage fileStorage;
    private final ReportMetrics metrics;
    private final ObjectMapper objectMapper;

    public ReportUploadService(ReportComplianceService compliance,
                               ReportParser reportParser,
                               ReportReferenceService referenceService,
                               ReportKnowledgeService reportKnowledge,
                               CriticalValueEvaluator criticalEvaluator,
                               com.example.mall.agentservice.graph.GraphKnowledgeService graphKnowledge,
                               ReportRecordRepository recordRepository,
                               ReportIndicatorRecordRepository indicatorRepository,
                               HealthMemoryService healthMemory,
                               AgentOrchestrator orchestrator,
                               AgentSessionStore sessionStore,
                               TransactionTemplate transactionTemplate,
                               ReportFileStorage fileStorage,
                               ReportMetrics metrics,
                               ObjectMapper objectMapper) {
        this.compliance = compliance;
        this.reportParser = reportParser;
        this.referenceService = referenceService;
        this.reportKnowledge = reportKnowledge;
        this.criticalEvaluator = criticalEvaluator;
        this.graphKnowledge = graphKnowledge;
        this.recordRepository = recordRepository;
        this.indicatorRepository = indicatorRepository;
        this.healthMemory = healthMemory;
        this.orchestrator = orchestrator;
        this.sessionStore = sessionStore;
        this.transactionTemplate = transactionTemplate;
        this.fileStorage = fileStorage;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public ReportUploadResponse upload(Long userId, String sessionId, MultipartFile file, boolean consent) {
        return upload(userId, sessionId, file, consent, ProgressListener.noop());
    }

    /** 完整管线（可注入进度监听供异步任务 SSE 推送）。 */
    public ReportUploadResponse upload(Long userId, String sessionId, MultipartFile file,
                                       boolean consent, ProgressListener progress) {
        String fileName = compliance.validateFile(file);
        byte[] content = readBytes(file);
        // 内容门禁 + HEIC 转码（同步路径在此准备解析输入，无法转码即 400 拒绝）
        byte[] parseContent = compliance.prepareContent(content).content();
        return doUpload(userId, sessionId, fileName, file.getContentType(), content, parseContent, consent, progress);
    }

    /** 原始字节入口（异步任务用：MultipartFile 不跨请求生命周期，任务持字节数组）。
     *  content 为原始字节（幂等 hash 与原文留档依据），parseContent 为解析输入（HEIC 已由 prepareContent 转 JPEG）。
     *  consent/内容门禁在 upload()/submit() 均已过 prepareContent（fail-closed 纵深防御：两个入口都无法免检）。 */
    public ReportUploadResponse doUpload(Long userId, String sessionId, String fileName, String contentType,
                                         byte[] content, byte[] parseContent, boolean consent, ProgressListener progress) {
        compliance.requireConsent(consent);
        long startedAt = System.currentTimeMillis();

        // 文件 hash 幂等：按原始字节计算，同用户重复上传直接回放快照（防重复解析/落库/计费）
        String fileHash = sha256(content);
        ReportRecord existing = recordRepository.findByUserIdAndFileHash(userId, fileHash).orElse(null);
        if (existing != null && existing.getResponseSnapshot() != null) {
            metrics.idempotentHit();
            log.info("幂等命中：回放历史响应 reportId={} hash={}", existing.getId(), fileHash);
            return readSnapshot(existing.getResponseSnapshot());
        }

        try {
            ReportUploadResponse response = process(userId, sessionId, fileName, contentType,
                    content, parseContent, consent, progress, fileHash);
            metrics.parseResult("success");
            return response;
        } catch (BusinessException e) {
            if (e.getMessage() != null && e.getMessage().contains("未能自动识别")) {
                metrics.parseResult("e3-rejected");
            }
            throw e;
        } finally {
            metrics.recordParseDuration(System.currentTimeMillis() - startedAt);
        }
    }

    private ReportUploadResponse process(Long userId, String sessionId, String fileName, String contentType,
                                         byte[] content, byte[] parseContent, boolean consent, ProgressListener progress,
                                         String fileHash) {
        // plan-execute 解析（PROFILE → PLAN → EXECUTE，页级并行在管线内部）；parseContent 为解析输入
        ReportParseResult parseResult = reportParser.parse(parseContent, fileName, contentType, progress);
        ReportDoc doc = parseResult.doc();
        List<ReportFinding> safeFindings = desensitizeFindings(parseResult.findings());
        if (parseResult.hasNoContent()) {
            // E-3：零指标且零所见（如无文本层的图片件且未接视觉模型）——不虚构、不落库
            throw new BusinessException("未能自动识别报告内容，请上传更清晰的图片或带文本层的 PDF 报告");
        }

        // 防线1：参考范围比对（报告自带优先，纯 Java 判定）
        progress.onProgress("ALIGN", 1, 1, "参考范围比对");
        ReportDoc aligned = referenceService.align(doc);
        // 防线3：危急值规则引擎（确定性，先于 LLM）
        List<CriticalAlert> alerts = criticalEvaluator.evaluate(aligned.indicators(), safeFindings);
        alerts.forEach(a -> metrics.criticalAlert());
        String summary = healthMemory.summarize(aligned);

        // 原文留档（OSS/本地；失败降级不留档，解读不受影响）
        String fileKey = null;
        String storageType = "none";
        try {
            ReportFileStorage.StoredFile stored = fileStorage.store(userId, fileHash, content, fileName, contentType);
            fileKey = stored.key();
            storageType = stored.storageType();
        } catch (Exception e) {
            metrics.degrade("storage");
            log.warn("原文留档失败，降级不留档: {}", e.getMessage());
        }

        // 落库事务收窄：三表同事务（T-1），LLM 解读移出事务
        progress.onProgress("PERSIST", 1, 1, "保存报告记录");
        String finalFileKey = fileKey;
        String finalStorageType = storageType;
        ReportRecord record = transactionTemplate.execute(status ->
                persist(aligned, userId, sessionId, summary, fileHash, finalFileKey, finalStorageType));
        healthMemory.upsert(userId, healthMemory.toFacts(aligned, alerts));

        // LLM 解读为 best-effort（T-3）：失败降级占位，不影响已提交的落库事务
        progress.onProgress("INTERPRET", 1, 1, "生成解读");
        String llmInput = compliance.buildLlmInput(aligned, safeFindings);
        if (!aligned.referenceVerified()) {
            llmInput += "\n（提示：参考范围未经知识库校验）";
        }
        if (!alerts.isEmpty()) {
            llmInput += "\n\n【危急值提醒（系统规则引擎判定，必须在回复中原样传达并建议立即就医）】\n";
            for (CriticalAlert alert : alerts) {
                llmInput += "- " + alert.display() + '\n';
            }
        }
        // 知识图谱关联（Neo4j 多跳推理，图不可用降级 ES 关系查表，全降级为空不阻断）
        String graphContext = graphKnowledge.buildAbnormalContext(aligned.indicators());
        if (!graphContext.isBlank()) {
            llmInput += "\n\n" + graphContext;
        }
        // 知识库关联（T-2）：ES 命中则附解释性片段，不可用/无命中降级为空，不影响解读
        String knowledge = reportKnowledge.enrich(aligned);
        if (!knowledge.isBlank()) {
            llmInput += "\n\n" + knowledge;
        }
        String sid = resolveSessionId(sessionId);
        AgentSession session = sessionStore.getOrCreate(sid);
        session.setUserId(userId);
        AgentChatResponse chat;
        try {
            chat = orchestrator.chat(buildChatRequest(sid, llmInput));
        } catch (Exception e) {
            log.warn("报告解读 LLM 调用失败，降级占位: {}", e.getMessage());
            chat = null;
        }
        String interpretation = chat != null ? chat.getContent()
                : "报告解读服务暂时不可用，请稍后重试或咨询问诊医生。";
        // 输出侧注入检测（PromptGuard 第三层）：疑似注入成功 → 降级占位并记告警指标
        if (PromptGuard.looksCompromised(interpretation)) {
            metrics.degrade("prompt-guard");
            log.warn("解读输出疑似注入泄露，已替换为降级回复");
            interpretation = "检测到报告内容包含可疑指令，本次解读已拦截。请上传正常的检查报告。";
        }

        progress.onProgress("DONE", 1, 1, "解析完成");
        List<String> criticalViews = alerts.stream().map(CriticalAlert::display).toList();
        List<String> handoffs = List.copyOf(collectHandoffs(aligned, chat, alerts));
        ReportUploadResponse response = new ReportUploadResponse(record.getId(), aligned.examName(),
                aligned.reportDate(), aligned.source(), aligned.referenceVerified(), toViews(aligned),
                summary, interpretation, DISCLAIMER, handoffs, criticalViews,
                toFindingViews(safeFindings), parseResult.trace());
        // 响应快照落库：幂等回放与原报告回看的依据（独立短事务，失败不回滚主数据）
        try {
            record.setResponseSnapshot(objectMapper.writeValueAsString(response));
            transactionTemplate.executeWithoutResult(status -> recordRepository.save(record));
        } catch (Exception e) {
            log.warn("响应快照落库失败（不影响主数据）: {}", e.getMessage());
        }
        return response;
    }

    /** 本库三表同事务（T-1）：报告记录 + 指标明细（由 TransactionTemplate 包裹执行）。 */
    private ReportRecord persist(ReportDoc aligned, Long userId, String sessionId, String summary,
                                 String fileHash, String fileKey, String storageType) {
        ReportRecord record = new ReportRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setExamName(aligned.examName());
        record.setReportDate(aligned.reportDate());
        record.setSource(aligned.source());
        record.setSummary(summary);
        record.setCreatedAt(LocalDateTime.now());
        record.setFileHash(fileHash);
        record.setFileKey(fileKey);
        record.setStorageType(storageType);
        record = recordRepository.save(record);

        for (var in : aligned.indicators()) {
            ReportIndicatorRecord row = new ReportIndicatorRecord();
            row.setUserId(userId);
            row.setReportId(record.getId());
            row.setIndicatorName(in.name());
            row.setValue(in.value());
            row.setUnit(in.unit());
            row.setRefRange(in.refRange());
            row.setStatus(in.status().name());
            row.setExamDate(in.examDate());
            indicatorRepository.save(row);
        }
        return record;
    }

    /** 被遗忘权：删除报告记录 + 指标明细 + 原文留档（事实卡为派生数据，保留聚合语义）。 */
    public void deleteReport(Long userId, Long reportId) {
        ReportRecord record = recordRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BusinessException("报告不存在或无权访问"));
        if (record.getFileKey() != null) {
            try {
                fileStorage.delete(record.getFileKey());
            } catch (Exception e) {
                log.warn("原文删除失败（继续删除记录）: {}", e.getMessage());
            }
        }
        transactionTemplate.executeWithoutResult(status -> {
            indicatorRepository.deleteByReportId(reportId);
            recordRepository.delete(record);
        });
    }

    /** 原报告回看：读留档原文（本地流式 / OSS 侧由 Controller 走预签名跳转）。 */
    public byte[] loadReportFile(Long userId, Long reportId) {
        ReportRecord record = recordRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BusinessException("报告不存在或无权访问"));
        if (record.getFileKey() == null) {
            throw new BusinessException("该报告未留档原文");
        }
        return fileStorage.load(record.getFileKey());
    }

    private ReportUploadResponse readSnapshot(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, ReportUploadResponse.class);
        } catch (Exception e) {
            throw new BusinessException("历史响应快照损坏，请重新上传");
        }
    }

    /** 检查所见脱敏（B-6）：掩码证件号、剔除含 PII 的行后再进入 prompt/响应。 */
    private List<ReportFinding> desensitizeFindings(List<ReportFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<ReportFinding> safe = new java.util.ArrayList<>(findings.size());
        for (ReportFinding finding : findings) {
            String text = compliance.desensitize(finding.text());
            if (!text.isBlank()) {
                safe.add(new ReportFinding(finding.section(), text, finding.pageNo()));
            }
        }
        return safe;
    }

    private AgentChatRequest buildChatRequest(String sessionId, String llmInput) {
        AgentChatRequest req = new AgentChatRequest();
        req.setSessionId(sessionId);
        req.setMessage(llmInput);
        req.setTargetAgent(AgentType.REPORT);
        return req;
    }

    private static String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        return sessionId;
    }

    private Set<String> collectHandoffs(ReportDoc doc, AgentChatResponse chat, List<CriticalAlert> alerts) {
        Set<String> handoffs = new LinkedHashSet<>();
        boolean abnormal = doc.indicators().stream()
                .anyMatch(i -> i.status() == IndicatorStatus.HIGH || i.status() == IndicatorStatus.LOW);
        if (abnormal || !alerts.isEmpty()) {
            handoffs.add(AgentType.CONSULTATION.name());
        }
        if (chat != null && chat.isHandoff() && chat.getHandoffTo() != null) {
            handoffs.add(chat.getHandoffTo().name());
        }
        return handoffs;
    }

    private List<ReportUploadResponse.IndicatorView> toViews(ReportDoc doc) {
        return doc.indicators().stream()
                .map(in -> new ReportUploadResponse.IndicatorView(
                        in.name(), in.value(), in.unit(), in.refRange(), in.status().display(), in.examDate()))
                .toList();
    }

    private static List<ReportUploadResponse.FindingView> toFindingViews(List<ReportFinding> findings) {
        return findings.stream()
                .map(f -> new ReportUploadResponse.FindingView(f.section(), f.text(), f.pageNo()))
                .toList();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("文件读取失败，请重试");
        }
    }
}
