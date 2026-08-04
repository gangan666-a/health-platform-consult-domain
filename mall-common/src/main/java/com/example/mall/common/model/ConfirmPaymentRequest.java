package com.example.mall.common.model;

import java.time.LocalDateTime;

/**
 * 支付确认回调（payment-service → order-service，order-service 将订单置为已支付）
 */
public class ConfirmPaymentRequest {

    private String payMethod;
    private String payBank;
    /** 卡号尾号（仅 4 位） */
    private String payCardTail;
    private LocalDateTime paidAt;

    public ConfirmPaymentRequest() {
    }

    public ConfirmPaymentRequest(String payMethod, String payBank, String payCardTail, LocalDateTime paidAt) {
        this.payMethod = payMethod;
        this.payBank = payBank;
        this.payCardTail = payCardTail;
        this.paidAt = paidAt;
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

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
