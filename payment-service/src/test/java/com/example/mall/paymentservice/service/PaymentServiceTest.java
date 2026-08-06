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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentService 单元测试：mock PaymentRepository + OrderClient。
 * 覆盖：正常发起、卡号尾号脱敏、金额精度、P0-01 FAILED 重试复用、已支付/已取消/重复发起拒绝、
 * 并发败者唯一约束映射 400、回调失败置 FAILED、状态流转、卡号/金额边界。
 *
 * 注意：mock 仓库不触发 DB NOT NULL 约束，因此"新建流水时 status 先于 saveAndFlush 赋值"这一
 * DB 层问题由 {@link #newPayment_isFlushedWithStatusPROCESSING()} 用 Answer 捕获 flush 时刻状态探测，
 * 并在功能测试（真实 H2）中复核。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String ORDER_NO = "SO202608061000011234";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderClient orderClient;

    @InjectMocks
    private PaymentService paymentService;

    // ---------- helpers ----------

    private OrderDTO createdOrder(String orderNo, BigDecimal amount) {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo(orderNo);
        dto.setStatus(OrderStatus.CREATED);
        dto.setTotalAmount(amount);
        return dto;
    }

    private PaymentRequest paymentRequest(String method, String bank, String cardNo) {
        PaymentRequest r = new PaymentRequest();
        r.setOrderNo(ORDER_NO);
        r.setPayMethod(method);
        r.setPayBank(bank);
        r.setPayCardNo(cardNo);
        return r;
    }

    private Payment existingPayment(Long id, PaymentStatus status) {
        Payment p = new Payment();
        p.setId(id);
        p.setOrderNo(ORDER_NO);
        p.setAmount(new BigDecimal("15998.00"));
        p.setPayMethod("支付宝");
        p.setPayBank(null);
        p.setPayCardTail(null);
        p.setStatus(status);
        p.setFailReason(status == PaymentStatus.FAILED ? "订单服务不可用，请稍后重试" : null);
        p.setCreatedAt(CREATED_AT);
        return p;
    }

    private void stubSuccessFlow(BigDecimal amount) {
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, amount));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.confirmPayment(anyString(), any(ConfirmPaymentRequest.class))).thenReturn(null);
    }

    private PaymentDTO runSuccess(PaymentRequest req, BigDecimal amount) {
        stubSuccessFlow(amount);
        return paymentService.executePayment(req);
    }

    // ---------- 正常发起 ----------

    @Test
    void firstPayment_success_persistsFields() {
        PaymentDTO result = runSuccess(paymentRequest("支付宝", null, null), new BigDecimal("15998.00"));

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(ORDER_NO, result.getOrderNo());
        assertEquals(new BigDecimal("15998.00").setScale(2), result.getAmount());
        assertEquals(2, result.getAmount().scale(), "金额应保持 2 位小数精度");
        assertNull(result.getPayCardTail());
        assertNull(result.getPayBank());
        verify(paymentRepository).saveAndFlush(any(Payment.class));
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void bankCard_firstPayment_savesOnlyLast4Tail() {
        PaymentDTO result = runSuccess(
                paymentRequest("银行卡", "招商银行", "6225 8888 1234 5678"),
                new BigDecimal("15998.00"));

        assertEquals("SUCCESS", result.getStatus());
        assertEquals("5678", result.getPayCardTail());
        assertEquals("招商银行", result.getPayBank());

        // 完整卡号不入库：实体无完整卡号字段，仅存尾号 4 位（卡号全链路脱敏）
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals("5678", captor.getValue().getPayCardTail());
        assertEquals("银行卡", captor.getValue().getPayMethod());
    }

    @Test
    void cardTail_stripsNonDigits_keepsLast4() {
        PaymentDTO result = runSuccess(
                paymentRequest("银行卡", "工商银行", "6225-8888-1234-5678"),
                new BigDecimal("100.00"));
        assertEquals("5678", result.getPayCardTail());
    }

    @Test
    void cardTail_shortOrNull_returnsNull_notSaved() {
        // 不足 4 位 → 不入库尾号（PRD EX-33/EX-35）
        PaymentDTO shortCard = runSuccess(
                paymentRequest("银行卡", "招商银行", "123"),
                new BigDecimal("100.00"));
        assertNull(shortCard.getPayCardTail());

        // null 卡号（支付宝/微信）→ 不入库尾号
        PaymentDTO noCard = runSuccess(paymentRequest("支付宝", null, null), new BigDecimal("100.00"));
        assertNull(noCard.getPayCardTail());
    }

    @Test
    void alipayWechat_withoutCard_work_tailNull() {
        PaymentDTO ali = runSuccess(paymentRequest("支付宝", null, null), new BigDecimal("100.00"));
        assertEquals("SUCCESS", ali.getStatus());
        assertNull(ali.getPayCardTail());

        PaymentDTO wx = runSuccess(paymentRequest("微信支付", null, null), new BigDecimal("100.00"));
        assertEquals("SUCCESS", wx.getStatus());
        assertNull(wx.getPayCardTail());
    }

    @Test
    void bankCard_blankBank_savedAsNull() {
        PaymentDTO result = runSuccess(
                paymentRequest("银行卡", "  ", "6225 8888 1234 5678"),
                new BigDecimal("100.00"));
        assertNull(result.getPayBank());
        assertEquals("5678", result.getPayCardTail());
    }

    // 文档性用例：服务端对支付方式不做枚举校验（无 payMethod 白名单）
    @Test
    void unknownPayMethod_isAccepted_noValidation() {
        PaymentDTO result = runSuccess(paymentRequest("比特币", null, null), new BigDecimal("100.00"));
        assertEquals("SUCCESS", result.getStatus(), "实现未校验支付方式，未知方式被接受（记录缺口）");
    }

    // ---------- P0-01：FAILED 重试复用既有行 ----------

    @Test
    void failedRetry_reusesExistingRow_withoutSecondInsert() {
        Payment failed = existingPayment(99L, PaymentStatus.FAILED);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("15998.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(failed));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.confirmPayment(anyString(), any(ConfirmPaymentRequest.class))).thenReturn(null);

        PaymentDTO result = paymentService.executePayment(
                paymentRequest("银行卡", "招商银行", "6225 8888 1234 5678"));

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(99L, result.getId(), "应复用既有 FAILED 行，不新增第二行");
        // 关键：重试路径不得再次 saveAndFlush（否则触发 order_no 唯一约束冲突）
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertEquals(99L, saved.getId());
        assertEquals(PaymentStatus.SUCCESS, saved.getStatus());
        assertNull(saved.getFailReason());
        assertEquals("5678", saved.getPayCardTail(), "重试时回填新支付信息");
        assertEquals(CREATED_AT, saved.getCreatedAt(), "复用行应保留原始 createdAt");
    }

    @Test
    void failedRetry_callbackFailsAgain_staysFailedSameRow() {
        Payment failed = existingPayment(99L, PaymentStatus.FAILED);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("15998.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(failed));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.confirmPayment(anyString(), any(ConfirmPaymentRequest.class)))
                .thenThrow(new BusinessException("订单服务不可用，请稍后重试"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("订单服务不可用，请稍后重试", ex.getMessage());

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(PaymentStatus.FAILED, captor.getValue().getStatus());
        assertEquals("订单服务不可用，请稍后重试", captor.getValue().getFailReason());
        assertEquals(99L, captor.getValue().getId());
    }

    // ---------- 已支付 / 已取消 / 重复发起 ----------

    @Test
    void orderAlreadyPaid_throwsDuplicatePayment() {
        OrderDTO order = createdOrder(ORDER_NO, new BigDecimal("100.00"));
        order.setStatus(OrderStatus.PAID);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("订单已支付，请勿重复支付", ex.getMessage());
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void orderCancelled_throwsCannotPay() {
        OrderDTO order = createdOrder(ORDER_NO, new BigDecimal("100.00"));
        order.setStatus(OrderStatus.CANCELLED);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("订单已取消，无法支付", ex.getMessage());
    }

    @Test
    void existingSuccessRow_rejected() {
        Payment success = existingPayment(5L, PaymentStatus.SUCCESS);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("100.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(success));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("该订单已发起过支付，请勿重复操作", ex.getMessage());
    }

    @Test
    void existingProcessingRow_rejected() {
        Payment processing = existingPayment(5L, PaymentStatus.PROCESSING);
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("100.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(processing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("该订单已发起过支付，请勿重复操作", ex.getMessage());
    }

    // ---------- 并发败者：唯一约束异常映射为 400 业务错误（P0-01） ----------

    @Test
    void concurrentFirstPay_loserGetsBusiness400_not500() {
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("100.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("UK_order_no"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("该订单已发起过支付，请勿重复操作", ex.getMessage());
    }

    // ---------- 回调失败：PROCESSING→FAILED 并上抛（设计 §6.2 / EX-15） ----------

    @Test
    void callbackFailure_newPayment_marksFailed_andRethrows() {
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("100.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.confirmPayment(anyString(), any(ConfirmPaymentRequest.class)))
                .thenThrow(new BusinessException("订单已取消，无法支付"));

        assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(PaymentStatus.FAILED, captor.getValue().getStatus());
        assertEquals("订单已取消，无法支付", captor.getValue().getFailReason());
    }

    // ---------- 状态流转探针：新建流水应在 saveAndFlush 时已是 PROCESSING ----------

    /**
     * P0-01 修复的关键探测：设计约定"写流水 PROCESSING"发生在入库前。
     * 生产代码在 saveAndFlush 之后才 setStatus(PROCESSING)，导致 flush 时 status 为 null，
     * 触发 NOT NULL 约束 → DataIntegrityViolation → 被误判为"该订单已发起过支付"。
     * 该用例在真实 H2（功能测试）中同样被复现。预期：PROCESSING；实际：null。
     */
    @Test
    void newPayment_isFlushedWithStatusPROCESSING() {
        when(orderClient.getOrder(ORDER_NO)).thenReturn(createdOrder(ORDER_NO, new BigDecimal("15998.00")));
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.empty());
        AtomicReference<PaymentStatus> statusAtFlush = new AtomicReference<>();
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            statusAtFlush.set(((Payment) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderClient.confirmPayment(anyString(), any(ConfirmPaymentRequest.class))).thenReturn(null);

        paymentService.executePayment(paymentRequest("支付宝", null, null));

        assertEquals(PaymentStatus.PROCESSING, statusAtFlush.get(),
                "新建流水在 saveAndFlush 时 status 应为 PROCESSING（生产代码此处为 null → 触发 NOT NULL 约束）");
    }

    // ---------- 金额边界：null / ≤0 必须抛出独立金额异常（P1 / PRD EX-6） ----------

    @Test
    void zeroAmount_isRejected_withAmountException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> runSuccess(paymentRequest("支付宝", null, null), new BigDecimal("0.00")));
        assertEquals("订单金额异常，无法支付", ex.getMessage());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void negativeAmount_isRejected_withAmountException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> runSuccess(paymentRequest("支付宝", null, null), new BigDecimal("-5.00")));
        assertEquals("订单金额异常，无法支付", ex.getMessage());
    }

    @Test
    void nullAmount_isRejected_withAmountException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> runSuccess(paymentRequest("支付宝", null, null), null));
        assertEquals("订单金额异常，无法支付", ex.getMessage());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    // ---------- 订单不存在 / 查询 ----------

    @Test
    void orderNotExist_reliesOnOrderClientBusinessException() {
        when(orderClient.getOrder(ORDER_NO))
                .thenThrow(new BusinessException("订单不存在"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)));
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    void nullOrderFromClient_throwsNpe_notBusiness() {
        when(orderClient.getOrder(ORDER_NO)).thenReturn(null);
        assertThrows(NullPointerException.class,
                () -> paymentService.executePayment(paymentRequest("支付宝", null, null)),
                "orderClient 返回 null 时无空值守卫 → NPE → 500（记录缺口）");
    }

    @Test
    void getPaymentByOrderNo_notFound_throws() {
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.getPaymentByOrderNo(ORDER_NO));
        assertEquals("支付记录不存在", ex.getMessage());
    }

    @Test
    void getPaymentByOrderNo_found_mapsDto() {
        Payment p = existingPayment(7L, PaymentStatus.SUCCESS);
        when(paymentRepository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(p));
        PaymentDTO dto = paymentService.getPaymentByOrderNo(ORDER_NO);
        assertNotNull(dto);
        assertEquals(7L, dto.getId());
        assertEquals("SUCCESS", dto.getStatus());
        assertEquals(new BigDecimal("15998.00"), dto.getAmount());
    }
}
