package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 药品（medicine-service → order-service / Agent 工具）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MedicineDTO {

    private Long id;
    private String name;
    /** 通用名 */
    private String genericName;
    private String description;
    /** 适应症 */
    private String indication;
    /** 剂型：片剂、胶囊、注射液等 */
    private String dosageForm;
    private BigDecimal price;
    private Integer stock;
    private String imageUrl;
    /** 品类：感冒、消化、心脑血管等 */
    private String category;
    /** 是否处方药（Rx） */
    private boolean rxFlag = false;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGenericName() {
        return genericName;
    }

    public void setGenericName(String genericName) {
        this.genericName = genericName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIndication() {
        return indication;
    }

    public void setIndication(String indication) {
        this.indication = indication;
    }

    public String getDosageForm() {
        return dosageForm;
    }

    public void setDosageForm(String dosageForm) {
        this.dosageForm = dosageForm;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isRxFlag() {
        return rxFlag;
    }

    public void setRxFlag(boolean rxFlag) {
        this.rxFlag = rxFlag;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
