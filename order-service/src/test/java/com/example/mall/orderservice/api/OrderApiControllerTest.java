package com.example.mall.orderservice.api;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.orderservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order-service REST API 接口测试（供 payment-service 回调）。
 * 契约：GET /api/orders/{orderNo}、POST /api/orders/{orderNo}/confirm-payment（幂等）。
 * 错误体统一 {"message":"..."}；业务异常返回 400。
 */
@WebMvcTest(OrderApiController.class)
class OrderApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    private OrderDTO orderDTO(OrderStatus status, String orderNo) {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo(orderNo);
        dto.setStatus(status);
        dto.setStatusLabel(status.getLabel());
        dto.setTotalAmount(new BigDecimal("1999.00"));
        dto.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 0));
        if (status == OrderStatus.PAID) {
            dto.setPayMethod("支付宝");
            dto.setPaidAt(LocalDateTime.of(2026, 8, 6, 10, 5));
        }
        OrderItemDTO item = new OrderItemDTO();
        item.setProductId(1L);
        item.setProductName("iPhone 15 Pro");
        item.setUnitPrice(new BigDecimal("1999.00"));
        item.setQuantity(1);
        item.setSubtotal(new BigDecimal("1999.00"));
        dto.setItems(List.of(item));
        return dto;
    }

    /* ---------- GET /api/orders/{orderNo} ---------- */

    @Test
    void getOrder_ok() throws Exception {
        when(orderService.getOrderDTO("SO202608061000011234"))
                .thenReturn(orderDTO(OrderStatus.PAID, "SO202608061000011234"));

        mockMvc.perform(get("/api/orders/SO202608061000011234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("SO202608061000011234"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.statusLabel").value("已支付"))
                .andExpect(jsonPath("$.totalAmount").value(1999.0))
                .andExpect(jsonPath("$.items[0].productId").value(1));
    }

    @Test
    void getOrder_notExists_returns400BusinessErrorBody() throws Exception {
        when(orderService.getOrderDTO("SO-NOT-EXIST"))
                .thenThrow(new BusinessException("订单不存在"));

        mockMvc.perform(get("/api/orders/SO-NOT-EXIST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    /* ---------- POST /api/orders/{orderNo}/confirm-payment ---------- */

    @Test
    void confirmPayment_ok_returnsPaidOrder() throws Exception {
        when(orderService.confirmPayment(eq("SO123"), any(ConfirmPaymentRequest.class)))
                .thenReturn(orderDTO(OrderStatus.PAID, "SO123"));

        mockMvc.perform(post("/api/orders/SO123/confirm-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\",\"payCardTail\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payMethod").value("支付宝"));
    }

    /** 幂等：重复回调返回 200，状态保持 PAID（契约层不 4xx） */
    @Test
    void confirmPayment_idempotent_repeatedCallback_returns200StatusUnchanged() throws Exception {
        when(orderService.confirmPayment(eq("SO123"), any(ConfirmPaymentRequest.class)))
                .thenReturn(orderDTO(OrderStatus.PAID, "SO123"));
        String body = "{\"payMethod\":\"支付宝\",\"payCardTail\":\"1234\"}";

        mockMvc.perform(post("/api/orders/SO123/confirm-payment")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 第二次（支付服务重试/乱序）仍 200 且状态不变
        mockMvc.perform(post("/api/orders/SO123/confirm-payment")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    /** 非法状态：已取消订单收到回调 → 4xx 业务错误 */
    @Test
    void confirmPayment_cancelledOrder_returns400BusinessError() throws Exception {
        when(orderService.confirmPayment(eq("SO-CANCELLED"), any(ConfirmPaymentRequest.class)))
                .thenThrow(new BusinessException("订单已取消，无法支付"));

        mockMvc.perform(post("/api/orders/SO-CANCELLED/confirm-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单已取消，无法支付"));
    }

    /** 未知订单回调 → 400 业务错误（非 500） */
    @Test
    void confirmPayment_orderNotExists_returns400BusinessError() throws Exception {
        when(orderService.confirmPayment(eq("SO-NOT-EXIST"), any(ConfirmPaymentRequest.class)))
                .thenThrow(new BusinessException("订单不存在"));

        mockMvc.perform(post("/api/orders/SO-NOT-EXIST/confirm-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }
}
