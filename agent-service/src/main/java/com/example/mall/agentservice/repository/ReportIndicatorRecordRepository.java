package com.example.mall.agentservice.repository;

import com.example.mall.agentservice.model.ReportIndicatorRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 指标明细仓储（shopdb_agent.report_indicator）。
 * 同指标多日期对比走复合索引 IDX(user_id, indicator_name, exam_date)（§5 主索引）。
 */
public interface ReportIndicatorRecordRepository extends JpaRepository<ReportIndicatorRecord, Long> {

    List<ReportIndicatorRecord> findByUserIdAndIndicatorNameOrderByExamDateAsc(Long userId, String indicatorName);

    List<ReportIndicatorRecord> findByReportId(Long reportId);

    void deleteByReportId(Long reportId);
}
