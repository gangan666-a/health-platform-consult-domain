package com.example.mall.agentservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报告记录（shopdb_agent.report_record）。仅存内部 userId + 检查数据，不落 PII（合规红线）。
 * 快照语义：exam_name/report_date/source/summary 按检查当时固存。
 */
@Entity
@Table(name = "report_record",
        indexes = {
                @Index(name = "idx_report_user", columnList = "user_id"),
                @Index(name = "idx_report_session", columnList = "session_id"),
                @Index(name = "idx_report_created", columnList = "created_at")
        })
public class ReportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "exam_name", length = 128)
    private String examName;

    @Column(name = "report_date")
    private LocalDate reportDate;

    /** 解析来源：vision（多模态 LLM） */
    @Column(name = "source", length = 32)
    private String source;

    /** 自然语言摘要（长期记忆原料） */
    @Lob
    @Column(name = "summary")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 上传文件 SHA-256（幂等键：同 userId+hash 直接回放快照，防重复解析/计费） */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /** 对象存储键（OSS 或本地降级存储）；不落盘策略下为空 */
    @Column(name = "file_key", length = 256)
    private String fileKey;

    /** 存储类型：oss / local / none */
    @Column(name = "storage_type", length = 16)
    private String storageType;

    /** 完整响应快照（JSON）：幂等回放与原报告回看的依据 */
    @Lob
    @Column(name = "response_snapshot")
    private String responseSnapshot;

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public void setResponseSnapshot(String responseSnapshot) {
        this.responseSnapshot = responseSnapshot;
    }

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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getExamName() {
        return examName;
    }

    public void setExamName(String examName) {
        this.examName = examName;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
