package com.example.mall.agentservice.controller;

import com.example.mall.agentservice.dto.ReportCompareResponse;
import com.example.mall.agentservice.dto.ReportUploadResponse;
import com.example.mall.agentservice.service.ReportCompareService;
import com.example.mall.agentservice.service.ReportTaskService;
import com.example.mall.agentservice.service.ReportUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 报告解读端点（§4）：上传（同步保留 + 异步任务）+ 任务轮询/SSE + 同指标历史对比。
 * 调试期由 agent-service 直接暴露（BFF 代理为后置迭代，见 Spec §13 边界说明）。
 */
@RestController
@RequestMapping("/api/agents/report")
public class ReportController {

    private final ReportUploadService uploadService;
    private final ReportCompareService compareService;
    private final ReportTaskService taskService;

    public ReportController(ReportUploadService uploadService, ReportCompareService compareService,
                            ReportTaskService taskService) {
        this.uploadService = uploadService;
        this.compareService = compareService;
        this.taskService = taskService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReportUploadResponse upload(@RequestParam("userId") Long userId,
                                       @RequestParam(value = "sessionId", required = false) String sessionId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "consent", defaultValue = "false") boolean consent) {
        return uploadService.upload(userId, sessionId, file, consent);
    }

    /** 异步上传：校验同步完成（非法输入仍 400），受理即返回 taskId（一二十页报告不阻塞等待）。 */
    @PostMapping(value = "/upload/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportTaskService.TaskAccepted uploadAsync(@RequestParam("userId") Long userId,
                                                      @RequestParam(value = "sessionId", required = false) String sessionId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "consent", defaultValue = "false") boolean consent) {
        return taskService.submit(userId, sessionId, file, consent);
    }

    /** 任务轮询：状态 + 进度事件 + 完成后的完整结果。 */
    @GetMapping("/task/{taskId}")
    public ReportTaskService.ReportTaskView task(@PathVariable String taskId) {
        return taskService.view(taskId);
    }

    /** SSE 实时进度：回放历史进度后持续推送，直至 done/failed 终态事件。 */
    @GetMapping("/task/{taskId}/events")
    public SseEmitter taskEvents(@PathVariable String taskId) {
        return taskService.subscribe(taskId);
    }

    @GetMapping("/{userId}/compare")
    public ReportCompareResponse compare(@PathVariable Long userId,
                                         @RequestParam("indicator") String indicator) {
        return compareService.compare(userId, indicator);
    }

    /** 原报告回看：读留档原文（OSS 实现可扩展为预签名 URL 302 跳转）。 */
    @GetMapping("/file/{reportId}")
    public ResponseEntity<byte[]> file(@RequestParam("userId") Long userId,
                                       @PathVariable Long reportId) {
        byte[] content = uploadService.loadReportFile(userId, reportId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"report-" + reportId + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /** 被遗忘权：删除报告（记录 + 指标明细 + 原文留档）。 */
    @DeleteMapping("/{reportId}")
    public Map<String, Object> delete(@RequestParam("userId") Long userId,
                                      @PathVariable Long reportId) {
        uploadService.deleteReport(userId, reportId);
        return Map.of("deleted", true, "reportId", reportId);
    }
}
