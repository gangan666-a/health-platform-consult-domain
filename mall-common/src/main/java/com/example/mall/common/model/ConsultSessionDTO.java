package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * 问诊会话（consult-service → 调用方/前端）。
 * <p>
 * {@code token} 为该会话当前有效期的 WebSocket join 凭据（用户身份）：
 * 创建返回用户 token；医生接单后返回医生 token。演示环境 token 存内存（R-12：重启后需经
 * 建会话复用重新签发）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsultSessionDTO {

    private Long sessionId;
    private String token;
    private ConsultationStatus status;
    private String statusLabel;
    private Long productId;
    private String productName;
    private String doctorId;
    private String doctorName;
    private String patientName;
    /** 下单成功后绑定的订单号（可空） */
    private String orderNo;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime endedAt;
    /** 主动结束/断线结束原因（USER_ENDED/DOCTOR_ENDED/DISCONNECT_TIMEOUT/COMPLETED，可空） */
    private String endReason;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public ConsultationStatus getStatus() {
        return status;
    }

    public void setStatus(ConsultationStatus status) {
        this.status = status;
    }

    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
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

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }
}
