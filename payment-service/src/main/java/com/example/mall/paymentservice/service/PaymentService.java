package com.example.mall.paymentservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.PaymentDTO;
import com.example.mall.common.model.PaymentRequest;
import com.example.mall.paymentservice.client.OrderClient;
import com.example.mall.paymentservice.model.Payment;
import com.example.mall.paymentservice.model.PaymentStatus;
import com.example.mall.paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 模拟支付：校验订单 → 记录流水 → 回调订单服务确认 → 置成功。
 * 跨服务无法保证强一致，回调失败会把流水置为 FAILED 并上抛，避免永久卡在 PROCESSING。
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderClient orderClient;

    public PaymentService(PaymentRepository paymentRepository, OrderClient orderClient) {
        this.paymentRepository = paymentRepository;
        this.orderClient = orderClient;
    }

    @Transactional
    public PaymentDTO executePayment(PaymentRequest request) {
        String orderNo = request.getOrderNo();
        OrderDTO order = orderClient.getOrder(orderNo); // 订单不存在 → 订单服务抛异常

        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessException("订单已支付，请勿重复支付");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("订单已取消，无法支付");
        }
        // 防重复支付：已存在成功/处理中的流水则拒绝（失败的流水允许重试）
        boolean alreadyCharged = paymentRepository.findByOrderNo(orderNo)
                .filter(p -> p.getStatus() != PaymentStatus.FAILED)
                .isPresent();
        if (alreadyCharged) {
            throw new BusinessException("该订单已发起过支付，请勿重复操作");
        }

        String cardTail = computeCardTail(request.getPayCardNo());

        Payment payment = new Payment();
        payment.setOrderNo(orderNo);
        payment.setAmount(order.getTotalAmount());
        payment.setPayMethod(request.getPayMethod());
        if (request.getPayBank() != null && !request.getPayBank().isBlank()) {
            payment.setPayBank(request.getPayBank());
        }
        payment.setPayCardTail(cardTail);
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // 回调订单服务，把订单置为已支付
        try {
            LocalDateTime paidAt = LocalDateTime.now();
            ConfirmPaymentRequest confirm = new ConfirmPaymentRequest(
                    request.getPayMethod(), payment.getPayBank(), cardTail, paidAt);
            orderClient.confirmPayment(orderNo, confirm);
        } catch (RuntimeException ex) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailReason(ex.getMessage());
            paymentRepository.save(payment);
            throw ex;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now());
        return toDTO(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentDTO getPaymentByOrderNo(String orderNo) {
        Payment payment = paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException("支付记录不存在"));
        return toDTO(payment);
    }

    /** 仅保存卡号尾号 4 位，不保存完整卡号 */
    private String computeCardTail(String payCardNo) {
        if (payCardNo == null) {
            return null;
        }
        String digits = payCardNo.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    private PaymentDTO toDTO(Payment payment) {
        PaymentDTO dto = new PaymentDTO();
        dto.setId(payment.getId());
        dto.setOrderNo(payment.getOrderNo());
        dto.setAmount(payment.getAmount());
        dto.setPayMethod(payment.getPayMethod());
        dto.setPayBank(payment.getPayBank());
        dto.setPayCardTail(payment.getPayCardTail());
        dto.setStatus(payment.getStatus() == null ? null : payment.getStatus().name());
        dto.setCreatedAt(payment.getCreatedAt());
        dto.setPaidAt(payment.getPaidAt());
        return dto;
    }
}
