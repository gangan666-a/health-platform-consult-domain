package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 处方恢复请求（order-service → consult-service）。
 * <p>
 * <b>契约固定</b>：处方号走路径（POST /api/consult/prescriptions/{no}/restore），
 * 本 body 仅携带 {@code orderNo}。restore CAS `WHERE status='USED' AND consumed_order_no=:orderNo`
 * ——只有「本次消费的订单」才能恢复，缩小爆炸半径（P2-03）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestorePrescriptionRequest {

    private String orderNo;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}
