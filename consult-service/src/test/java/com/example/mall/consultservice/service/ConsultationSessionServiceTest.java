package com.example.mall.consultservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.CreateConsultSessionRequest;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.netty.WsSessionRegistry;
import com.example.mall.consultservice.repository.ChatMessageRepository;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConsultationSessionService 单元测试（Mockito）。
 * - createOrReuse：商品信息不完整拒绝；新会话 WAITING 签发用户 token；同药品未结束会话复用并吊销旧令牌；
 * - accept：医生身份缺失/会话不存在/终态拒绝/重复接单拒绝；WAITING→ONGOING 走 CAS，CAS=0 →「接单失败」；
 * - end / getSession / refreshToken。
 */
class ConsultationSessionServiceTest {

    private ConsultationSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private SessionStateRegistry stateRegistry;
    private WsSessionRegistry wsRegistry;
    private MessageService messageService;
    private SessionLifecycle lifecycle;
    private ConsultationSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ConsultationSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        stateRegistry = new SessionStateRegistry();
        wsRegistry = mock(WsSessionRegistry.class);
        messageService = mock(MessageService.class);
        lifecycle = mock(SessionLifecycle.class);
        service = new ConsultationSessionService(sessionRepository, messageRepository,
                stateRegistry, wsRegistry, messageService, lifecycle);
    }

    private CreateConsultSessionRequest createReq(Long productId, String productName) {
        CreateConsultSessionRequest req = new CreateConsultSessionRequest();
        req.setProductId(productId);
        req.setProductName(productName);
        return req;
    }

    private ConsultationSession session(Long id, ConsultationStatus status, Long productId) {
        ConsultationSession s = new ConsultationSession();
        s.setId(id);
        s.setProductId(productId);
        s.setProductName("阿莫西林胶囊");
        s.setPatientName("演示患者");
        s.setStatus(status);
        return s;
    }

    // ==================== createOrReuse ====================

    @Test
    @DisplayName("创建：productId 为空 → 商品信息不完整")
    void create_productIdNull() {
        CreateConsultSessionRequest req = createReq(null, "阿莫西林胶囊");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrReuse(req));
        assertEquals("商品信息不完整", ex.getMessage());
    }

    @Test
    @DisplayName("创建：productName 为空 → 商品信息不完整")
    void create_productNameBlank() {
        CreateConsultSessionRequest req = createReq(1L, "  ");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrReuse(req));
        assertEquals("商品信息不完整", ex.getMessage());
    }

    @Test
    @DisplayName("创建：无未结束会话 → 新建 WAITING 会话，签发用户 token，预加载内存态")
    void create_newSession() {
        when(sessionRepository.findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
                eq(1L), anyList())).thenReturn(Optional.empty());
        when(messageRepository.maxSeq(anyLong())).thenReturn(0);
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            ConsultationSession s = inv.getArgument(0);
            s.setId(7L);
            return s;
        });

        ConsultSessionDTO dto = service.createOrReuse(createReq(1L, "阿莫西林胶囊"));

        assertEquals(7L, dto.getSessionId());
        assertEquals(ConsultationStatus.WAITING, dto.getStatus());
        assertNotNull(dto.getToken());
        verify(wsRegistry).issueToken(eq(7L), eq(dto.getToken()), eq(WsSessionRegistry.ROLE_USER));
        assertNotNull(stateRegistry.get(7L));
        assertEquals(ConsultationStatus.WAITING, stateRegistry.get(7L).getStatus());
    }

    @Test
    @DisplayName("创建：存在同药品未结束会话 → 复用并吊销旧令牌、重发新令牌")
    void create_reuseSession() {
        ConsultationSession existing = session(3L, ConsultationStatus.WAITING, 1L);
        existing.setToken("OLD-TOKEN");
        when(sessionRepository.findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
                eq(1L), anyList())).thenReturn(Optional.of(existing));
        when(messageRepository.maxSeq(3L)).thenReturn(5);
        wsRegistry.issueToken(3L, "OLD-TOKEN", WsSessionRegistry.ROLE_USER);

        ConsultSessionDTO dto = service.createOrReuse(createReq(1L, "阿莫西林胶囊"));

        assertEquals(3L, dto.getSessionId());
        assertNotNull(dto.getToken());
        assertTrue(!dto.getToken().equals("OLD-TOKEN"));
        verify(wsRegistry).revokeToken("OLD-TOKEN");
        verify(wsRegistry).issueToken(eq(3L), eq(dto.getToken()), eq(WsSessionRegistry.ROLE_USER));
        // 内存态预加载 seq 对齐 DB MAX(seq)
        assertEquals(5, stateRegistry.get(3L).currentSeq());
    }

    // ==================== accept ====================

    @Test
    @DisplayName("接单：医生身份缺失 → 医生身份缺失")
    void accept_doctorMissing() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.accept(1L, "  ", "医生A"));
        assertEquals("医生身份缺失", ex.getMessage());
    }

    @Test
    @DisplayName("接单：会话不存在 → 会话不存在")
    void accept_sessionNotFound() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.accept(1L, "d1", "医生A"));
        assertEquals("会话不存在", ex.getMessage());
    }

    @Test
    @DisplayName("接单：终态会话 → 会话已结束，无法接单")
    void accept_terminalState() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, ConsultationStatus.WAITING, 1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ENDED, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.accept(1L, "d1", "医生A"));
        assertEquals("会话已结束，无法接单", ex.getMessage());
    }

    @Test
    @DisplayName("接单：已 ONGOING → 会话已被接单，无法重复接单")
    void accept_alreadyOngoing() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, ConsultationStatus.WAITING, 1L)));
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.accept(1L, "d1", "医生A"));
        assertEquals("会话已被接单，无法重复接单", ex.getMessage());
    }

    @Test
    @DisplayName("接单成功：WAITING→ONGOING 走 CAS=1，签发医生 token，落医生接入系统消息")
    void accept_success() {
        ConsultationSession s = session(1L, ConsultationStatus.WAITING, 1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(s));
        when(sessionRepository.accept(1L, "d1", "医生A")).thenReturn(1);
        when(messageRepository.maxSeq(1L)).thenReturn(3);
        SessionState state = stateRegistry.getOrLoad(1L, ConsultationStatus.WAITING, 0);

        ConsultSessionDTO dto = service.accept(1L, "d1", "医生A");

        assertEquals(ConsultationStatus.ONGOING, dto.getStatus());
        assertNotNull(dto.getToken());
        assertEquals("d1", dto.getDoctorId());
        assertEquals("医生A", dto.getDoctorName());
        assertEquals(ConsultationStatus.ONGOING, state.getStatus());
        verify(wsRegistry).issueToken(eq(1L), eq(dto.getToken()), eq(WsSessionRegistry.ROLE_DOCTOR));
        verify(messageService).enqueueSystemMessage(eq(1L), eq("医生 医生A 已接入本次问诊"));
    }

    @Test
    @DisplayName("接单：内存态缺失 + CAS=0（并发已被接单）→ 接单失败，会话不存在或已被接单")
    void accept_casConflict() {
        ConsultationSession s = session(1L, ConsultationStatus.WAITING, 1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(s));
        when(sessionRepository.accept(1L, "d1", "医生A")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.accept(1L, "d1", "医生A"));
        assertEquals("接单失败，会话不存在或已被接单", ex.getMessage());
    }

    // ==================== end / getSession / refreshToken ====================

    @Test
    @DisplayName("结束：委托 lifecycle.endSession，返回 ENDED DTO")
    void end_delegates() {
        ConsultationSession s = session(1L, ConsultationStatus.ENDED, 1L);
        s.setEndReason("USER_ENDED");
        when(lifecycle.endSession(1L, "USER_ENDED")).thenReturn(s);
        ConsultSessionDTO dto = service.end(1L, "USER_ENDED");
        assertEquals(ConsultationStatus.ENDED, dto.getStatus());
        assertEquals("USER_ENDED", dto.getEndReason());
    }

    @Test
    @DisplayName("详情：会话不存在 → 会话不存在")
    void getSession_notFound() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getSession(1L));
        assertEquals("会话不存在", ex.getMessage());
    }

    @Test
    @DisplayName("详情：正常返回")
    void getSession_ok() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, ConsultationStatus.ONGOING, 1L)));
        ConsultSessionDTO dto = service.getSession(1L);
        assertEquals(1L, dto.getSessionId());
        assertEquals(ConsultationStatus.ONGOING, dto.getStatus());
    }

    @Test
    @DisplayName("重签 token：吊销旧令牌、签发新用户令牌")
    void refreshToken() {
        ConsultationSession s = session(1L, ConsultationStatus.WAITING, 1L);
        s.setToken("OLD");
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(s));
        wsRegistry.issueToken(1L, "OLD", WsSessionRegistry.ROLE_USER);

        ConsultSessionDTO dto = service.refreshToken(1L);

        assertNotNull(dto.getToken());
        verify(wsRegistry).revokeToken("OLD");
        verify(wsRegistry).issueToken(eq(1L), eq(dto.getToken()), eq(WsSessionRegistry.ROLE_USER));
    }
}
