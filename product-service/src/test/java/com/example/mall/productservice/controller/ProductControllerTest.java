package com.example.mall.productservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.productservice.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProductController 接口测试（@WebMvcTest + @MockBean ProductService，MockMvc）。
 * 断言重点：状态码、错误体统一 {"message":"..."}、字段值。
 */
@WebMvcTest(ProductController.class)
@DisplayName("ProductController 接口测试")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    private ProductDTO dto(long id, int stock) {
        ProductDTO d = new ProductDTO();
        d.setId(id);
        d.setName("iPhone 15 Pro");
        d.setDescription("描述");
        d.setPrice(new BigDecimal("7999.00"));
        d.setStock(stock);
        d.setImageUrl("📱");
        d.setCategory("手机");
        d.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 0));
        return d;
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("GET /api/products 正常：200 与数组结构")
    void list_ok() throws Exception {
        when(productService.listProducts()).thenReturn(List.of(dto(1L, 5), dto(2L, 8)));
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$[0].stock").value(5))
                .andExpect(jsonPath("$[0].category").value("手机"));
    }

    // ==================== 详情 ====================

    @Test
    @DisplayName("GET /api/products/{id} 正常：200 返回详情")
    void detail_ok() throws Exception {
        when(productService.getProduct(1L)).thenReturn(dto(1L, 5));
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stock").value(5));
    }

    @Test
    @DisplayName("GET /api/products/{id} 详情 JSON 序列化 rxFlag（Rx=true / OTC=false，PRD FR-D1）")
    void detail_rxFlagSerialized() throws Exception {
        ProductDTO rx = dto(1L, 5);
        rx.setRxFlag(true);
        when(productService.getProduct(1L)).thenReturn(rx);
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rxFlag").value(true));

        ProductDTO otc = dto(2L, 8);
        when(productService.getProduct(2L)).thenReturn(otc);
        mockMvc.perform(get("/api/products/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rxFlag").value(false));
    }

    @Test
    @DisplayName("GET /api/products/{id} 不存在：400 + {\"message\":\"商品不存在\"}（设计 §3.1 定 400；任务期望 404，记录为待确认）")
    void detail_notFound() throws Exception {
        when(productService.getProduct(999L)).thenThrow(new BusinessException("商品不存在"));
        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品不存在"));
    }

    // ==================== 扣减 ====================

    @Test
    @DisplayName("POST /api/products/{id}/stock/deduct 正常：200 返回扣减后商品（AC-FR3-1）")
    void deduct_ok() throws Exception {
        when(productService.deductStock(1L, 2)).thenReturn(dto(1L, 8));
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(8));
        verify(productService).deductStock(1L, 2);
    }

    @Test
    @DisplayName("POST deduct 缺 quantity 参数：必填 → 400 {\"message\":\"quantity 不能为空\"}（QA-P2 契约收紧，不再静默默认 1）")
    void deduct_missingQuantity_rejected() throws Exception {
        mockMvc.perform(post("/api/products/1/stock/deduct"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("quantity 不能为空"));
        verify(productService, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("POST deduct quantity=0 → 400 {\"message\":\"扣减数量必须大于 0\"}（EX-3 / AC-FR3-4）")
    void deduct_zeroQuantity() throws Exception {
        when(productService.deductStock(1L, 0)).thenThrow(new BusinessException("扣减数量必须大于 0"));
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减数量必须大于 0"));
    }

    @Test
    @DisplayName("POST deduct quantity=负数 → 400 {\"message\":\"扣减数量必须大于 0\"}（EX-3）")
    void deduct_negativeQuantity() throws Exception {
        when(productService.deductStock(1L, -5)).thenThrow(new BusinessException("扣减数量必须大于 0"));
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减数量必须大于 0"));
    }

    @Test
    @DisplayName("POST deduct quantity 非整数(1.5)：参数绑定失败 → 400 {\"message\":\"参数非法\"}")
    void deduct_nonIntegerQuantity() throws Exception {
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数非法"));
    }

    @Test
    @DisplayName("POST deduct quantity 非数字(abc) → 400 {\"message\":\"参数非法\"}")
    void deduct_nonNumericQuantity() throws Exception {
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数非法"));
    }

    @Test
    @DisplayName("POST deduct 库存不足 → 400 {\"message\":\"库存不足，当前库存仅 X 件\"}（EX-1 / AC-FR3-6）")
    void deduct_insufficientStock() throws Exception {
        when(productService.deductStock(1L, 5)).thenThrow(new BusinessException("库存不足，当前库存仅 2 件"));
        mockMvc.perform(post("/api/products/1/stock/deduct").param("quantity", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("库存不足，当前库存仅 2 件"));
    }

    @Test
    @DisplayName("POST deduct 商品不存在 → 400 {\"message\":\"商品不存在\"}（EX-5 / AC-FR3-5）")
    void deduct_notFound() throws Exception {
        when(productService.deductStock(999L, 1)).thenThrow(new BusinessException("商品不存在"));
        mockMvc.perform(post("/api/products/999/stock/deduct").param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品不存在"));
    }

    // ==================== 回补 ====================

    @Test
    @DisplayName("POST restore 正常：200 返回回补后商品（AC-FR3-2）")
    void restore_ok() throws Exception {
        when(productService.restoreStock(1L, 2)).thenReturn(dto(1L, 10));
        mockMvc.perform(post("/api/products/1/stock/restore").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(10));
        verify(productService).restoreStock(1L, 2);
    }

    @Test
    @DisplayName("POST restore 缺 quantity 参数：默认 1 → 200（同设计默认 1）")
    void restore_missingQuantity_usesDefault() throws Exception {
        when(productService.restoreStock(1L, 1)).thenReturn(dto(1L, 9));
        mockMvc.perform(post("/api/products/1/stock/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(9));
        verify(productService).restoreStock(1L, 1);
    }

    @Test
    @DisplayName("POST restore quantity=0 → 400 {\"message\":\"回补数量必须大于 0\"}")
    void restore_zeroQuantity() throws Exception {
        when(productService.restoreStock(1L, 0)).thenThrow(new BusinessException("回补数量必须大于 0"));
        mockMvc.perform(post("/api/products/1/stock/restore").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回补数量必须大于 0"));
    }

    @Test
    @DisplayName("POST restore 商品不存在 → 400 {\"message\":\"商品不存在\"}（EX-5）")
    void restore_notFound() throws Exception {
        when(productService.restoreStock(999L, 1)).thenThrow(new BusinessException("商品不存在"));
        mockMvc.perform(post("/api/products/999/stock/restore").param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品不存在"));
    }
}
