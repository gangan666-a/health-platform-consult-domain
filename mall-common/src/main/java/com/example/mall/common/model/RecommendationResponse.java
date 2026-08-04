package com.example.mall.common.model;

import java.util.List;

/**
 * AI 智能导购响应（recommend-service → order-service）：推荐商品列表。
 */
public class RecommendationResponse {

    private List<RecommendationItemDTO> items;

    public List<RecommendationItemDTO> getItems() {
        return items;
    }

    public void setItems(List<RecommendationItemDTO> items) {
        this.items = items;
    }
}
