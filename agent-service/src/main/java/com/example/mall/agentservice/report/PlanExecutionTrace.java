package com.example.mall.agentservice.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行轨迹（EXECUTE 步骤的审计日志）：记录 plan-execute 各步骤的状态迁移与页级细节，
 * 渲染进上传响应 parseTrace，供排查问题与成本核算（本次解析走了哪些步骤、哪些页走了什么通道）。
 */
public final class PlanExecutionTrace {

    /** 步骤状态：PENDING 未执行 / RUNNING 执行中 / DONE 成功 / FAILED 失败（已降级）/ SKIPPED 跳过 */
    public enum StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    public record TraceEntry(String step, StepStatus status, String detail, Instant at) {
    }

    private final List<TraceEntry> entries = new ArrayList<>();

    public synchronized void running(String step, String detail) {
        entries.add(new TraceEntry(step, StepStatus.RUNNING, detail, Instant.now()));
    }

    public synchronized void done(String step, String detail) {
        entries.add(new TraceEntry(step, StepStatus.DONE, detail, Instant.now()));
    }

    public synchronized void failed(String step, String detail) {
        entries.add(new TraceEntry(step, StepStatus.FAILED, detail, Instant.now()));
    }

    public synchronized void skipped(String step, String detail) {
        entries.add(new TraceEntry(step, StepStatus.SKIPPED, detail, Instant.now()));
    }

    public synchronized List<TraceEntry> snapshot() {
        return List.copyOf(entries);
    }

    /** 渲染为响应字段：如「CLASSIFY_PAGES:DONE(3页)」「EXTRACT_PAGES:DONE(第2页 LLM失败→规则兜底)」。 */
    public synchronized List<String> render() {
        List<String> lines = new ArrayList<>(entries.size());
        for (TraceEntry e : entries) {
            lines.add(e.step() + ":" + e.status() + (e.detail().isBlank() ? "" : "(" + e.detail() + ")"));
        }
        return lines;
    }
}
