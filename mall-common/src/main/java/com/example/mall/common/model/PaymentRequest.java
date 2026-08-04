package com.example.mall.common.model;

/**
 * 发起支付请求（order-service 前端 → payment-service）
 */
public class PaymentRequest {

    private String orderNo;
    /** 支付方式：支付宝 / 微信支付 / 银行卡 */
    private String payMethod;
    /** 银行卡支付：发卡银行 */
    private String payBank;
    /** 银行卡支付：完整卡号（仅支付服务可见，服务端只保存尾号 4 位） */
    private String payCardNo;

    public PaymentRequest() {
    }

    public PaymentRequest(String orderNo, String payMethod, String payBank, String payCardNo) {
        this.orderNo = orderNo;
        this.payMethod = payMethod;
        this.payBank = payBank;
        this.payCardNo = payCardNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
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

    public String getPayCardNo() {
        return payCardNo;
    }

    public void setPayCardNo(String payCardNo) {
        this.payCardNo = payCardNo;
    }
}
