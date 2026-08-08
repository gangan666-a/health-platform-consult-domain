package com.example.mall.consultservice.service;

import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.consultservice.batch.MessageWriteQueue;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.netty.WsNotifier;
import com.example.mall.consultservice.netty.WsSessionRegistry;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * 会话生命周期：四种结束判定的统一收敛点（主动/断线/空闲/开方完成），与调度器共用同一状态机与 CAS。
 * <p>
 * 结束流程（评审 P2-12）：CAS 置终态 → enqueue SYSTEM 结束消息 → flush 写队列残留（含 SYSTEM 消息，
 * 其广播由写队列回调完成）→ 推 session_ended → purge 注册表并关闭 channel → 回收内存态。
 * 结束与发消息/开方并发由会话锁 + CAS 决定先后，结果确定、无半状态（EX-IM-14/15/27）。
 */
@Service
public class SessionLifecycle {

    private final ConsultationSessionRepository sessionRepository;
    private final SessionStateRegistry stateRegistry;
    private final WsSessionRegistry wsRegistry;
    private final WsNotifier notifier;
    private final MessageService messageService;
    private final MessageWriteQueue writeQueue;
    private final ConsultProperties props;
    private final ExecutorService taskExecutor;

    public SessionLifecycle(ConsultationSessionRepository sessionRepository,
                            SessionStateRegistry stateRegistry,
                            WsSessionRegistry wsRegistry,
                            WsNotifier notifier,
                            MessageService messageService,
                            MessageWriteQueue writeQueue,
                            ConsultProperties props,
                            ExecutorService nettyTaskExecutor) {
        this.sessionRepository = sessionRepository;
        this.stateRegistry = stateRegistry;
        this.wsRegistry = wsRegistry;
        this.notifier = notifier;
        this.messageService = messageService;
        this.writeQueue = writeQueue;
        this.props = props;
        this.taskExecutor = nettyTaskExecutor;
    }

    /**
     * 主动结束（用户/医生）或断线结束：CAS WAITING|ONGOING→ENDED。幂等：已是终态则 no-op。
     */
    public ConsultationSession endSession(Long sessionId, String reason) {
        ConsultationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SessionState state = stateRegistry.get(sessionId);
        boolean transitioned = false;
        boolean needCleanup = false;
        if (state != null) {
            state.lock.lock();
            try {
                if (state.getStatus() != null && state.getStatus().isTerminal()) {
                    return session; // 幂等：终态不再动
                }
                int affected = sessionRepository.endSession(sessionId, reason);
                if (affected == 1) {
                    session.setStatus(ConsultationStatus.ENDED);
                    session.setEndReason(reason);
                    session.setEndedAt(LocalDateTime.now());
                    state.setStatus(ConsultationStatus.ENDED);
                    transitioned = true;
                }
                needCleanup = true; // affected==0：并发已被其他路径结束，仍统一广播+清理
            } finally {
                state.lock.unlock();
            }
        } else {
            // 内存态缺失（极端兜底）：直接 CAS
            if (sessionRepository.endSession(sessionId, reason) == 1) {
                session.setStatus(ConsultationStatus.ENDED);
                session.setEndReason(reason);
                session.setEndedAt(LocalDateTime.now());
                transitioned = true;
                needCleanup = true;
            }
        }
        if (needCleanup) {
            if (transitioned) {
                messageService.enqueueSystemMessage(sessionId, "会话已结束");
            }
            finishTerminal(sessionId, state, ConsultationStatus.ENDED, reason);
        }
        return session;
    }

    /** 空闲超时：CAS ONGOING→EXPIRED（仅 ONGOING 计时；WAITING 不自动结束，AC-C4-6） */
    public void expireIdle(Long sessionId) {
        ConsultationSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        SessionState state = stateRegistry.get(sessionId);
        boolean transitioned = false;
        boolean needCleanup = false;
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(props.getSessionIdleTimeoutSeconds());
        if (state != null) {
            state.lock.lock();
            try {
                if (state.getStatus() != null && state.getStatus().isTerminal()) {
                    return;
                }
                if (sessionRepository.expireIdle(sessionId, cutoff) == 1) {
                    session.setStatus(ConsultationStatus.EXPIRED);
                    session.setEndedAt(LocalDateTime.now());
                    state.setStatus(ConsultationStatus.EXPIRED);
                    transitioned = true;
                }
                needCleanup = true;
            } finally {
                state.lock.unlock();
            }
        } else {
            if (sessionRepository.expireIdle(sessionId, cutoff) == 1) {
                transitioned = true;
                needCleanup = true;
            }
        }
        if (needCleanup) {
            if (transitioned) {
                messageService.enqueueSystemMessage(sessionId, "会话因长时间无消息已结束");
            }
            finishTerminal(sessionId, state, ConsultationStatus.EXPIRED, null);
        }
    }

    /**
     * 开方完成终态收尾：broadcast session_ended(PRESCRIPTION_ISSUED) + purge + 回收内存态。
     * 处方系统消息由开方流程自行 enqueue（含处方号，AC-C2-7），本方法不再重复落系统消息。
     */
    public void finalizePrescriptionIssued(Long sessionId) {
        finalizeTerminal(sessionId, ConsultationStatus.PRESCRIPTION_ISSUED, "PRESCRIPTION_ISSUED");
    }

    /** 通用终态收尾（处方消费 COMPLETED 等外部路径）：flush 残留 → broadcast session_ended → purge → 回收内存态 */
    public void finalizeTerminal(Long sessionId, ConsultationStatus target, String reason) {
        SessionState state = stateRegistry.get(sessionId);
        if (state != null) {
            state.lock.lock();
            try {
                finishTerminal(sessionId, state, target, reason);
            } finally {
                state.lock.unlock();
            }
        } else {
            finishTerminal(sessionId, null, target, reason);
        }
    }

    private void finishTerminal(Long sessionId, SessionState state,
                                ConsultationStatus target, String reason) {
        // ① flush 在途消息残留（含刚才 enqueue 的 SYSTEM 结束消息，其广播由写队列回调完成）
        writeQueue.flush(sessionId).join();
        // ② 广播 session_ended
        notifier.broadcastSessionEnded(sessionId, target, reason, LocalDateTime.now());
        // ③ 清理注册表并关闭本会话 channel（P1-03）+ 回收内存态
        wsRegistry.purge(sessionId);
        stateRegistry.remove(sessionId);
    }

    /** 断线：记录身份离线时刻（供调度器 60s 缓冲判定；重连接管后清除） */
    public void onDisconnect(Long sessionId, String role) {
        SessionState state = stateRegistry.get(sessionId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            // P0-01：若该角色已由新连接在会话锁内接管（join 关旧绑新），不记录离线——
            // 否则「旧 channel 关闭」会误判为新断线，使缓冲期内重连被调度器误结束
            if (wsRegistry.isRoleOnline(sessionId, role)) {
                return;
            }
            state.recordDisconnect(role);
        } finally {
            state.lock.unlock();
        }
    }

    /** 重连成功（join 接管绑定后）：清空断线缓冲标记，取消断线结束 */
    public void onReconnect(Long sessionId, String role) {
        SessionState state = stateRegistry.get(sessionId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            state.clearDisconnect(role);
        } finally {
            state.lock.unlock();
        }
    }

    /** 心跳：异步落库 heartbeatAt（不阻塞事件循环；探活本身靠 IdleStateHandler + channelInactive） */
    public void recordHeartbeat(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        taskExecutor.execute(() -> {
            try {
                sessionRepository.updateHeartbeat(sessionId);
            } catch (Exception ex) {
                // 心跳时间戳仅观测用，失败可忽略
            }
        });
    }
}
