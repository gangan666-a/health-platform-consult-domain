package com.example.mall.productservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    /** 商品图片：可为 emoji 或 http 图片链接 */
    @Column(length = 300)
    private String imageUrl;

    /** 品类（同类商品推荐依据；老数据可能为 null） */
    @Column(length = 50)
    private String category;

    /**
     * 是否处方药（Rx）：true=处方药（须凭有效处方才能购买），false=OTC 非处方药（可普通购买）。
     * fail-closed 默认 false：老库缺失 Rx 标记 / ddl-auto=update 新列回填一律按 OTC 处理，
     * 绝不允许老数据被误判为处方药（PRD FR-D1 / AC-D1-5）。种子数据负责按商品名回填。
     */
    @Column(name = "rx_flag", nullable = false)
    private boolean rxFlag = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Product() {
    }

    public Product(String name, String description, BigDecimal price, int stock, String imageUrl) {
        this(name, description, price, stock, imageUrl, null);
    }

    public Product(String name, String description, BigDecimal price, int stock, String imageUrl, String category) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.imageUrl = imageUrl;
        this.category = category;
        this.createdAt = LocalDateTime.now();
    }

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
