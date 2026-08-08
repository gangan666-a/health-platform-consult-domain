package com.example.mall.consultservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.CreateConsultSessionRequest;
import com.example.mall.consultservice.model.ConsultationSession;
import com.example.mall.consultservice.netty.WsSessionRegistry;
import com.example.mall.consultservice.repository.ChatMessageRepository;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话服务：创建/复用、医生接单、查询、主动结束。
 * <p>
 * 在 REST（Spring）线程签发 WS token 并预加载内存态 SessionState（DB 权威状态 + MAX(seq)），
 * 保证 WS join/发消息在事件循环内免查库快速裁决（ADR-IM-04 的前提）。状态流转全部走 CAS，DB 为唯一权威。
 */
@Service
public class ConsultationSessionService {

    private final ConsultationSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionStateRegistry stateRegistry;
    private final WsSessionRegistry wsRegistry;
    private final MessageService messageService;
    private final SessionLifecycle lifecycle;

    public ConsultationSessionService(ConsultationSessionRepository sessionRepository,
                                      ChatMessageRepository messageRepository,
                                      SessionStateRegistry stateRegistry,
                                      WsSessionRegistry wsRegistry,
                                      MessageService messageService,
                                      SessionLifecycle lifecycle) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.stateRegistry = stateRegistry;
        this.wsRegistry = wsRegistry;
        this.messageService = messageService;
        this.lifecycle = lifecycle;
    }

    /**
     * 创建/复用会话：同药品存在未结束会话（WAITING/ONGOING）→ 复用（演示无 userId，按药品去重）。
     * 复用也重发新用户 token（吊销旧令牌，防令牌堆积 P1-03；R-12 重启后重签）。
     */
    @Transactional
    public ConsultSessionDTO createOrReuse(CreateConsultSessionRequest req) {
        if (req.getProductId() == null
                || req.getProductName() == null || req.getProductName().isBlank()) {
            throw new BusinessException("商品信息不完整");
        }
        ConsultationSession session = sessionRepository
                .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(req.getProductId(),
                        List.of(ConsultationStatus.WAITING, ConsultationStatus.ONGOING))
                .orElseGet(() -> createNew(req));

        String oldToken = session.getToken();
        if (oldToken != null) {
            wsRegistry.revokeToken(oldToken);
        }
        String token = newToken();
        session.setToken(token);
        wsRegistry.issueToken(session.getId(), token, WsSessionRegistry.ROLE_USER);
        // 内存态预加载：DB 权威状态 + MAX(seq)，事件循环免查库
        int maxSeq = messageRepository.maxSeq(session.getId());
        stateRegistry.getOrLoad(session.getId(), session.getStatus(), maxSeq);
        return toDTO(session, token);
    }

    private ConsultationSession createNew(CreateConsultSessionRequest req) {
        ConsultationSession s = new ConsultationSession();
        s.setProductId(req.getProductId());
        s.setProductName(req.getProductName());
        s.setPatientName("演示患者");
        s.setStatus(ConsultationStatus.WAITING);
        s.setLastActiveAt(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());
        return sessionRepository.save(s);
    }

    /** 医生接单：CAS WAITING→ONGOING + 绑定医生；签发医生 token；落 SYSTEM「医生已接入」广播给用户。 */
    @Transactional
    public ConsultSessionDTO accept(Long sessionId, String doctorId, String doctorName) {
        if (doctorId == null || doctorId.isBlank()) {
            throw new BusinessException("医生身份缺失");
        }
        ConsultationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        SessionState state = stateRegistry.get(sessionId);
        if (state != null) {
            state.lock.lock();
            try {
                ConsultationStatus st = state.getStatus();
                if (st != null && st.isTerminal()) {
                    throw new BusinessException("会话已结束，无法接单");
                }
                if (st != ConsultationStatus.WAITING) {
                    throw new BusinessException("会话已被接单，无法重复接单");
                }
                doAccept(session, sessionId, doctorId, doctorName);
                state.setStatus(ConsultationStatus.ONGOING);
            } finally {
                state.lock.unlock();
            }
        } else {
            doAccept(session, sessionId, doctorId, doctorName);
        }
        // 签发医生 token + 预加载内存态（若缺失）
        SessionState loaded = stateRegistry.getOrLoad(sessionId, ConsultationStatus.ONGOING,
                messageRepository.maxSeq(sessionId));
        loaded.setStatus(ConsultationStatus.ONGOING);
        String doctorToken = newToken();
        wsRegistry.issueToken(sessionId, doctorToken, WsSessionRegistry.ROLE_DOCTOR);
        messageService.enqueueSystemMessage(sessionId, "医生 " + doctorName + " 已接入本次问诊");
        return toDTO(session, doctorToken);
    }

    private void doAccept(ConsultationSession session, Long sessionId,
                          String doctorId, String doctorName) {
        if (sessionRepository.accept(sessionId, doctorId, doctorName) == 0) {
            throw new BusinessException("接单失败，会话不存在或已被接单");
        }
        session.setStatus(ConsultationStatus.ONGOING);
        session.setDoctorId(doctorId);
        session.setDoctorName(doctorName);
    }

    /** 主动结束（用户/医生）：统一结束流程（CAS → SYSTEM → broadcast → purge），幂等。 */
    public ConsultSessionDTO end(Long sessionId, String reason) {
        ConsultationSession session = lifecycle.endSession(sessionId, reason);
        return toDTO(session, null);
    }

    /** 会话详情 */
    @Transactional(readOnly = true)
    public ConsultSessionDTO getSession(Long sessionId) {
        ConsultationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        return toDTO(session, null);
    }

    /** 重新签发用户 token（前端断线重连取 token，R-12） */
    @Transactional
    public ConsultSessionDTO refreshToken(Long sessionId) {
        ConsultationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        String oldToken = session.getToken();
        if (oldToken != null) {
            wsRegistry.revokeToken(oldToken);
        }
        String token = newToken();
        session.setToken(token);
        wsRegistry.issueToken(sessionId, token, WsSessionRegistry.ROLE_USER);
        return toDTO(session, token);
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public ConsultSessionDTO toDTO(ConsultationSession s, String token) {
        ConsultSessionDTO dto = new ConsultSessionDTO();
        dto.setSessionId(s.getId());
        dto.setToken(token);
        dto.setStatus(s.getStatus());
        dto.setStatusLabel(s.getStatus().getLabel());
        dto.setProductId(s.getProductId());
        dto.setProductName(s.getProductName());
        dto.setDoctorId(s.getDoctorId());
        dto.setDoctorName(s.getDoctorName());
        dto.setPatientName(s.getPatientName());
        dto.setOrderNo(s.getOrderNo());
        dto.setLastActiveAt(s.getLastActiveAt());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setEndedAt(s.getEndedAt());
        dto.setEndReason(s.getEndReason());
        return dto;
    }
}
