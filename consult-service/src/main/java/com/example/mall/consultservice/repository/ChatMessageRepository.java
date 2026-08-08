package com.example.mall.consultservice.repository;

import com.example.mall.consultservice.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 消息仓储。历史查询走覆盖索引（session_id, seq）；批量写入经 JdbcTemplate（INSERT IGNORE 逐行容错）而非本仓储。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderBySeqAsc(Long sessionId);

    /** 断线重连增量补拉：afterSeq 之后的消息按 seq 升序（AC-C4-2/AC-IM-3） */
    List<ChatMessage> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(Long sessionId, int afterSeq);

    /** 已持久化最大 seq（会话首次发消息前惰性加载，重启后与内存计数对齐，P2-06） */
    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM ChatMessage m WHERE m.sessionId = :sessionId")
    int maxSeq(@Param("sessionId") Long sessionId);
}
