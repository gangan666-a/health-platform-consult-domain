package com.example.mall.agentservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 指标明细（shopdb_agent.report_indicator）。冗余 userId 供同指标多日期对比查询，
 * 复合索引 IDX(user_id, indicator_name, exam_date) 为对比主索引。
 * value/ref_range 按检查当时固存（快照语义），后续源数据变更不影响历史对比。
 */
@Entity
@Table(name = "report_indicator",
        indexes = {
                @Index(name = "idx_indicator_compare",
                        columnList = "user_id,indicator_name,exam_date")
        })
public class ReportIndicatorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "indicator_name", length = 128)
    private String indicatorName;

    /** indicator_value：H2/MySQL 通用（value 为 H2 保留字，故避免同名物理列） */
    @Column(name = "indicator_value", length = 64)
    private String value;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "ref_range", length = 128)
    private String refRange;

    /** HIGH / NORMAL / LOW / UNKNOWN */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "exam_date")
    private LocalDate examDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public String getIndicatorName() {
        return indicatorName;
    }

    public void setIndicatorName(String indicatorName) {
        this.indicatorName = indicatorName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getRefRange() {
        return refRange;
    }

    public void setRefRange(String refRange) {
        this.refRange = refRange;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }
}
