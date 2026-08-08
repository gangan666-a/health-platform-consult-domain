package com.example.mall.consultservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.consultservice.service.ConsultationSessionService;
import com.example.mall.consultservice.service.MessageService;
import com.example.mall.consultservice.service.PrescriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ConsultationController 接口测试（@WebMvcTest + MockMvc，统一 {@code {"message":...}} 错误体）。
 * 覆盖全部 REST 端点：会话创建/详情/接单/结束/消息历史/重签 token + 处方开具/消费/恢复/查询，
 * 业务异常→400 精确文案，未知异常→500 不泄露内部细节。
 */
@WebMvcTest(ConsultationController.class)
@DisplayName("问诊 REST 接口测试（@WebMvcTest）")
class ConsultationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConsultationSessionService sessionService;

    @MockBean
    private MessageService messageService;

    @MockBean
    private PrescriptionService prescriptionService;

    private ConsultSessionDTO sessionDTO(Long id, ConsultationStatus status) {
        ConsultSessionDTO dto = new ConsultSessionDTO();
        dto.setSessionId(id);
        dto.setToken("TOKEN-X");
        dto.setStatus(status);
        dto.setStatusLabel(status.getLabel());
        dto.setProductId(1L);
        dto.setProductName("阿莫西林胶囊");
        dto.setPatientName("演示患者");
        return dto;
    }

    private PrescriptionDTO prescriptionDTO(String no) {
        PrescriptionDTO dto = new PrescriptionDTO();
        dto.setNo(no);
        dto.setSessionId(1L);
        dto.setStatus(PrescriptionStatus.ACTIVE);
        dto.setStatusLabel("有效");
        dto.setValidUntil(LocalDateTime.now().plusDays(7));
        return dto;
    }

    // ==================== 会话 ====================

    @Test
    @DisplayName("创建/复用会话 → 200 返回 token 与会话状态")
    void createSession() throws Exception {
        when(sessionService.createOrReuse(any())).thenReturn(sessionDTO(1L, ConsultationStatus.WAITING));
        mockMvc.perform(post("/api/consult/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"productName\":\"阿莫西林胶囊\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1))
                .andExpect(jsonPath("$.token").value("TOKEN-X"))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("创建会话：商品信息不完整 → 400 精确文案")
    void createSession_incomplete() throws Exception {
        when(sessionService.createOrReuse(any()))
                .thenThrow(new BusinessException("商品信息不完整"));
        mockMvc.perform(post("/api/consult/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":null,\"productName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品信息不完整"));
    }

    @Test
    @DisplayName("会话详情 → 200")
    void detail() throws Exception {
        when(sessionService.getSession(1L)).thenReturn(sessionDTO(1L, ConsultationStatus.ONGOING));
        mockMvc.perform(get("/api/consult/sessions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1))
                .andExpect(jsonPath("$.status").value("ONGOING"));
    }

    @Test
    @DisplayName("会话详情：不存在 → 400 会话不存在")
    void detail_notFound() throws Exception {
        when(sessionService.getSession(1L)).thenThrow(new BusinessException("会话不存在"));
        mockMvc.perform(get("/api/consult/sessions/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    @DisplayName("医生接单 → 200 返回医生 token")
    void accept() throws Exception {
        when(sessionService.accept(1L, "d1", "医生A")).thenReturn(sessionDTO(1L, ConsultationStatus.ONGOING));
        mockMvc.perform(post("/api/consult/sessions/1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorId\":\"d1\",\"doctorName\":\"医生A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONGOING"))
                .andExpect(jsonPath("$.doctorName").isEmpty());
    }

    @Test
    @DisplayName("接单：医生身份缺失（空 body）→ 400")
    void accept_missingDoctor() throws Exception {
        when(sessionService.accept(eq(1L), isNull(), isNull()))
                .thenThrow(new BusinessException("医生身份缺失"));
        mockMvc.perform(post("/api/consult/sessions/1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("医生身份缺失"));
    }

    @Test
    @DisplayName("接单：重复接单 → 400 会话已被接单")
    void accept_duplicate() throws Exception {
        when(sessionService.accept(1L, "d1", "医生A"))
                .thenThrow(new BusinessException("会话已被接单，无法重复接单"));
        mockMvc.perform(post("/api/consult/sessions/1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorId\":\"d1\",\"doctorName\":\"医生A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("会话已被接单，无法重复接单"));
    }

    @Test
    @DisplayName("结束：无 body → 默认 reason=USER_ENDED")
    void end_defaultReason() throws Exception {
        when(sessionService.end(1L, "USER_ENDED")).thenReturn(sessionDTO(1L, ConsultationStatus.ENDED));
        mockMvc.perform(post("/api/consult/sessions/1/end")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        verify(sessionService).end(1L, "USER_ENDED");
    }

    @Test
    @DisplayName("结束：带 reason=DOCTOR_ENDED → 透传")
    void end_withReason() throws Exception {
        when(sessionService.end(1L, "DOCTOR_ENDED")).thenReturn(sessionDTO(1L, ConsultationStatus.ENDED));
        mockMvc.perform(post("/api/consult/sessions/1/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"DOCTOR_ENDED\"}"))
                .andExpect(status().isOk());
        verify(sessionService).end(1L, "DOCTOR_ENDED");
    }

    @Test
    @DisplayName("消息历史 → 200 返回列表")
    void messages() throws Exception {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setSessionId(1L);
        dto.setSeq(1);
        dto.setContent("你好");
        when(messageService.history(1L, null)).thenReturn(List.of(dto));
        mockMvc.perform(get("/api/consult/sessions/1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].content").value("你好"));
    }

    @Test
    @DisplayName("消息历史：afterSeq 增量补拉 → 透传参数")
    void messages_afterSeq() throws Exception {
        when(messageService.history(1L, 5)).thenReturn(List.of());
        mockMvc.perform(get("/api/consult/sessions/1/messages").param("afterSeq", "5"))
                .andExpect(status().isOk());
        verify(messageService).history(1L, 5);
    }

    @Test
    @DisplayName("重签 token → 200 返回新 token")
    void wsToken() throws Exception {
        when(sessionService.refreshToken(1L)).thenReturn(sessionDTO(1L, ConsultationStatus.ONGOING));
        mockMvc.perform(post("/api/consult/sessions/1/ws-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("TOKEN-X"));
    }

    // ==================== 处方 ====================

    @Test
    @DisplayName("开方 → 200 返回处方 DTO")
    void prescribe() throws Exception {
        when(prescriptionService.prescribe(any())).thenReturn(prescriptionDTO("RX123"));
        mockMvc.perform(post("/api/consult/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"items\":[{\"productId\":1,\"productName\":\"阿莫西林胶囊\","
                                + "\"dosage\":\"0.5g\",\"frequency\":\"tid\",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("RX123"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("开方：会话标识缺失 → 400")
    void prescribe_sessionIdMissing() throws Exception {
        when(prescriptionService.prescribe(any()))
                .thenThrow(new BusinessException("会话标识缺失"));
        mockMvc.perform(post("/api/consult/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("会话标识缺失"));
    }

    @Test
    @DisplayName("开方：CAS 冲突 → 400 开方冲突，会话状态已变化")
    void prescribe_casConflict() throws Exception {
        when(prescriptionService.prescribe(any()))
                .thenThrow(new BusinessException("开方冲突，会话状态已变化"));
        mockMvc.perform(post("/api/consult/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"items\":[{\"productId\":1,\"productName\":\"阿莫西林胶囊\","
                                + "\"dosage\":\"0.5g\",\"frequency\":\"tid\",\"quantity\":2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("开方冲突，会话状态已变化"));
    }

    @Test
    @DisplayName("消费处方 → 200")
    void consume() throws Exception {
        PrescriptionDTO used = prescriptionDTO("RX123");
        used.setStatus(PrescriptionStatus.USED);
        used.setConsumedOrderNo("ORD1");
        when(prescriptionService.consume(any())).thenReturn(used);
        mockMvc.perform(post("/api/consult/prescriptions/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prescriptionNo\":\"RX123\",\"orderNo\":\"ORD1\","
                                + "\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.consumedOrderNo").value("ORD1"));
    }

    @Test
    @DisplayName("消费：orderNo 为空（P1-02）→ 400 订单号不能为空")
    void consume_missingOrderNo() throws Exception {
        when(prescriptionService.consume(any())).thenThrow(new BusinessException("订单号不能为空"));
        mockMvc.perform(post("/api/consult/prescriptions/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prescriptionNo\":\"RX123\",\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单号不能为空"));
    }

    @Test
    @DisplayName("补偿恢复 → 200")
    void restore() throws Exception {
        when(prescriptionService.restore("RX123", "ORD1")).thenReturn(prescriptionDTO("RX123"));
        mockMvc.perform(post("/api/consult/prescriptions/RX123/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("RX123"));
    }

    @Test
    @DisplayName("恢复：订单号为空 → 400 订单号不能为空")
    void restore_missingOrderNo() throws Exception {
        when(prescriptionService.restore(eq("RX123"), isNull()))
                .thenThrow(new BusinessException("订单号不能为空"));
        mockMvc.perform(post("/api/consult/prescriptions/RX123/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单号不能为空"));
    }

    @Test
    @DisplayName("处方查询 → 200")
    void prescriptionByNo() throws Exception {
        when(prescriptionService.getByNo("RX123")).thenReturn(prescriptionDTO("RX123"));
        mockMvc.perform(get("/api/consult/prescriptions/RX123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.no").value("RX123"));
    }

    @Test
    @DisplayName("按会话查处方列表 → 200")
    void sessionPrescriptions() throws Exception {
        when(prescriptionService.listBySession(1L)).thenReturn(List.of(prescriptionDTO("RX1")));
        mockMvc.perform(get("/api/consult/sessions/1/prescriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].no").value("RX1"));
    }

    // ==================== 统一错误体 ====================

    @Test
    @DisplayName("未知异常 → 500 统一错误体，不泄露内部细节")
    void internalError_notLeaked() throws Exception {
        when(sessionService.getSession(1L)).thenThrow(new RuntimeException("数据库连接池内部堆栈-机密"));
        mockMvc.perform(get("/api/consult/sessions/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }
}
