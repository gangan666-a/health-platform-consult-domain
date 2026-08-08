package com.example.mall.orderservice.api;

import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.CreateConsultSessionRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.WritePrescriptionRequest;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.orderservice.client.ConsultClient;
import com.example.mall.orderservice.consult.ConsultSessionIndex;
import com.example.mall.orderservice.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单服务 REST API（供支付服务调用 / 问诊前端调用，返回 JSON）
 */
@RestController
@RequestMapping("/api/orders")
public class OrderApiController {

    private final OrderService orderService;
    private final ConsultClient consultClient;
    private final ConsultSessionIndex consultSessionIndex;

    public OrderApiController(OrderService orderService,
                              ConsultClient consultClient,
                              ConsultSessionIndex consultSessionIndex) {
        this.orderService = orderService;
        this.consultClient = consultClient;
        this.consultSessionIndex = consultSessionIndex;
    }

    /** 订单详情（支付服务发起支付前校验用） */
    @GetMapping("/{orderNo}")
    public OrderDTO getOrder(@PathVariable String orderNo) {
        return orderService.getOrderDTO(orderNo);
    }

    /** 支付确认回调（支付服务完成模拟扣款后调用，将订单置为已支付） */
    @PostMapping("/{orderNo}/confirm-payment")
    public OrderDTO confirmPayment(@PathVariable String orderNo,
                                   @RequestBody ConfirmPaymentRequest request) {
        return orderService.confirmPayment(orderNo, request);
    }

    /* ---------- 问诊/处方转发（BFF 集成 F5，页面 → consult-service） ---------- */

    /** 创建/复用问诊会话：返回会话 DTO（含用户 WS token），并登记 BFF 索引供医生工作台/聊天页使用 */
    @PostMapping("/consult/sessions")
    public ConsultSessionDTO createConsultSession(@RequestBody CreateConsultSessionRequest request) {
        ConsultSessionDTO dto = consultClient.createSession(request.getProductId(), request.getProductName());
        consultSessionIndex.upsert(dto.getSessionId(), dto.getProductId(), dto.getProductName(), dto.getToken());
        return dto;
    }

    /** 医生开方：转发给问诊服务（ONGOING→PRESCRIPTION_ISSUED），并回写索引状态 */
    @PostMapping("/consult/prescriptions")
    public PrescriptionDTO writePrescription(@RequestBody WritePrescriptionRequest request) {
        PrescriptionDTO dto = consultClient.prescribe(request.getSessionId(), request.getItems(), request.getNote());
        consultSessionIndex.updateStatus(request.getSessionId(), ConsultationStatus.PRESCRIPTION_ISSUED);
        return dto;
    }

    /** 消息历史（问诊页断线重连增量补拉） */
    @GetMapping("/consult/sessions/{id}/messages")
    public List<ChatMessageDTO> consultMessages(@PathVariable Long id,
                                                @RequestParam(required = false) Integer afterSeq) {
        return consultClient.getMessages(id, afterSeq);
    }

    /** 处方详情（问诊页凭处方号取明细/数量，构建去下单跳转） */
    @GetMapping("/consult/prescriptions/{no}")
    public PrescriptionDTO consultPrescription(@PathVariable String no) {
        return consultClient.getPrescription(no);
    }
}
