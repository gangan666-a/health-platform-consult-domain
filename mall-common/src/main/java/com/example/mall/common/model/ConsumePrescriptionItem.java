package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 处方消费请求的明细（下单时携带），用于「药品与数量匹配」校验。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsumePrescriptionItem {

    private Long productId;
    private Integer quantity;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
