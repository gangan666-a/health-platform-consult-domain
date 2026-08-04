package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * 单条智能推荐（recommend-service → order-service）：
 * 自包含商品展示字段，order-service 无需再回查商品服务即可渲染推荐卡片。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationItemDTO {

    private Long productId;
    private String productName;
    private BigDecimal price;
    /** 商品图片：可为 emoji 或 http 图片链接 */
    private String imageUrl;
    /** 推荐理由（AI 生成或规则模板生成） */
    private String reason;
    /** 推荐来源：ai = DeepSeek 大模型；rule = 关键词互补规则回退 */
    private String source;

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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
