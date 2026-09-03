package com.example.mall.agentservice.report;

/**
 * 解析进度监听（异步任务 SSE 进度推送的接入点）：同步路径用 {@link #noop()}，
 * 异步路径由 ReportTaskService 注入实现，把管线各阶段进度推给前端。
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param stage  阶段名（PROFILE/PLAN/CLASSIFY/EXTRACT/AGGREGATE/VALIDATE/ALIGN/PERSIST/INTERPRET/DONE）
     * @param current 当前完成数（阶段内无进度细分时传 1）
     * @param total   总数（同上）
     * @param detail  人类可读细节（如「第 3/12 页·检验明细」）
     */
    void onProgress(String stage, int current, int total, String detail);

    static ProgressListener noop() {
        return (stage, current, total, detail) -> {
        };
    }
}
