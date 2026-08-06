package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * AI 智能导购请求（order-service → recommend-service）：
 * 携带用户已购商品清单与全量商品目录，由推荐服务挑选商品。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendRequest {

    /** 推荐类型："complement"（互补，默认）/ "similar"（同类）；null 或空串按 complement 处理 */
    private String type;

    /** 已购商品（来自订单明细快照）；必填，但空列表为合法边界（返回 200 空） */
    @NotNull(message = "purchasedItems 不能为空")
    private List<OrderItemDTO> purchasedItems;

    /** 全量商品目录（用于限定推荐范围，避免模型推荐目录外商品）；必填，但空列表为合法边界 */
    @NotNull(message = "catalog 不能为空")
    private List<ProductDTO> catalog;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<OrderItemDTO> getPurchasedItems() {
        return purchasedItems;
    }

    public void setPurchasedItems(List<OrderItemDTO> purchasedItems) {
        this.purchasedItems = purchasedItems;
    }

    public List<ProductDTO> getCatalog() {
        return catalog;
    }

    public void setCatalog(List<ProductDTO> catalog) {
        this.catalog = catalog;
    }
}
