package com.example.mall.consultservice.repository;

import com.example.mall.common.model.PrescriptionStatus;
import com.example.mall.consultservice.model.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 处方仓储。消费/恢复/过期/撤销全部走单条 CAS UPDATE，行锁串行化（防复用唯一边界，EX-IM-26）。
 */
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    Optional<Prescription> findByNo(String no);

    List<Prescription> findBySessionId(Long sessionId);

    List<Prescription> findByStatusAndValidUntilBefore(PrescriptionStatus status, LocalDateTime cutoff);

    /**
     * 一次性消费：存在→ACTIVE→未过期 全条件原子比对，affectedRows=1 才成功。
     * 药品/数量匹配已在第 0 步校验（本 CAS 负责并发正确性裁决）。
     */
    @Transactional
    @Modifying
    @Query("UPDATE Prescription p SET p.status = 'USED', p.consumedOrderNo = :orderNo, "
            + "p.consumedAt = CURRENT_TIMESTAMP WHERE p.no = :no AND p.status = 'ACTIVE' AND p.validUntil > CURRENT_TIMESTAMP")
    int consume(@Param("no") String no, @Param("orderNo") String orderNo);

    /** 恢复（下单失败补偿，P1-01/P2-03）：仅「被该订单消费」的处方可恢复，幂等 */
    @Transactional
    @Modifying
    @Query("UPDATE Prescription p SET p.status = 'ACTIVE', p.consumedOrderNo = NULL, p.consumedAt = NULL "
            + "WHERE p.no = :no AND p.status = 'USED' AND p.consumedOrderNo = :orderNo")
    int restore(@Param("no") String no, @Param("orderNo") String orderNo);

    /** 过期懒判定：查询/消费时 now > validUntil → 实时置 EXPIRED */
    @Transactional
    @Modifying
    @Query("UPDATE Prescription p SET p.status = 'EXPIRED' "
            + "WHERE p.no = :no AND p.status = 'ACTIVE' AND p.validUntil < CURRENT_TIMESTAMP")
    int expireLazy(@Param("no") String no);

    /** 过期定时扫描兜底（与懒判定双保险） */
    @Transactional
    @Modifying
    @Query("UPDATE Prescription p SET p.status = 'EXPIRED' "
            + "WHERE p.id IN :ids AND p.status = 'ACTIVE' AND p.validUntil < CURRENT_TIMESTAMP")
    int expireByIds(@Param("ids") List<Long> ids);
}
