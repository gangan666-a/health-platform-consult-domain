package com.example.mall.orderservice.api;

import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.orderservice.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单服务 REST API（供支付服务调用，返回 JSON）
 */
@RestController
@RequestMapping("/api/orders")
public class OrderApiController {

    private final OrderService orderService;

    public OrderApiController(OrderService orderService) {
        this.orderService = orderService;
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
}
