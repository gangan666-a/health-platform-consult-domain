package com.example.mall.paymentservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.PaymentDTO;
import com.example.mall.common.model.PaymentRequest;
import com.example.mall.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentController 接口测试：@WebMvcTest + @MockBean(PaymentService)。
 * 覆盖：发起支付响应结构、业务异常 400 错误体、非法 JSON / 500 不回显内部异常、查询流水。
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    private PaymentDTO successDto;

    @BeforeEach
    void setUp() {
        successDto = new PaymentDTO();
        successDto.setId(1L);
        successDto.setOrderNo("SO202608061000011234");
        successDto.setAmount(new BigDecimal("15998.00"));
        successDto.setPayMethod("银行卡");
        successDto.setPayBank("招商银行");
        successDto.setPayCardTail("5678");
        successDto.setStatus("SUCCESS");
        successDto.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 1, 0));
        successDto.setPaidAt(LocalDateTime.of(2026, 8, 6, 10, 1, 5));
    }

    private static final String VALID_BODY =
            "{\"orderNo\":\"SO202608061000011234\",\"payMethod\":\"银行卡\","
                    + "\"payBank\":\"招商银行\",\"payCardNo\":\"6225 8888 1234 5678\"}";

    // ---------- POST /api/payments ----------

    @Test
    void pay_validRequest_returns200AndDtoStructure() throws Exception {
        when(paymentService.executePayment(any(PaymentRequest.class))).thenReturn(successDto);

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("SO202608061000011234"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(15998.00))
                .andExpect(jsonPath("$.payMethod").value("银行卡"))
                .andExpect(jsonPath("$.payCardTail").value("5678"))
                .andExpect(jsonPath("$.payBank").value("招商银行"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void pay_duplicateBusinessException_returns400Message() throws Exception {
        when(paymentService.executePayment(any(PaymentRequest.class)))
                .thenThrow(new BusinessException("该订单已发起过支付，请勿重复操作"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该订单已发起过支付，请勿重复操作"));
    }

    @Test
    void pay_orderPaidBusinessException_returns400Message() throws Exception {
        when(paymentService.executePayment(any(PaymentRequest.class)))
                .thenThrow(new BusinessException("订单已支付，请勿重复支付"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("订单已支付，请勿重复支付"));
    }

    @Test
    void pay_malformedJson_returnsSanitized500_notEcho() throws Exception {
        // 请求体非法 JSON → HttpMessageNotReadableException → 通用兜底
        // 生产返回 500 + 固定文案（不回显异常详情/请求体，满足 P2-04）。
        // 注：按 API 契约 §3.0，请求体非法应 400，实际 500，见报告。
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"orderNo\": \"SO1\", \"payCardNo\": \"6225 8888 1234 5678\" notjson"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("支付服务内部错误，请稍后重试"));
    }

    @Test
    void pay_genericRuntimeException_returns500Sanitized() throws Exception {
        // 业务逻辑内部异常（非 BusinessException）→ 500，且不回显内部异常（含卡号风险）
        when(paymentService.executePayment(any(PaymentRequest.class)))
                .thenThrow(new RuntimeException("Internal secret 6225 8888 1234 5678"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("支付服务内部错误，请稍后重试"));
    }

    @Test
    void pay_missingField_deserializesToNull_andCallsService() throws Exception {
        // 无 Bean Validation：缺 orderNo 时对象字段为 null，仍走业务层（服务端不校验必填）
        when(paymentService.executePayment(any(PaymentRequest.class))).thenReturn(successDto);

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\"}"))
                .andExpect(status().isOk());
    }

    // ---------- GET /api/payments/{orderNo} ----------

    @Test
    void getByOrderNo_found_returns200() throws Exception {
        when(paymentService.getPaymentByOrderNo("SO202608061000011234")).thenReturn(successDto);

        mockMvc.perform(get("/api/payments/SO202608061000011234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderNo").value("SO202608061000011234"));
    }

    @Test
    void getByOrderNo_notFound_returns400BusinessBody() throws Exception {
        // 设计 §3.3 / EX-16：查询不存在 → 400 + {"message":"支付记录不存在"}（非 404）
        when(paymentService.getPaymentByOrderNo("NOPE")).thenThrow(new BusinessException("支付记录不存在"));

        mockMvc.perform(get("/api/payments/NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("支付记录不存在"));
    }
}
