package com.example.mall.agentservice.repository;

import com.example.mall.agentservice.model.ChatMessageRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** L0 会话原文仓储：按 memoryKey 倒序取窗口（调用方反转即为正序），clear 按 key 全删。 */
public interface ChatMessageRecordRepository extends JpaRepository<ChatMessageRecord, Long> {

    List<ChatMessageRecord> findByMemoryKeyOrderByIdDesc(String memoryKey, Pageable pageable);

    @Transactional
    void deleteByMemoryKey(String memoryKey);
}
