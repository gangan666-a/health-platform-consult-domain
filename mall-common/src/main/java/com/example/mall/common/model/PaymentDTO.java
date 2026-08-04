package com.example.mall.common.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录（payment-service → 前端/调用方）
 */
public class PaymentDTO {

    private Long id;
    private String orderNo;
    private BigDecimal amount;
    private String payMethod;
    private String payBank;
    private String payCardTail;
    /** 支付状态：PROCESSING / SUCCESS / FAILED */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
    }

    public String getPayBank() {
        return payBank;
    }

    public void setPayBank(String payBank) {
        this.payBank = payBank;
    }

    public String getPayCardTail() {
        return payCardTail;
    }

    public void setPayCardTail(String payCardTail) {
        this.payCardTail = payCardTail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
