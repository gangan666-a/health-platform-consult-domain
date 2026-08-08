package com.example.mall.consultservice.repository;

import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.consultservice.model.ConsultationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。状态变更全部走单条 CAS UPDATE（影响行数裁决），DB 为唯一并发正确性边界（ADR-IM-05）。
 */
public interface ConsultationSessionRepository extends JpaRepository<ConsultationSession, Long> {

    /** 同药品未结束会话复用判定（演示无 userId，按药品去重）；最新优先 */
    Optional<ConsultationSession> findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
            Long productId, Collection<ConsultationStatus> statuses);

    List<ConsultationSession> findByStatusIn(Collection<ConsultationStatus> statuses);

    /** 空闲超时扫描：ONGOING 且最近活动早于 cutoff */
    List<ConsultationSession> findByStatusAndLastActiveAtBefore(ConsultationStatus status, LocalDateTime cutoff);

    /** 医生接单：WAITING→ONGOING，绑定医生；affectedRows=1 才成功 */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.status = 'ONGOING', s.doctorId = :doctorId, "
            + "s.doctorName = :doctorName WHERE s.id = :id AND s.status = 'WAITING'")
    int accept(@Param("id") Long id, @Param("doctorId") String doctorId, @Param("doctorName") String doctorName);

    /** 主动结束/断线结束：WAITING|ONGOING→ENDED */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.status = 'ENDED', s.endReason = :reason, s.endedAt = CURRENT_TIMESTAMP "
            + "WHERE s.id = :id AND s.status IN ('WAITING','ONGOING')")
    int endSession(@Param("id") Long id, @Param("reason") String reason);

    /** 开方完成自动结束：ONGOING→PRESCRIPTION_ISSUED（与处方 INSERT 同事务） */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.status = 'PRESCRIPTION_ISSUED' "
            + "WHERE s.id = :id AND s.status = 'ONGOING'")
    int prescribeComplete(@Param("id") Long id);

    /** 空闲超时：ONGOING 且超时 → EXPIRED（调度器 CAS，幂等） */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.status = 'EXPIRED', s.endedAt = CURRENT_TIMESTAMP "
            + "WHERE s.id = :id AND s.status = 'ONGOING' AND s.lastActiveAt < :cutoff")
    int expireIdle(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff);

    /** 凭处方下单成功：PRESCRIPTION_ISSUED→ENDED(COMPLETED)，回写订单号（consume 事务内同置） */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.status = 'ENDED', s.endReason = 'COMPLETED', "
            + "s.orderNo = :orderNo, s.endedAt = CURRENT_TIMESTAMP "
            + "WHERE s.id = :id AND s.status = 'PRESCRIPTION_ISSUED'")
    int markCompleted(@Param("id") Long id, @Param("orderNo") String orderNo);

    /** 文本消息落库后刷新活动时间（心跳/系统消息不计入） */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.lastActiveAt = :time "
            + "WHERE s.id = :id AND s.status IN ('WAITING','ONGOING')")
    int touchActive(@Param("id") Long id, @Param("time") LocalDateTime time);

    /** 心跳时间戳（仅观测用，不参与空闲判定；异步落库避免阻塞事件循环） */
    @Transactional
    @Modifying
    @Query("UPDATE ConsultationSession s SET s.heartbeatAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int updateHeartbeat(@Param("id") Long id);
}
