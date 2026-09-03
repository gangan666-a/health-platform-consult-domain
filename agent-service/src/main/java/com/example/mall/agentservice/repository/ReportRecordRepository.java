package com.example.mall.agentservice.repository;

import com.example.mall.agentservice.model.ReportRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 报告记录仓储（shopdb_agent.report_record）。 */
public interface ReportRecordRepository extends JpaRepository<ReportRecord, Long> {

    List<ReportRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 幂等查询：同用户同文件 hash 的历史记录（防重复解析/重复计费）。 */
    Optional<ReportRecord> findByUserIdAndFileHash(Long userId, String fileHash);

    Optional<ReportRecord> findByIdAndUserId(Long id, Long userId);
}
