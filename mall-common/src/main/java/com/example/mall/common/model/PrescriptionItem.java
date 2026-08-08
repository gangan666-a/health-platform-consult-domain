package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 处方明细：药品 + 用法用量 + 数量。
 * 处方可含多个药品（items JSON），药品必须与会话目标药品一致（演示环境为单药品）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrescriptionItem {

    private Long productId;
    private String productName;
    private String dosage;
    private String frequency;
    private Integer quantity;

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

    public String getDosage() {
        return dosage;
    }

    public void setDosage(String dosage) {
        this.dosage = dosage;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
