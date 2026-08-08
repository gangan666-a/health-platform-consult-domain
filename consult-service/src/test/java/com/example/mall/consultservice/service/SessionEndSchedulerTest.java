package com.example.mall.consultservice.service;

import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.model.Prescription;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import com.example.mall.consultservice.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionEndScheduler（兜底调度器）单元测试（Mockito）。
 * - 空闲超时：仅扫描 ONGOING 且 lastActiveAt 早于 cutoff；WAITING 不会被选中自动结束（AC-C4-6）；
 * - 断线缓冲：仅非终态且有离线标记且超 grace 才会 endSession(DISCONNECT_TIMEOUT)；终态/未离线/未超时跳过；
 * - 处方过期扫描：ACTIVE 且 validUntil 早于 now → expireByIds。
 * <p>
 * 断线判定的时间用 props 的 grace（本测试置 0/大值控制确定性，避免真实 sleep）。
 */
class SessionEndSchedulerTest {

    private ConsultationSessionRepository sessionRepository;
    private PrescriptionRepository prescriptionRepository;
    private SessionLifecycle lifecycle;
    private SessionStateRegistry stateRegistry;
    private ConsultProperties props;
    private SessionEndScheduler scheduler;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ConsultationSessionRepository.class);
        prescriptionRepository = mock(PrescriptionRepository.class);
        lifecycle = mock(SessionLifecycle.class);
        stateRegistry = new SessionStateRegistry();
        props = new ConsultProperties();
        scheduler = new SessionEndScheduler(sessionRepository, prescriptionRepository,
                lifecycle, stateRegistry, props);
    }

    private ConsultationSession session(Long id, ConsultationStatus status) {
        ConsultationSession s = new ConsultationSession();
        s.setId(id);
        s.setStatus(status);
        s.setLastActiveAt(LocalDateTime.now().minusMinutes(10));
        return s;
    }

    private Prescription prescription(Long id) {
        Prescription p = new Prescription();
        p.setId(id);
        p.setStatus(PrescriptionStatus.ACTIVE);
        return p;
    }

    // ==================== 空闲超时 ====================

    @Test
    @DisplayName("空闲超时：ONGOING 超时会话 → 逐个 expireIdle")
    void sweepIdle_ongoingExpired() {
        when(sessionRepository.findByStatusAndLastActiveAtBefore(any(), any()))
                .thenReturn(List.of(session(1L, ConsultationStatus.ONGOING),
                        session(2L, ConsultationStatus.ONGOING)));
        scheduler.sweepIdle();
        verify(lifecycle).expireIdle(1L);
        verify(lifecycle).expireIdle(2L);
    }

    @Test
    @DisplayName("空闲超时：无超时会话 → 不调 expireIdle")
    void sweepIdle_none() {
        when(sessionRepository.findByStatusAndLastActiveAtBefore(any(), any())).thenReturn(List.of());
        scheduler.sweepIdle();
        verify(lifecycle, never()).expireIdle(any());
    }

    // ==================== 断线缓冲 ====================

    @Test
    @DisplayName("断线缓冲：非终态 + 有离线标记 + 超 grace → endSession(DISCONNECT_TIMEOUT)")
    void sweepDisconnect_overGrace() {
        props.setSessionDisconnectGraceSeconds(0); // 刚记录即超时（确定性）
        SessionState st = stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        st.recordDisconnect("USER");
        scheduler.sweepDisconnect();
        verify(lifecycle).endSession(1L, "DISCONNECT_TIMEOUT");
    }

    @Test
    @DisplayName("断线缓冲：无离线标记 → 不结束")
    void sweepDisconnect_noDisconnect() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        scheduler.sweepDisconnect();
        verify(lifecycle, never()).endSession(any(), any());
    }

    @Test
    @DisplayName("断线缓冲：离线未超 grace（缓冲期内）→ 不结束")
    void sweepDisconnect_withinGrace() {
        props.setSessionDisconnectGraceSeconds(10_000);
        SessionState st = stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        st.recordDisconnect("USER");
        scheduler.sweepDisconnect();
        verify(lifecycle, never()).endSession(any(), any());
    }

    @Test
    @DisplayName("断线缓冲：终态会话（即使有离线标记）→ 跳过不结束")
    void sweepDisconnect_terminalSkipped() {
        props.setSessionDisconnectGraceSeconds(0);
        SessionState st = stateRegistry.getOrLoad(1L, ConsultationStatus.ENDED, 0);
        st.recordDisconnect("USER");
        scheduler.sweepDisconnect();
        verify(lifecycle, never()).endSession(any(), any());
    }

    @Test
    @DisplayName("断线缓冲：重连清标记后（clearDisconnect）→ 不结束")
    void sweepDisconnect_reconnected() {
        props.setSessionDisconnectGraceSeconds(0);
        SessionState st = stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        st.recordDisconnect("USER");
        st.clearDisconnect("USER"); // 缓冲期内重连接管成功，清标记
        scheduler.sweepDisconnect();
        verify(lifecycle, never()).endSession(any(), any());
    }

    // ==================== 处方过期扫描 ====================

    @Test
    @DisplayName("处方过期扫描：有过期处方 → expireByIds 批量置 EXPIRED")
    void sweepPrescriptionExpiry_expired() {
        when(prescriptionRepository.findByStatusAndValidUntilBefore(any(), any()))
                .thenReturn(List.of(prescription(1L), prescription(2L)));
        scheduler.sweepPrescriptionExpiry();
        verify(prescriptionRepository).expireByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("处方过期扫描：无过期 → 不调 expireByIds")
    void sweepPrescriptionExpiry_none() {
        when(prescriptionRepository.findByStatusAndValidUntilBefore(any(), any())).thenReturn(List.of());
        scheduler.sweepPrescriptionExpiry();
        verify(prescriptionRepository, never()).expireByIds(anyList());
    }
}
