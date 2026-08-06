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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 模拟支付：校验订单 → 记录流水 → 回调订单服务确认 → 置成功。
 * 跨服务无法保证强一致，回调失败会把流水置为 FAILED 并上抛，避免永久卡在 PROCESSING。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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
        // 进入支付逻辑前校验金额：null 或 ≤0 抛独立金额异常（评审 P1 / PRD EX-6），
        // 绝不落入后续入库路径被误报成"重复支付"
        BigDecimal amount = order.getTotalAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单金额异常，无法支付");
        }
        String cardTail = computeCardTail(request.getPayCardNo());

        // 防重复支付 + FAILED 重试：
        // - 已存在 SUCCESS/PROCESSING 流水 → 拒绝（幂等防重，返回业务错误 400 而非 500）
        // - 已存在 FAILED 流水 → 复用该行（回填支付信息并重置为 PROCESSING），
        //   不新增第二行，规避 order_no 唯一约束对"失败可重试"的阻断（评审 P0-01）
        Payment payment = paymentRepository.findByOrderNo(orderNo).orElse(null);
        if (payment != null) {
            if (payment.getStatus() != PaymentStatus.FAILED) {
                throw new BusinessException("该订单已发起过支付，请勿重复操作");
            }
            fillPayment(payment, request, order, cardTail);
            payment.setStatus(PaymentStatus.PROCESSING);
            payment.setFailReason(null);
        } else {
            payment = new Payment();
            payment.setOrderNo(orderNo);
            fillPayment(payment, request, order, cardTail);
            // 关键：saveAndFlush 之前必须先置 status，否则 flush 时 status=null 触发
            // NOT NULL 约束 → DataIntegrityViolationException 被误判为重复支付（P0-01）
            payment.setStatus(PaymentStatus.PROCESSING);
            payment.setFailReason(null);
            payment.setCreatedAt(LocalDateTime.now());
            try {
                // saveAndFlush 立即落库，让唯一约束在远程回调前生效
                paymentRepository.saveAndFlush(payment);
            } catch (DataIntegrityViolationException ex) {
                if (isUniqueConstraintViolation(ex)) {
                    // 并发首付败者：唯一键兜底 → 业务错误 400，而非 500
                    throw new BusinessException("该订单已发起过支付，请勿重复操作");
                }
                // 其他约束违规（如非空/精度）：参数或状态错误，不回显内部细节
                log.error("支付流水写入失败，orderNo={}", orderNo, ex);
                throw new BusinessException("支付数据异常，请稍后重试");
            }
        }

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

    /**
     * 判定约束违规是否为"唯一约束冲突"（= 真重复支付），映射为业务 400；
     * 其余约束违规（非空、精度等）视为参数/状态错误，统一走通用文案 + 服务端日志。
     */
    private boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage();
        Throwable mostSpecific = ex.getMostSpecificCause();
        if (mostSpecific != null && mostSpecific.getMessage() != null) {
            msg = mostSpecific.getMessage();
        }
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("unique") || lower.contains("uk_")
                || lower.contains("duplicate") || lower.contains("23505");
    }

    @Transactional(readOnly = true)
    public PaymentDTO getPaymentByOrderNo(String orderNo) {
        Payment payment = paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException("支付记录不存在"));
        return toDTO(payment);
    }

    /** 回填支付信息（新建流水与 FAILED 复用共用同一逻辑） */
    private void fillPayment(Payment payment, PaymentRequest request, OrderDTO order, String cardTail) {
        payment.setAmount(order.getTotalAmount());
        payment.setPayMethod(request.getPayMethod());
        payment.setPayBank(request.getPayBank() != null && !request.getPayBank().isBlank()
                ? request.getPayBank() : null);
        payment.setPayCardTail(cardTail);
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
