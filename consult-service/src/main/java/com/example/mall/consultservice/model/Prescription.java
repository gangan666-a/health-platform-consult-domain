package com.example.mall.consultservice.model;

import com.example.mall.common.model.PrescriptionItem;
import com.example.mall.common.model.PrescriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处方。
 * <p>
 * 状态机：ACTIVE→USED（下单原子消费，一次性）；ACTIVE→EXPIRED（过期，懒判定+定时扫描双保险）；
 * ACTIVE→REVOKED（撤销）。终态不可再消费。
 * <p>
 * 关键索引：
 * - UK(no)：处方号唯一兜底（RX+yyyyMMddHHmmss+4位随机，同秒碰撞重试）。
 * - IDX(status, valid_until)：过期扫描。
 * 消费/恢复/撤销/过期全部走单条 CAS UPDATE，行锁串行化，防复用唯一边界（EX-IM-26）。
 */
@Entity
@Table(name = "prescription",
        indexes = {
                @Index(name = "idx_prescription_no", columnList = "no", unique = true),
                @Index(name = "idx_prescription_session", columnList = "session_id"),
                @Index(name = "idx_prescription_status_valid", columnList = "status,valid_until")
        })
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "no", nullable = false, length = 32)
    private String no;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "doctor_id", nullable = false, length = 64)
    private String doctorId;

    @Column(name = "doctor_name", length = 64)
    private String doctorName;

    @Column(name = "patient_name", nullable = false, length = 50)
    private String patientName;

    /** 处方明细（JSON：productId/productName/dosage/frequency/quantity） */
    @Convert(converter = ItemsJsonConverter.class)
    @Column(name = "items", nullable = false, columnDefinition = "TEXT")
    private List<PrescriptionItem> items;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PrescriptionStatus status;

    /** 有效期截止（默认开方 + 7 天） */
    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    /** 消费订单号（USED 时回写，restore 按此归属校验，P2-03） */
    @Column(name = "consumed_order_no", length = 32)
    private String consumedOrderNo;

    @Column(length = 200)
    private String note;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNo() {
        return no;
    }

    public void setNo(String no) {
        this.no = no;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
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

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public List<PrescriptionItem> getItems() {
        return items;
    }

    public void setItems(List<PrescriptionItem> items) {
        this.items = items;
    }

    public PrescriptionStatus getStatus() {
        return status;
    }

    public void setStatus(PrescriptionStatus status) {
        this.status = status;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDateTime validUntil) {
        this.validUntil = validUntil;
    }

    public String getConsumedOrderNo() {
        return consumedOrderNo;
    }

    public void setConsumedOrderNo(String consumedOrderNo) {
        this.consumedOrderNo = consumedOrderNo;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
