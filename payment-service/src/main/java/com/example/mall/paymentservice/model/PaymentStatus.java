package com.example.mall.paymentservice.model;

/**
 * 支付流水状态
 */
public enum PaymentStatus {

    /** 支付处理中 */
    PROCESSING,
    /** 支付成功 */
    SUCCESS,
    /** 支付失败（如订单状态冲突、回调订单服务失败） */
    FAILED
}
