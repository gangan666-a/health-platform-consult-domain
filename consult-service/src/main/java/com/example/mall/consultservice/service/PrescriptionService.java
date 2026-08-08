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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 处方服务：开具（医生本人接管的 ONGOING 会话）、一次性消费、补偿恢复、查询。
 * <p>
 * <b>消费/恢复/过期</b>：全部单条 CAS UPDATE 为唯一并发边界（ADR-IM-07/§6.4），第 0 步校验给出精确
 * 拒绝文案，第 1 步 CAS 裁决并发；防复用靠行锁串行化（EX-IM-26）。
 * <b>P1-02</b>：消费请求必须携带 orderNo，CAS 回写 consumed_order_no 并与 restore 归属校验对齐。
 * <b>P1-01</b>：restore 仅「被该订单消费」的处方可恢复（USED→ACTIVE），order-service 落单失败时补偿。
 * <p>
 * DB 写入与广播分界：事务内完成「处方 INSERT + 会话状态 CAS」，提交后再 broadcast/enqueue，避免
 * 「广播了未提交的处方」。终态收尾（flush/purge）委托 {@link SessionLifecycle#finalizeTerminal}。
 */
@Service
public class PrescriptionService {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionService.class);
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PrescriptionRepository prescriptionRepository;
    private final ConsultationSessionRepository sessionRepository;
    private final SessionStateRegistry stateRegistry;
    private final SessionLifecycle lifecycle;
    private final MessageService messageService;
    private final WsNotifier notifier;
    private final ConsultProperties props;
    private final TransactionTemplate tx;

    public PrescriptionService(PrescriptionRepository prescriptionRepository,
                               ConsultationSessionRepository sessionRepository,
                               SessionStateRegistry stateRegistry,
                               SessionLifecycle lifecycle,
                               MessageService messageService,
                               WsNotifier notifier,
                               ConsultProperties props,
                               PlatformTransactionManager txManager) {
        this.prescriptionRepository = prescriptionRepository;
        this.sessionRepository = sessionRepository;
        this.stateRegistry = stateRegistry;
        this.lifecycle = lifecycle;
        this.messageService = messageService;
        this.notifier = notifier;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 医生开方：会话存在 → ONGOING → 处方明细合法且与会话药品一致；同事务完成处方 INSERT + 会话→PRESCRIPTION_ISSUED。 */
    public PrescriptionDTO prescribe(WritePrescriptionRequest req) {
        Long sessionId = req.getSessionId();
        if (sessionId == null) {
            throw new BusinessException("会话标识缺失");
        }
        ConsultationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        SessionState state = stateRegistry.get(sessionId);
        if (state != null) {
            state.lock.lock();
            try {
                ConsultationStatus st = state.getStatus();
                if (st != null && st.isTerminal()) {
                    throw new BusinessException("会话已结束，无法开方");
                }
                if (st != ConsultationStatus.ONGOING) {
                    throw new BusinessException("医生尚未接单，无法开方");
                }
                return doPrescribe(session, req, state);
            } finally {
                state.lock.unlock();
            }
        } else {
            if (session.getStatus() != ConsultationStatus.ONGOING) {
                throw new BusinessException("医生尚未接单或会话已结束，无法开方");
            }
            return doPrescribe(session, req, null);
        }
    }

    private PrescriptionDTO doPrescribe(ConsultationSession session,
                                        WritePrescriptionRequest req, SessionState state) {
        validateItems(req.getItems(), session.getProductId());
        final Long sessionId = session.getId();
        PrescriptionDTO dto = null;
        for (int attempt = 0; attempt < 3 && dto == null; attempt++) {
            try {
                dto = tx.execute(status -> {
                    Prescription p = buildPrescription(session, req);
                    prescriptionRepository.saveAndFlush(p);
                    if (sessionRepository.prescribeComplete(sessionId) == 0) {
                        throw new BusinessException("开方冲突，会话状态已变化");
                    }
                    if (state != null) {
                        state.setStatus(ConsultationStatus.PRESCRIPTION_ISSUED);
                    }
                    return toDTO(p);
                });
            } catch (DataIntegrityViolationException ex) {
                // 处方号同秒 4 位随机碰撞（DB 唯一索引兜底）：重试重新生成号
                if (attempt == 2) {
                    throw ex;
                }
                log.warn("处方号碰撞，重试生成, sessionId={}", sessionId);
            }
        }
        // 事务已提交：落系统消息（含处方号）+ 广播 prescription_issued + 终态收尾（flush/session_ended/purge）
        messageService.enqueueSystemMessage(sessionId, "医生已开具处方，处方号：" + dto.getNo());
        notifier.broadcastPrescriptionIssued(sessionId, dto.getNo(), dto.getValidUntil());
        lifecycle.finalizeTerminal(sessionId, ConsultationStatus.PRESCRIPTION_ISSUED, "PRESCRIPTION_ISSUED");
        return dto;
    }

    /**
     * 一次性消费：存在→ACTIVE→未过期→药品数量匹配→CAS 置 USED+回写 orderNo；成功后会话
     * PRESCRIPTION_ISSUED→ENDED(COMPLETED)。第 0 步快速失败给精确文案，CAS 裁决并发（EX-IM-2~8/26）。
     */
    public PrescriptionDTO consume(ConsumePrescriptionRequest req) {
        String no = req.getPrescriptionNo();
        String orderNo = req.getOrderNo();
        if (no == null || no.isBlank()) {
            throw new BusinessException("处方号不能为空");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException("订单号不能为空"); // P1-02：必须携带 orderNo
        }
        Prescription p = prescriptionRepository.findByNo(no)
                .orElseThrow(() -> new BusinessException("处方不存在或无效"));
        if (p.getStatus() == PrescriptionStatus.ACTIVE && p.getValidUntil().isBefore(LocalDateTime.now())) {
            prescriptionRepository.expireLazy(no); // 懒过期实时落库
            throw new BusinessException("处方已过期");
        }
        if (p.getStatus() != PrescriptionStatus.ACTIVE) {
            throw new BusinessException(rejectMessage(p.getStatus()));
        }
        validateConsumeItems(p.getItems(), req.getItems());
        final Long sessionId = p.getSessionId();
        final boolean[] sessionCompleted = {false};
        PrescriptionDTO dto = tx.execute(status -> {
            if (prescriptionRepository.consume(no, orderNo) == 0) {
                throw new BusinessException("处方消费失败，请重试");
            }
            p.setStatus(PrescriptionStatus.USED);
            p.setConsumedOrderNo(orderNo);
            p.setConsumedAt(LocalDateTime.now());
            // 消费成功 → 会话自动结束（COMPLETED），同事务（CAS 幂等：会话已终态则影响 0 行，处方消费仍成功）
            if (sessionRepository.markCompleted(sessionId, orderNo) == 1) {
                sessionCompleted[0] = true;
                SessionState st = stateRegistry.get(sessionId);
                if (st != null) {
                    st.setStatus(ConsultationStatus.ENDED);
                }
            }
            return toDTO(p);
        });
        if (sessionCompleted[0]) {
            // 提交后广播 session_ended(COMPLETED) + 回收终态内存
            lifecycle.finalizeTerminal(sessionId, ConsultationStatus.ENDED, "COMPLETED");
        }
        return dto;
    }

    /** 补偿恢复（P1-01/P2-03）：仅「被该订单消费」的处方可恢复（USED→ACTIVE），幂等。 */
    public PrescriptionDTO restore(String no, String orderNo) {
        if (no == null || no.isBlank()) {
            throw new BusinessException("处方号不能为空");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException("订单号不能为空");
        }
        Prescription p = prescriptionRepository.findByNo(no)
                .orElseThrow(() -> new BusinessException("处方不存在"));
        if (p.getStatus() != PrescriptionStatus.USED) {
            throw new BusinessException("处方非已使用状态，无需恢复");
        }
        if (!orderNo.equals(p.getConsumedOrderNo())) {
            throw new BusinessException("处方未被该订单消费，无法恢复");
        }
        if (prescriptionRepository.restore(no, orderNo) == 0) {
            throw new BusinessException("处方恢复失败，请重试");
        }
        p.setStatus(PrescriptionStatus.ACTIVE);
        p.setConsumedOrderNo(null);
        p.setConsumedAt(null);
        return toDTO(p);
    }

    public PrescriptionDTO getByNo(String no) {
        Prescription p = prescriptionRepository.findByNo(no)
                .orElseThrow(() -> new BusinessException("处方不存在"));
        lazyExpireIfNeeded(p);
        return toDTO(p);
    }

    public List<PrescriptionDTO> listBySession(Long sessionId) {
        return prescriptionRepository.findBySessionId(sessionId).stream()
                .peek(this::lazyExpireIfNeeded)
                .map(this::toDTO)
                .toList();
    }

    /** 过期懒判定（AC-P3-4/EX-IM-3）：查询/消费时 now > validUntil → 实时置 EXPIRED 落库 */
    private void lazyExpireIfNeeded(Prescription p) {
        if (p.getStatus() == PrescriptionStatus.ACTIVE && p.getValidUntil().isBefore(LocalDateTime.now())) {
            prescriptionRepository.expireLazy(p.getNo());
            p.setStatus(PrescriptionStatus.EXPIRED);
        }
    }

    private void validateItems(List<PrescriptionItem> items, Long productId) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("处方明细不能为空");
        }
        for (PrescriptionItem it : items) {
            if (it.getProductId() == null || it.getProductName() == null || it.getProductName().isBlank()
                    || it.getDosage() == null || it.getDosage().isBlank()
                    || it.getFrequency() == null || it.getFrequency().isBlank()
                    || it.getQuantity() == null || it.getQuantity() <= 0) {
                throw new BusinessException("处方明细不完整：需药品、用法、用量与数量");
            }
            if (!productId.equals(it.getProductId())) {
                throw new BusinessException("处方药品必须与会话目标药品一致");
            }
        }
    }

    private void validateConsumeItems(List<PrescriptionItem> items, List<ConsumePrescriptionItem> reqItems) {
        if (reqItems == null || reqItems.isEmpty()) {
            throw new BusinessException("购买明细不能为空");
        }
        Map<Long, Integer> expected = new HashMap<>();
        for (PrescriptionItem it : items) {
            expected.put(it.getProductId(), it.getQuantity());
        }
        for (ConsumePrescriptionItem it : reqItems) {
            Integer q = expected.get(it.getProductId());
            if (q == null) {
                throw new BusinessException("处方药品与购买药品不符");
            }
            if (!q.equals(it.getQuantity())) {
                throw new BusinessException("下单数量必须与处方开具数量一致");
            }
        }
    }

    private Prescription buildPrescription(ConsultationSession session, WritePrescriptionRequest req) {
        Prescription p = new Prescription();
        p.setNo(generateNo());
        p.setSessionId(session.getId());
        p.setDoctorId(session.getDoctorId());
        p.setDoctorName(session.getDoctorName());
        p.setPatientName(session.getPatientName());
        p.setItems(req.getItems());
        p.setStatus(PrescriptionStatus.ACTIVE);
        p.setValidUntil(LocalDateTime.now().plusDays(props.getPrescriptionValidDays()));
        p.setNote(req.getNote());
        p.setIssuedAt(LocalDateTime.now());
        return p;
    }

    private String generateNo() {
        String ts = LocalDateTime.now().format(NO_FORMAT);
        return "RX" + ts + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String rejectMessage(PrescriptionStatus s) {
        return switch (s) {
            case USED -> "处方已使用，请重新问诊开具处方";
            case EXPIRED -> "处方已过期";
            case REVOKED -> "处方已撤销，无法使用";
            case ACTIVE -> "处方无效";
        };
    }

    public PrescriptionDTO toDTO(Prescription p) {
        PrescriptionDTO dto = new PrescriptionDTO();
        dto.setNo(p.getNo());
        dto.setSessionId(p.getSessionId());
        dto.setDoctorId(p.getDoctorId());
        dto.setDoctorName(p.getDoctorName());
        dto.setPatientName(p.getPatientName());
        dto.setItems(p.getItems());
        dto.setStatus(p.getStatus());
        dto.setStatusLabel(p.getStatus().getLabel());
        dto.setValidUntil(p.getValidUntil());
        dto.setConsumedOrderNo(p.getConsumedOrderNo());
        dto.setNote(p.getNote());
        dto.setIssuedAt(p.getIssuedAt());
        dto.setConsumedAt(p.getConsumedAt());
        return dto;
    }
}
