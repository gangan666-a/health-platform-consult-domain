package com.example.mall.orderservice.api;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.common.model.WritePrescriptionRequest;
import com.example.mall.orderservice.client.ConsultClient;
import com.example.mall.orderservice.consult.ConsultSessionIndex;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @MockBean
    private ConsultClient consultClient;

    @MockBean
    private ConsultSessionIndex consultSessionIndex;

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

    /* ---------- 问诊/处方转发（BFF 集成 F5） ---------- */

    /** 创建/复用问诊会话：返回会话 DTO 并登记 BFF 索引 */
    @Test
    void createConsultSession_ok_returnsSessionAndIndexes() throws Exception {
        ConsultSessionDTO dto = new ConsultSessionDTO();
        dto.setSessionId(1L);
        dto.setToken("token-user-1");
        dto.setProductId(7L);
        dto.setProductName("布洛芬");
        dto.setStatus(ConsultationStatus.WAITING);
        when(consultClient.createSession(7L, "布洛芬")).thenReturn(dto);

        mockMvc.perform(post("/api/orders/consult/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":7,\"productName\":\"布洛芬\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1))
                .andExpect(jsonPath("$.token").value("token-user-1"))
                .andExpect(jsonPath("$.status").value("WAITING"));

        verify(consultSessionIndex).upsert(1L, 7L, "布洛芬", "token-user-1");
    }

    /** 医生开方：转发给问诊服务并回写索引状态 PRESCRIPTION_ISSUED */
    @Test
    void writePrescription_ok_forwardsAndUpdatesIndex() throws Exception {
        PrescriptionDTO dto = new PrescriptionDTO();
        dto.setNo("RX202608071234567890");
        dto.setSessionId(1L);
        dto.setStatus(PrescriptionStatus.ACTIVE);
        when(consultClient.prescribe(eq(1L), any(), nullable(String.class))).thenReturn(dto);

        mockMvc.perform(post("/api/orders/consult/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"items\":[{\"productId\":7,\"productName\":\"布洛芬\","
                                + "\"dosage\":\"每次 1 粒\",\"frequency\":\"每日 3 次\",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("RX202608071234567890"));

        verify(consultSessionIndex).updateStatus(1L, ConsultationStatus.PRESCRIPTION_ISSUED);
    }

    /** consult 服务不可用 → 创建会话 fail-closed：400 业务错误、不登记索引（AC-IM-6 / NFR-11） */
    @Test
    void createConsultSession_consultUnavailable_returns400AndDoesNotIndex() throws Exception {
        when(consultClient.createSession(7L, "阿莫西林胶囊"))
                .thenThrow(new BusinessException("问诊服务不可用，请稍后重试"));

        mockMvc.perform(post("/api/orders/consult/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":7,\"productName\":\"阿莫西林胶囊\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问诊服务不可用，请稍后重试"));

        // fail-closed：失败时不登记索引，页面不会误跳转
        verify(consultSessionIndex, never()).upsert(anyLong(), anyLong(), anyString(), anyString());
    }

    /** 消息历史转发：正常返回列表（seq 由 consult 保证，BFF 透传） */
    @Test
    void consultMessages_ok_forwardsToConsult() throws Exception {
        ChatMessageDTO msg = new ChatMessageDTO();
        msg.setSeq(1);
        msg.setContent("咳嗽三天");
        when(consultClient.getMessages(1L, null)).thenReturn(List.of(msg));

        mockMvc.perform(get("/api/orders/consult/sessions/1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].content").value("咳嗽三天"));
    }

    /** 消息历史：consult 不可用 → 400 业务错误体（不白屏） */
    @Test
    void consultMessages_consultUnavailable_returns400() throws Exception {
        when(consultClient.getMessages(1L, null))
                .thenThrow(new BusinessException("问诊服务不可用，请稍后重试"));

        mockMvc.perform(get("/api/orders/consult/sessions/1/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问诊服务不可用，请稍后重试"));
    }

    /** 处方详情转发：正常返回（问诊页凭处方号回显） */
    @Test
    void consultPrescription_ok_returnsPrescription() throws Exception {
        PrescriptionDTO dto = new PrescriptionDTO();
        dto.setNo("RX202608071234567890");
        dto.setStatus(PrescriptionStatus.ACTIVE);
        when(consultClient.getPrescription("RX202608071234567890")).thenReturn(dto);

        mockMvc.perform(get("/api/orders/consult/prescriptions/RX202608071234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("RX202608071234567890"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** 处方详情：伪造/不存在 → 400 业务错误体透传（EX-IM-5） */
    @Test
    void consultPrescription_notFound_returns400() throws Exception {
        when(consultClient.getPrescription("RX-FAKE"))
                .thenThrow(new BusinessException("处方不存在或无效"));

        mockMvc.perform(get("/api/orders/consult/prescriptions/RX-FAKE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("处方不存在或无效"));
    }
}
