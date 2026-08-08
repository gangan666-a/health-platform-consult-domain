package com.example.mall.consultservice.model;

import com.example.mall.common.model.ConsultationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问诊会话。
 * <p>
 * 状态机（全部 CAS 影响行数裁决，并发安全）：WAITING→ONGOING（接单）、ONGOING→PRESCRIPTION_ISSUED（开方，同事务）、
 * WAITING/ONGOING→ENDED（主动/断线）、ONGOING→EXPIRED（空闲超时调度器）。
 * <p>
 * 关键索引：
 * - IDX(product_id, status)：同药品未结束会话复用判定（演示无 userId，按药品去重）。
 * - IDX(status, last_active_at)：空闲超时扫描。
 * 状态变更依赖行级锁，DB 为唯一权威（内存 SessionState 仅作快速裁决与 seq 分配）。
 */
@Entity
@Table(name = "consultation_session",
        indexes = {
                @Index(name = "idx_session_product_status", columnList = "product_id,status"),
                @Index(name = "idx_session_status_last_active", columnList = "status,last_active_at")
        })
public class ConsultationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    /** 患者姓名（演示环境默认「演示患者」，不落 userId，隔离靠 token 绑定） */
    @Column(name = "patient_name", nullable = false, length = 50)
    private String patientName;

    @Column(name = "doctor_id", length = 64)
    private String doctorId;

    @Column(name = "doctor_name", length = 64)
    private String doctorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsultationStatus status;

    /** 会话用户 join 凭据（演示环境内存签发，重启后经建会话复用重新签发，R-12） */
    @Column(name = "token", length = 64)
    private String token;

    /** 下单成功后绑定的订单号（可空） */
    @Column(name = "order_no", length = 32)
    private String orderNo;

    /** 最近文本消息时间（空闲超时依据；心跳/系统消息不更新，PRD 明确） */
    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    /** 最近心跳时间（探活参考，不参与空闲计时） */
    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 结束原因（ENDED 时：USER_ENDED/DOCTOR_ENDED/DISCONNECT_TIMEOUT/COMPLETED） */
    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(String doctorId) {
        this.doctorId = doctorId;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public void setDoctorName(String doctorName) {
        this.doctorName = doctorName;
    }

    public ConsultationStatus getStatus() {
        return status;
    }

    public void setStatus(ConsultationStatus status) {
        this.status = status;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }
}
