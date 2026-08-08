package com.example.mall.consultservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.ConsumePrescriptionItem;
import com.example.mall.common.model.ConsumePrescriptionRequest;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.PrescriptionItem;
import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.common.model.WritePrescriptionRequest;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.model.Prescription;
import com.example.mall.consultservice.netty.WsNotifier;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import com.example.mall.consultservice.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PrescriptionService 单元测试（Mockito + 假事务管理器，不连库）。
 * - 校验链逐条（sessionId 缺失/会话不存在/终态/非 ONGOING/明细空/明细不完整/药品不一致）；
 * - 开方成功：处方 INSERT + 会话 PRESCRIPTION_ISSUED 同事务，CAS=0 抛「开方冲突」；
 * - consume 一次性消费：P1-02 必须带 orderNo、过期/已用/撤销拒绝、数量匹配、CAS 裁决、消费成功自动结束会话；
 * - restore 补偿恢复（P1-01）：仅「被该订单消费」的 USED 处方可恢复，幂等。
 * <p>
 * 事务用假 PlatformTransactionManager（getTransaction 返回 SimpleTransactionStatus，commit/rollback 空操作），
 * 使 TransactionTemplate 同步执行回调，断言「同事务」路径与 CAS 失败时的回滚路径。
 */
class PrescriptionServiceTest {

    private PrescriptionRepository prescriptionRepository;
    private ConsultationSessionRepository sessionRepository;
    private SessionStateRegistry stateRegistry;
    private SessionLifecycle lifecycle;
    private MessageService messageService;
    private WsNotifier notifier;
    private ConsultProperties props;
    private PrescriptionService service;

    @BeforeEach
    void setUp() {
        prescriptionRepository = mock(PrescriptionRepository.class);
        sessionRepository = mock(ConsultationSessionRepository.class);
        stateRegistry = new SessionStateRegistry();
        lifecycle = mock(SessionLifecycle.class);
        messageService = mock(MessageService.class);
        notifier = mock(WsNotifier.class);
        props = new ConsultProperties();
        props.setPrescriptionValidDays(7);
        service = new PrescriptionService(prescriptionRepository, sessionRepository,
                stateRegistry, lifecycle, messageService, notifier, props, new FakeTxManager());
    }

    /** 假事务管理器：getTransaction 返回一个激活的事务，commit/rollback 空操作，回调同步执行 */
    static final class FakeTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private ConsultationSession ongoingSession(Long id) {
        ConsultationSession s = new ConsultationSession();
        s.setId(id);
        s.setProductId(1L);
        s.setProductName("阿莫西林胶囊");
        s.setStatus(ConsultationStatus.ONGOING);
        s.setDoctorId("d1");
        s.setDoctorName("医生A");
        s.setPatientName("演示患者");
        return s;
    }

    private PrescriptionItem item(Long productId, int qty) {
        PrescriptionItem it = new PrescriptionItem();
        it.setProductId(productId);
        it.setProductName("阿莫西林胶囊");
        it.setDosage("0.5g");
        it.setFrequency("tid");
        it.setQuantity(qty);
        return it;
    }

    private WritePrescriptionRequest prescribeReq(Long sessionId, PrescriptionItem... items) {
        WritePrescriptionRequest req = new WritePrescriptionRequest();
        req.setSessionId(sessionId);
        req.setItems(List.of(items));
        req.setNote("测试备注");
        return req;
    }

    private Prescription activePrescription(String no, Long sessionId) {
        Prescription p = new Prescription();
        p.setNo(no);
        p.setSessionId(sessionId);
        p.setStatus(PrescriptionStatus.ACTIVE);
        p.setValidUntil(LocalDateTime.now().plusDays(7));
        p.setDoctorId("d1");
        p.setDoctorName("医生A");
        p.setPatientName("演示患者");
        p.setItems(List.of(item(1L, 2)));
        return p;
    }

    private ConsumePrescriptionRequest consumeReq(String no, String orderNo, Long productId, int qty) {
        ConsumePrescriptionRequest req = new ConsumePrescriptionRequest();
        req.setPrescriptionNo(no);
        req.setOrderNo(orderNo);
        ConsumePrescriptionItem ci = new ConsumePrescriptionItem();
        ci.setProductId(productId);
        ci.setQuantity(qty);
        req.setItems(List.of(ci));
        return req;
    }

    // ==================== prescribe 校验链 ====================

    @Test
    @DisplayName("开方：sessionId 缺失 → 会话标识缺失")
    void prescribe_sessionIdNull() {
        WritePrescriptionRequest req = new WritePrescriptionRequest();
        req.setItems(List.of(item(1L, 2)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.prescribe(req));
        assertEquals("会话标识缺失", ex.getMessage());
    }

    @Test
    @DisplayName("开方：会话不存在 → 会话不存在")
    void prescribe_sessionNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(99L, item(1L, 2))));
        assertEquals("会话不存在", ex.getMessage());
    }

    @Test
    @DisplayName("开方：终态会话拒绝 → 会话已结束，无法开方")
    void prescribe_terminalState() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(ongoingSession(1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.PRESCRIPTION_ISSUED, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, item(1L, 2))));
        assertEquals("会话已结束，无法开方", ex.getMessage());
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("开方：非 ONGOING（WAITING）→ 医生尚未接单，无法开方")
    void prescribe_notOngoing() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(ongoingSession(1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.WAITING, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, item(1L, 2))));
        assertEquals("医生尚未接单，无法开方", ex.getMessage());
    }

    @Test
    @DisplayName("开方：内存态缺失 + 会话非 ONGOING → 医生尚未接单或会话已结束，无法开方")
    void prescribe_noStateAndNotOngoing() {
        ConsultationSession s = ongoingSession(1L);
        s.setStatus(ConsultationStatus.WAITING);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(s));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, item(1L, 2))));
        assertEquals("医生尚未接单或会话已结束，无法开方", ex.getMessage());
    }

    @Test
    @DisplayName("开方：明细为空 → 处方明细不能为空")
    void prescribe_itemsEmpty() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(ongoingSession(1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        WritePrescriptionRequest req = prescribeReq(1L);
        req.setItems(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.prescribe(req));
        assertEquals("处方明细不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("开方：明细不完整（缺用法/用量/数量）→ 处方明细不完整")
    void prescribe_itemIncomplete() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(ongoingSession(1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        PrescriptionItem bad = item(1L, 2);
        bad.setQuantity(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, bad)));
        assertEquals("处方明细不完整：需药品、用法、用量与数量", ex.getMessage());
    }

    @Test
    @DisplayName("开方：药品与会话目标药品不一致 → 处方药品必须与会话目标药品一致")
    void prescribe_productMismatch() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(ongoingSession(1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, item(999L, 2))));
        assertEquals("处方药品必须与会话目标药品一致", ex.getMessage());
    }

    // ==================== prescribe 成功 / CAS ====================

    @Test
    @DisplayName("开方成功：同事务完成处方 INSERT + 会话 PRESCRIPTION_ISSUED，内存态同步，广播+终态收尾")
    void prescribe_success() {
        ConsultationSession session = ongoingSession(1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        SessionState state = stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        when(prescriptionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.prescribeComplete(1L)).thenReturn(1);

        PrescriptionDTO dto = service.prescribe(prescribeReq(1L, item(1L, 2)));

        assertNotNull(dto.getNo());
        assertTrue(dto.getNo().startsWith("RX"));
        assertEquals(PrescriptionStatus.ACTIVE, dto.getStatus());
        assertEquals("有效", dto.getStatusLabel());
        assertEquals(1L, dto.getSessionId());
        assertEquals(2, dto.getItems().get(0).getQuantity());
        // 有效期 = 开方 + 7 天（props 注入）
        assertTrue(dto.getValidUntil().isAfter(LocalDateTime.now().plusDays(6)));
        assertTrue(dto.getValidUntil().isBefore(LocalDateTime.now().plusDays(8)));
        // 内存态同步为 PRESCRIPTION_ISSUED
        assertEquals(ConsultationStatus.PRESCRIPTION_ISSUED, state.getStatus());
        // 提交后：落系统消息（含处方号）+ 广播 prescription_issued + 终态收尾
        ArgumentCaptor<String> contentCap = ArgumentCaptor.forClass(String.class);
        verify(messageService).enqueueSystemMessage(eq(1L), contentCap.capture());
        assertTrue(contentCap.getValue().contains(dto.getNo()));
        verify(notifier).broadcastPrescriptionIssued(eq(1L), eq(dto.getNo()), any());
        verify(lifecycle).finalizeTerminal(eq(1L), eq(ConsultationStatus.PRESCRIPTION_ISSUED), eq("PRESCRIPTION_ISSUED"));
    }

    @Test
    @DisplayName("开方：处方 INSERT 后会话 CAS=0 → 开方冲突，会话状态已变化（事务回滚不落库不广播）")
    void prescribe_casConflict() {
        ConsultationSession session = ongoingSession(1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        when(prescriptionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.prescribeComplete(1L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prescribe(prescribeReq(1L, item(1L, 2))));
        assertEquals("开方冲突，会话状态已变化", ex.getMessage());
        verify(messageService, never()).enqueueSystemMessage(any(), anyString());
        verify(notifier, never()).broadcastPrescriptionIssued(any(), anyString(), any());
        verify(lifecycle, never()).finalizeTerminal(any(), any(), any());
    }

    @Test
    @DisplayName("开方：内存态缺失 + 会话 ONGOING → 仍可成功开方（DB 兜底）")
    void prescribe_successWithoutState() {
        ConsultationSession session = ongoingSession(1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(prescriptionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.prescribeComplete(1L)).thenReturn(1);

        PrescriptionDTO dto = service.prescribe(prescribeReq(1L, item(1L, 2)));
        assertNotNull(dto.getNo());
        assertEquals(PrescriptionStatus.ACTIVE, dto.getStatus());
    }

    // ==================== consume 校验链 ====================

    @Test
    @DisplayName("消费：处方号为空 → 处方号不能为空")
    void consume_noBlank() {
        ConsumePrescriptionRequest req = consumeReq("", "ORD1", 1L, 2);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(req));
        assertEquals("处方号不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("消费：orderNo 为空（P1-02）→ 订单号不能为空")
    void consume_orderNoBlank() {
        ConsumePrescriptionRequest req = consumeReq("RX1", "", 1L, 2);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(req));
        assertEquals("订单号不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("消费：处方不存在 → 处方不存在或无效")
    void consume_notFound() {
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 2)));
        assertEquals("处方不存在或无效", ex.getMessage());
    }

    @Test
    @DisplayName("消费：已过期（ACTIVE 且 validUntil < now）→ 懒过期落库 + 处方已过期")
    void consume_expiredLazy() {
        Prescription p = activePrescription("RX1", 1L);
        p.setValidUntil(LocalDateTime.now().minusDays(1));
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 2)));
        assertEquals("处方已过期", ex.getMessage());
        verify(prescriptionRepository).expireLazy("RX1");
    }

    @Test
    @DisplayName("消费：已使用（USED）→ 处方已使用，请重新问诊开具处方")
    void consume_alreadyUsed() {
        Prescription p = activePrescription("RX1", 1L);
        p.setStatus(PrescriptionStatus.USED);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 2)));
        assertEquals("处方已使用，请重新问诊开具处方", ex.getMessage());
    }

    @Test
    @DisplayName("消费：已撤销（REVOKED）→ 处方已撤销，无法使用")
    void consume_revoked() {
        Prescription p = activePrescription("RX1", 1L);
        p.setStatus(PrescriptionStatus.REVOKED);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 2)));
        assertEquals("处方已撤销，无法使用", ex.getMessage());
    }

    @Test
    @DisplayName("消费：购买明细为空 → 购买明细不能为空")
    void consume_itemsEmpty() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        ConsumePrescriptionRequest req = consumeReq("RX1", "ORD1", 1L, 2);
        req.setItems(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(req));
        assertEquals("购买明细不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("消费：药品不符 → 处方药品与购买药品不符")
    void consume_productMismatch() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 999L, 2)));
        assertEquals("处方药品与购买药品不符", ex.getMessage());
    }

    @Test
    @DisplayName("消费：数量不一致 → 下单数量必须与处方开具数量一致")
    void consume_quantityMismatch() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 3)));
        assertEquals("下单数量必须与处方开具数量一致", ex.getMessage());
    }

    // ==================== consume 成功 / CAS / 会话自动结束 ====================

    @Test
    @DisplayName("消费成功：CAS USED + 回写 orderNo，会话 PRESCRIPTION_ISSUED→ENDED(COMPLETED)，终态收尾")
    void consume_success_completesSession() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        when(prescriptionRepository.consume("RX1", "ORD1")).thenReturn(1);
        SessionState state = stateRegistry.getOrLoad(1L, ConsultationStatus.PRESCRIPTION_ISSUED, 0);
        when(sessionRepository.markCompleted(1L, "ORD1")).thenReturn(1);

        PrescriptionDTO dto = service.consume(consumeReq("RX1", "ORD1", 1L, 2));

        assertEquals(PrescriptionStatus.USED, dto.getStatus());
        assertEquals("ORD1", dto.getConsumedOrderNo());
        assertNotNull(dto.getConsumedAt());
        assertEquals(ConsultationStatus.ENDED, state.getStatus());
        verify(lifecycle).finalizeTerminal(1L, ConsultationStatus.ENDED, "COMPLETED");
    }

    @Test
    @DisplayName("消费成功：会话已终态（markCompleted=0）→ 处方消费仍成功，不重复终态收尾")
    void consume_success_sessionAlreadyTerminal() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        when(prescriptionRepository.consume("RX1", "ORD1")).thenReturn(1);
        when(sessionRepository.markCompleted(1L, "ORD1")).thenReturn(0);

        PrescriptionDTO dto = service.consume(consumeReq("RX1", "ORD1", 1L, 2));

        assertEquals(PrescriptionStatus.USED, dto.getStatus());
        verify(lifecycle, never()).finalizeTerminal(1L, ConsultationStatus.ENDED, "COMPLETED");
    }

    @Test
    @DisplayName("消费：CAS=0（并发已被消费）→ 处方消费失败，请重试")
    void consume_casConflict() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        when(prescriptionRepository.consume("RX1", "ORD1")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(consumeReq("RX1", "ORD1", 1L, 2)));
        assertEquals("处方消费失败，请重试", ex.getMessage());
    }

    // ==================== restore 补偿恢复（P1-01） ====================

    @Test
    @DisplayName("恢复：处方号为空 → 处方号不能为空")
    void restore_noBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("", "ORD1"));
        assertEquals("处方号不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("恢复：订单号为空 → 订单号不能为空")
    void restore_orderNoBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("RX1", ""));
        assertEquals("订单号不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("恢复：处方不存在 → 处方不存在")
    void restore_notFound() {
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("RX1", "ORD1"));
        assertEquals("处方不存在", ex.getMessage());
    }

    @Test
    @DisplayName("恢复：非 USED 状态 → 处方非已使用状态，无需恢复")
    void restore_notUsed() {
        Prescription p = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("RX1", "ORD1"));
        assertEquals("处方非已使用状态，无需恢复", ex.getMessage());
    }

    @Test
    @DisplayName("恢复：订单归属不符（P1-01 缩小爆炸半径）→ 处方未被该订单消费，无法恢复")
    void restore_wrongOrder() {
        Prescription p = activePrescription("RX1", 1L);
        p.setStatus(PrescriptionStatus.USED);
        p.setConsumedOrderNo("ORD-A");
        p.setConsumedAt(LocalDateTime.now());
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("RX1", "ORD-B"));
        assertEquals("处方未被该订单消费，无法恢复", ex.getMessage());
    }

    @Test
    @DisplayName("恢复成功：USED→ACTIVE，清空 consumedOrderNo/consumedAt")
    void restore_success() {
        Prescription p = activePrescription("RX1", 1L);
        p.setStatus(PrescriptionStatus.USED);
        p.setConsumedOrderNo("ORD1");
        p.setConsumedAt(LocalDateTime.now());
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        when(prescriptionRepository.restore("RX1", "ORD1")).thenReturn(1);

        PrescriptionDTO dto = service.restore("RX1", "ORD1");

        assertEquals(PrescriptionStatus.ACTIVE, dto.getStatus());
        assertNull(dto.getConsumedOrderNo());
        assertNull(dto.getConsumedAt());
    }

    @Test
    @DisplayName("恢复：CAS=0（并发）→ 处方恢复失败，请重试")
    void restore_casConflict() {
        Prescription p = activePrescription("RX1", 1L);
        p.setStatus(PrescriptionStatus.USED);
        p.setConsumedOrderNo("ORD1");
        p.setConsumedAt(LocalDateTime.now());
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(p));
        when(prescriptionRepository.restore("RX1", "ORD1")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore("RX1", "ORD1"));
        assertEquals("处方恢复失败，请重试", ex.getMessage());
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询：处方不存在 → 处方不存在")
    void getByNo_notFound() {
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getByNo("RX1"));
        assertEquals("处方不存在", ex.getMessage());
    }

    @Test
    @DisplayName("查询：有效处方正常返回，过期懒判定实时置 EXPIRED")
    void getByNo_activeAndExpired() {
        Prescription active = activePrescription("RX1", 1L);
        when(prescriptionRepository.findByNo("RX1")).thenReturn(Optional.of(active));
        assertEquals(PrescriptionStatus.ACTIVE, service.getByNo("RX1").getStatus());
        verify(prescriptionRepository, never()).expireLazy("RX1");

        Prescription expired = activePrescription("RX2", 1L);
        expired.setValidUntil(LocalDateTime.now().minusMinutes(1));
        when(prescriptionRepository.findByNo("RX2")).thenReturn(Optional.of(expired));
        PrescriptionDTO dto = service.getByNo("RX2");
        assertEquals(PrescriptionStatus.EXPIRED, dto.getStatus());
        verify(prescriptionRepository).expireLazy("RX2");
    }
}
