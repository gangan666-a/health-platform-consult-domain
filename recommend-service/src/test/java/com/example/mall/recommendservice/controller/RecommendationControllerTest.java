package com.example.mall.recommendservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.common.model.RecommendationResponse;
import com.example.mall.recommendservice.service.RecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口测试：POST /api/recommendations
 * 覆盖正常 200 结构、非法 JSON → 400 错误体、空目录/空已购 → 200 空 items、500 不回显内部信息。
 */
@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationService recommendationService;

    @Test
    @DisplayName("POST 正常请求 → 200，返回 items 结构")
    void post_validBody_returns200WithItems() throws Exception {
        RecommendationResponse resp = new RecommendationResponse();
        RecommendationItemDTO it = new RecommendationItemDTO();
        it.setProductId(2L);
        it.setProductName("AirPods Pro");
        it.setPrice(new BigDecimal("1899"));
        it.setSource("ai");
        it.setReason("搭配推荐");
        resp.setItems(List.of(it));
        when(recommendationService.recommend(any(RecommendRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"complement",
                                 "purchasedItems":[{"productId":1,"productName":"iPhone 15 Pro"}],
                                 "catalog":[{"id":1,"name":"iPhone 15 Pro","category":"手机","price":7999}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("AirPods Pro"))
                .andExpect(jsonPath("$.items[0].source").value("ai"));
    }

    @Test
    @DisplayName("请求体非法 JSON → 400，错误体 {\"message\":...}，不回带请求体")
    void post_invalidJson_returns400_message() throws Exception {
        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"similar\",\"catalog\":["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    @DisplayName("请求体缺必填字段（无 catalog/purchasedItems）→ 400 错误体 {\"message\":...}")
    void post_missingFields_returns400_message() throws Exception {
        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"similar\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    @DisplayName("catalog 为空 → 200 空 items（不 500）")
    void post_emptyCatalog_returns200Empty() throws Exception {
        RecommendationResponse resp = new RecommendationResponse();
        resp.setItems(List.of());
        when(recommendationService.recommend(any(RecommendRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"complement\",\"catalog\":[],\"purchasedItems\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("purchasedItems 为空 → 200（不 500）")
    void post_emptyPurchasedItems_returns200() throws Exception {
        RecommendationResponse resp = new RecommendationResponse();
        resp.setItems(List.of());
        when(recommendationService.recommend(any(RecommendRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"similar\",\"catalog\":[{\"id\":1,\"name\":\"iPhone\"}],\"purchasedItems\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("服务抛异常 → 500，错误体为固定通用文案，不回显内部信息（含敏感字段）")
    void post_serviceThrows_returns500_fixedMessage() throws Exception {
        when(recommendationService.recommend(any(RecommendRequest.class)))
                .thenThrow(new RuntimeException("secret db password=root:123456"));

        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"complement",
                                 "purchasedItems":[{"productId":1,"productName":"iPhone 15 Pro"}],
                                 "catalog":[{"id":1,"name":"iPhone 15 Pro","category":"手机","price":7999}]}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("推荐服务内部错误，请稍后重试"));
    }

    @Test
    @DisplayName("业务异常 → 400，message 透传")
    void post_businessException_returns400() throws Exception {
        when(recommendationService.recommend(any(RecommendRequest.class)))
                .thenThrow(new BusinessException("参数非法"));

        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"complement",
                                 "purchasedItems":[{"productId":1,"productName":"iPhone 15 Pro"}],
                                 "catalog":[{"id":1,"name":"iPhone 15 Pro","category":"手机","price":7999}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数非法"));
    }
}
