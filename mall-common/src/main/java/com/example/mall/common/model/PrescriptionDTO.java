package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处方（consult-service → 调用方/前端）。
 * <p>
 * {@code no} 为处方号（RX+yyyyMMddHHmmss+4位随机，DB 唯一索引兜底）；
 * {@code items} 为处方明细（JSON），{@code consumedOrderNo} 在消费时回写（P1-02 契约带 orderNo）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrescriptionDTO {

    private String no;
    private Long sessionId;
    private String doctorId;
    private String doctorName;
    private String patientName;
    private List<PrescriptionItem> items;
    private PrescriptionStatus status;
    private String statusLabel;
    private LocalDateTime validUntil;
    private String consumedOrderNo;
    private String note;
    private LocalDateTime issuedAt;
    private LocalDateTime consumedAt;

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

    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
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
