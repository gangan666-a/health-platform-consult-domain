package com.example.mall.paymentservice.controller;

import com.example.mall.common.model.PaymentDTO;
import com.example.mall.common.model.PaymentRequest;
import com.example.mall.paymentservice.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付服务 REST API
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 发起支付（order-service 前端调用） */
    @PostMapping
    public PaymentDTO pay(@RequestBody PaymentRequest request) {
        return paymentService.executePayment(request);
    }

    /** 查询某订单的支付流水 */
    @GetMapping("/{orderNo}")
    public PaymentDTO byOrderNo(@PathVariable String orderNo) {
        return paymentService.getPaymentByOrderNo(orderNo);
    }
}
