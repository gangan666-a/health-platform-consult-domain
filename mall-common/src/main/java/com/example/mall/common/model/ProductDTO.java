package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品（product-service → order-service / 前端）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDTO {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    /** 商品图片：可为 emoji 或 http 图片链接 */
    private String imageUrl;
    /** 品类（同类商品推荐依据；老数据可能为 null） */
    private String category;

    /**
     * 是否处方药（Rx）：true=处方药，false=OTC 非处方药（默认）。
     * fail-closed：老商品 / 缺失 Rx 标记一律按 OTC 处理，绝不允许被误判为处方药（PRD FR-D1）。
     */
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
