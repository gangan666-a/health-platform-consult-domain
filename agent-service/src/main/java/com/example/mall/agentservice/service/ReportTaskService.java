package com.example.mall.agentservice.service;

import com.example.mall.agentservice.config.ReportTaskProperties;
import com.example.mall.agentservice.dto.ReportUploadResponse;
import com.example.mall.agentservice.report.ProgressListener;
import com.example.mall.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 报告异步任务（演示级进程内任务队列，生产演进路径：RocketMQ/Kafka + Redis 任务表）：
 * 上传即返回 taskId（consent/文件校验同步完成，非法输入仍即时 400），
 * 虚拟线程 + Semaphore 有界并发执行解析管线，进度经 ProgressListener 推给 SSE 订阅者。
 * 任务状态内存态（与会话内存态一致的演示取舍），终态 TTL 惰性清理。
 */
@Service
public class ReportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ReportTaskService.class);

    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 单条进度事件（SSE 与轮询共用结构）。 */
    public record ProgressEvent(String stage, int current, int total, String detail, Instant at) {
    }

    /** 提交受理视图。 */
    public record TaskAccepted(String taskId, String status, String pollUrl, String sseUrl) {
    }

    /** 任务快照（轮询视图）。 */
    public record ReportTaskView(String taskId, String status, String currentStage,
                                 List<ProgressEvent> events, String error,
                                 ReportUploadResponse result) {
    }

    /** 任务（内存态 POJO：进度事件与状态变更同步保护，供并行页回调）。
     *  content 为原始字节（幂等 hash/原文留档），parseContent 为解析输入（HEIC 在 submit 已转 JPEG）。 */
    static final class ReportTask {
        final String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final Long userId;
        final String sessionId;
        final String fileName;
        final String contentType;
        final byte[] content;
        final byte[] parseContent;
        final Instant createdAt = Instant.now();
        volatile String status = "QUEUED";
        volatile String currentStage = "";
        volatile String error;
        volatile ReportUploadResponse result;
        volatile Instant finishedAt;
        final List<ProgressEvent> events = new ArrayList<>();
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

        ReportTask(Long userId, String sessionId, String fileName, String contentType, byte[] content, byte[] parseContent) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
            this.parseContent = parseContent;
        }

        synchronized void record(ProgressEvent event) {
            events.add(event);
            currentStage = event.stage();
        }
    }

    private final ReportComplianceService compliance;
    private final ReportUploadService uploadService;
    private final ReportTaskProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, ReportTask> tasks = new ConcurrentHashMap<>();
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ReportTaskService(ReportComplianceService compliance,
                             ReportUploadService uploadService,
                             ReportTaskProperties properties,
                             ObjectMapper objectMapper) {
        this.compliance = compliance;
        this.uploadService = uploadService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
    }

    /** 提交异步任务：consent/文件校验同步完成（非法输入即时 400），字节读尽后立即返回 taskId。 */
    public TaskAccepted submit(Long userId, String sessionId, MultipartFile file, boolean consent) {
        compliance.requireConsent(consent);
        String fileName = compliance.validateFile(file);
        byte[] content = readBytes(file);
        // 内容门禁 + HEIC 转码同步执行：伪装扩展名/无法转码的 HEIC 在此即时 400，避免后台任务才失败
        byte[] parseContent = compliance.prepareContent(content).content();
        sweepExpired();

        ReportTask task = new ReportTask(userId, sessionId, fileName, file.getContentType(), content, parseContent);
        tasks.put(task.taskId, task);
        executor.execute(() -> run(task, consent));
        log.info("报告异步任务已提交: taskId={} file={}", task.taskId, fileName);
        return new TaskAccepted(task.taskId, task.status,
                "/api/agents/report/task/" + task.taskId,
                "/api/agents/report/task/" + task.taskId + "/events");
    }

    /** 任务快照（轮询）。 */
    public ReportTaskView view(String taskId) {
        ReportTask task = requireTask(taskId);
        synchronized (task) {
            return new ReportTaskView(task.taskId, task.status, task.currentStage,
                    List.copyOf(task.events), task.error, task.result);
        }
    }

    /** SSE 订阅：先回放已发生进度，再实时推送至终态。 */
    public SseEmitter subscribe(String taskId) {
        ReportTask task = requireTask(taskId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> task.emitters.remove(emitter));
        emitter.onTimeout(() -> task.emitters.remove(emitter));
        task.emitters.add(emitter);

        synchronized (task) {
            for (ProgressEvent event : task.events) {
                sendEvent(emitter, "progress", event);
            }
            sendTerminalIfFinished(task, emitter);
        }
        return emitter;
    }

    private void run(ReportTask task, boolean consent) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(task, e);
            return;
        }
        try {
            task.status = "RUNNING";
            broadcast(task, new ProgressEvent("SUBMIT", 1, 1, "任务开始执行", Instant.now()));
            ReportUploadResponse result = uploadService.doUpload(task.userId, task.sessionId,
                    task.fileName, task.contentType, task.content, task.parseContent, consent,
                    (stage, current, total, detail) -> {
                        ProgressEvent event = new ProgressEvent(stage, current, total, detail, Instant.now());
                        task.record(event);
                        broadcast(task, event);
                    });
            finish(task, result);
        } catch (Exception e) {
            finish(task, e);
        } finally {
            permits.release();
        }
    }

    private void finish(ReportTask task, Object outcome) {
        synchronized (task) {
            if ("DONE".equals(task.status) || "FAILED".equals(task.status)) {
                return;
            }
            if (outcome instanceof ReportUploadResponse result) {
                task.status = "DONE";
                task.result = result;
            } else {
                task.status = "FAILED";
                task.error = outcome instanceof Exception e && e.getMessage() != null
                        ? e.getMessage() : String.valueOf(outcome);
                log.warn("报告异步任务失败: taskId={} error={}", task.taskId, task.error);
            }
            task.finishedAt = Instant.now();
            for (SseEmitter emitter : task.emitters) {
                sendTerminalIfFinished(task, emitter);
            }
            task.emitters.clear();
        }
    }

    private void sendTerminalIfFinished(ReportTask task, SseEmitter emitter) {
        if ("DONE".equals(task.status)) {
            sendEvent(emitter, "done", task.result);
        } else if ("FAILED".equals(task.status)) {
            sendEvent(emitter, "failed", Map.of("error", task.error == null ? "解析失败" : task.error));
        }
    }

    private void broadcast(ReportTask task, ProgressEvent event) {
        for (SseEmitter emitter : task.emitters) {
            sendEvent(emitter, "progress", event);
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
            if ("done".equals(name) || "failed".equals(name)) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            // 订阅端断开属正常（轮询可兜底取结果），不打断任务执行
            log.debug("SSE 推送失败（订阅端断开?）: {}", e.getMessage());
        }
    }

    private ReportTask requireTask(String taskId) {
        ReportTask task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在或已过期");
        }
        return task;
    }

    /** 终态任务 TTL 惰性清理（提交时顺带清扫，避免引入调度器）。 */
    private void sweepExpired() {
        Instant deadline = Instant.now().minus(properties.getTtlMinutes(), ChronoUnit.MINUTES);
        tasks.values().removeIf(task -> task.finishedAt != null && task.finishedAt.isBefore(deadline));
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("文件读取失败，请重试");
        }
    }
}
