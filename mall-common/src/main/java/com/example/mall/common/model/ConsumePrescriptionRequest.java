package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 处方消费请求（order-service → consult-service）。
 * <p>
 * <b>P1-02</b>：必须携带 {@code orderNo}，与 CAS `consumed_order_no=:orderNo` 回写对齐，
 * 也作为 restore 的归属校验条件（P2-03 绑定订单号缩小恢复爆炸半径）。
 * 一次性消费由 CAS `WHERE status='ACTIVE'` 保证。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsumePrescriptionRequest {

    private String prescriptionNo;
    private String orderNo;
    private List<ConsumePrescriptionItem> items;

    public String getPrescriptionNo() {
        return prescriptionNo;
    }

    public void setPrescriptionNo(String prescriptionNo) {
        this.prescriptionNo = prescriptionNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public List<ConsumePrescriptionItem> getItems() {
        return items;
    }

    public void setItems(List<ConsumePrescriptionItem> items) {
        this.items = items;
    }
}
