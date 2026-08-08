package com.example.mall.consultservice.service;

import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.model.Prescription;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import com.example.mall.consultservice.repository.PrescriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底调度器（ADR-IM-06/§5.4）：空闲超时 / 断线缓冲超时 / 处方过期扫描。
 * <p>
 * 全部走 CAS，与并发操作（发消息/开方/主动结束）碰撞时输家自动跳过，不重复结束、不误判。
 * 断线缓冲用内存态 SessionState 的离线时刻判定（事件循环 channelInactive 记录），空闲超时以 DB lastActiveAt 为准。
 */
@Component
public class SessionEndScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionEndScheduler.class);

    private final ConsultationSessionRepository sessionRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final SessionLifecycle lifecycle;
    private final SessionStateRegistry stateRegistry;
    private final ConsultProperties props;

    public SessionEndScheduler(ConsultationSessionRepository sessionRepository,
                               PrescriptionRepository prescriptionRepository,
                               SessionLifecycle lifecycle,
                               SessionStateRegistry stateRegistry,
                               ConsultProperties props) {
        this.sessionRepository = sessionRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.lifecycle = lifecycle;
        this.stateRegistry = stateRegistry;
        this.props = props;
    }

    /** 任务1 空闲超时：ONGOING 且 lastActiveAt 早于 cutoff → EXPIRED（仅 ONGOING 计时，WAITING 不自动结束）。 */
    @Scheduled(fixedDelayString = "${consult.session-sweep-interval-seconds:60}000")
    public void sweepIdle() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(props.getSessionIdleTimeoutSeconds());
        for (ConsultationSession s : sessionRepository.findByStatusAndLastActiveAtBefore(
                ConsultationStatus.ONGOING, cutoff)) {
            try {
                lifecycle.expireIdle(s.getId());
            } catch (Exception ex) {
                log.warn("空闲超时结束失败, sessionId={}", s.getId(), ex);
            }
        }
    }

    /** 任务2 断线缓冲兜底：任一角色离线超过 grace 秒且会话非终态 → ENDED(DISCONNECT_TIMEOUT)。 */
    @Scheduled(fixedDelayString = "${consult.session-sweep-interval-seconds:60}000")
    public void sweepDisconnect() {
        long graceMs = props.getSessionDisconnectGraceSeconds() * 1000L;
        for (SessionState st : stateRegistry.all()) {
            Long sessionId = st.getSessionId();
            boolean overGrace;
            st.lock.lock();
            try {
                ConsultationStatus status = st.getStatus();
                if (status != null && status.isTerminal()) {
                    continue;
                }
                overGrace = st.hasDisconnected() && st.anyDisconnectOver(graceMs);
            } finally {
                st.lock.unlock();
            }
            if (overGrace) {
                try {
                    lifecycle.endSession(sessionId, "DISCONNECT_TIMEOUT");
                } catch (Exception ex) {
                    log.warn("断线超时结束失败, sessionId={}", sessionId, ex);
                }
            }
        }
    }

    /** 任务3 处方过期扫描（与懒判定双保险）：ACTIVE 且 validUntil < now → EXPIRED。 */
    @Scheduled(fixedDelayString = "${consult.session-sweep-interval-seconds:60}000")
    public void sweepPrescriptionExpiry() {
        List<Prescription> expired = prescriptionRepository.findByStatusAndValidUntilBefore(
                PrescriptionStatus.ACTIVE, LocalDateTime.now());
        if (!expired.isEmpty()) {
            List<Long> ids = expired.stream().map(Prescription::getId).toList();
            prescriptionRepository.expireByIds(ids);
        }
    }
}
