package com.example.mall.orderservice.client;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.common.model.RecommendationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 推荐服务调用方（order-service → recommend-service）。
 * <p>
 * 与 ProductClient 不同：这里是<b>容错边界</b>——推荐服务不可用 / 返回异常一律记日志并返回空列表，
 * 绝不让 AI 导购区拖垮支付成功页。页面用 th:if 判断空列表隐藏区块。
 */
@Component
public class RecommendClient {

    private static final Logger log = LoggerFactory.getLogger(RecommendClient.class);

    private final RestClient restClient;

    public RecommendClient(@Value("${mall.recommend-service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 互补推荐（支付成功页「你可能还需要」）：永不抛异常，失败时记日志并返回空列表 */
    public List<RecommendationItemDTO> recommend(List<OrderItemDTO> purchasedItems, List<ProductDTO> catalog) {
        return call(purchasedItems, catalog, "complement");
    }

    /** 同类推荐（订单详情页 / 支付成功页「同类商品」）：语义同上，走同类模式 */
    public List<RecommendationItemDTO> recommendSimilar(List<OrderItemDTO> purchasedItems, List<ProductDTO> catalog) {
        return call(purchasedItems, catalog, "similar");
    }

    private List<RecommendationItemDTO> call(List<OrderItemDTO> purchasedItems, List<ProductDTO> catalog, String type) {
        RecommendRequest request = new RecommendRequest();
        request.setType(type);
        request.setPurchasedItems(purchasedItems);
        request.setCatalog(catalog);
        try {
            ResponseEntity<RecommendationResponse> resp = restClient.post()
                    .uri("/api/recommendations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(RecommendationResponse.class);
            RecommendationResponse body = resp.getBody();
            return body == null ? List.of() : body.getItems();
        } catch (Exception ex) {
            log.warn("推荐服务不可用，忽略本次推荐(type={}): {}", type, ex.toString());
            return List.of();
        }
    }
}
