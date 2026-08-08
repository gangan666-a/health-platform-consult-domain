package com.example.mall.consultservice.batch;

import com.example.mall.common.model.MessageSenderType;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ChatMessage;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageWriteQueue 纯单元测试（真实队列 + mock JdbcTemplate，不连库）。
 * - P1-04 逐行容错：批量含重复 clientMsgId 不拖垮整批、不重试死循环（batchUpdate 仅一次）；
 * - flush 可见性：flush().join() 后 done future 全部正常完成（先落库后 ack）；
 * - 队列上限背压：超限 offer 返回 false，pending 异常完成。
 * <p>
 * 参数：capacity=3 / threshold=100（禁用阈值自动 drain，测试用 flush() 精确驱动）/ interval=60s（定时兜底不触发）。
 * 构造后 scheduler 线程为 daemon，@AfterEach 调 destroy() 关闭。
 */
class MessageWriteQueueTest {

    private ConsultProperties props;
    private JdbcTemplate jdbcTemplate;
    private ConsultationSessionRepository sessionRepository;
    private MessageWriteQueue queue;

    @BeforeEach
    void setUp() {
        props = new ConsultProperties();
        props.setBatchQueueCapacity(3);
        props.setBatchDrainThreshold(100);
        props.setBatchDrainIntervalMs(60_000);
        jdbcTemplate = mock(JdbcTemplate.class);
        sessionRepository = mock(ConsultationSessionRepository.class);
        queue = new MessageWriteQueue(props, jdbcTemplate, sessionRepository);
    }

    @AfterEach
    void tearDown() {
        queue.destroy();
    }

    private PendingWrite text(Long sessionId, String clientMsgId, int seq) {
        return new PendingWrite(new ChatMessage(sessionId, MessageSenderType.USER, clientMsgId, "内容-" + seq, seq));
    }

    private PendingWrite system(Long sessionId, int seq) {
        return new PendingWrite(new ChatMessage(sessionId, MessageSenderType.SYSTEM, null, "系统-" + seq, seq));
    }

    @Test
    @DisplayName("offer+flush：批量 INSERT 一次落库全部消息，done 正常完成，刷新活动时间")
    void offerThenFlush_persistsAll() throws Exception {
        when(sessionRepository.touchActive(any(), any())).thenReturn(1);
        PendingWrite m1 = text(1L, "c1", 1);
        PendingWrite m2 = text(1L, "c2", 2);
        PendingWrite m3 = text(1L, "c3", 3);
        assertTrue(queue.offer(1L, m1));
        assertTrue(queue.offer(1L, m2));
        assertTrue(queue.offer(1L, m3));

        CompletableFuture<Void> f = queue.flush(1L);
        f.get(3, TimeUnit.SECONDS);

        assertTrue(m1.done().isDone() && !m1.done().isCompletedExceptionally());
        assertTrue(m2.done().isDone() && !m2.done().isCompletedExceptionally());
        assertTrue(m3.done().isDone() && !m3.done().isCompletedExceptionally());

        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, times(1)).batchUpdate(anyString(), cap.capture());
        List<Object[]> args = cap.getValue();
        assertNotNull(args);
        assertEquals(3, args.size());
        // 行内容：sessionId, seq, senderType, clientMsgId, content
        assertEquals(1L, args.get(0)[0]);
        assertEquals(1, args.get(0)[1]);
        assertEquals(MessageSenderType.USER.name(), args.get(0)[2]);
        assertEquals("c1", args.get(0)[3]);
        // 文本消息刷新活动时间
        verify(sessionRepository).touchActive(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("P1-04：批量含重复 clientMsgId（唯一键冲突）→ INSERT IGNORE 不抛异常、整批成功、不重试")
    void batchWithDuplicateClientMsgId_ok() throws Exception {
        // 同一会话重复 clientMsgId 重放（唯一键 (session_id, client_msg_id)）
        PendingWrite m1 = text(1L, "dup", 1);
        PendingWrite m2 = text(1L, "dup", 2);
        PendingWrite m3 = text(1L, "c3", 3);
        queue.offer(1L, m1);
        queue.offer(1L, m2);
        queue.offer(1L, m3);

        queue.flush(1L).get(3, TimeUnit.SECONDS);

        // 逐行容错：重复行「已存在」视为幂等成功，不拖垮整批、不触发重试死循环
        assertTrue(m1.done().isDone() && !m1.done().isCompletedExceptionally());
        assertTrue(m2.done().isDone() && !m2.done().isCompletedExceptionally());
        assertTrue(m3.done().isDone() && !m3.done().isCompletedExceptionally());
        verify(jdbcTemplate, times(1)).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("P1-04：DB 批量写失败 → 本批 done 全部异常完成、batchUpdate 仅一次（无重试死循环）")
    void batchFailure_noRetryLoop() {
        when(jdbcTemplate.batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any()))
                .thenThrow(new DataIntegrityViolationException("非唯一键冲突之外的 DB 异常"));
        PendingWrite m1 = text(1L, "c1", 1);
        PendingWrite m2 = text(1L, "c2", 2);
        queue.offer(1L, m1);
        queue.offer(1L, m2);

        CompletableFuture<Void> f = queue.flush(1L);
        assertTrue(f.isCompletedExceptionally());
        assertTrue(m1.done().isCompletedExceptionally());
        assertTrue(m2.done().isCompletedExceptionally());
        // 关键：异常只记一次、丢弃本批，绝不重试循环
        verify(jdbcTemplate, times(1)).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("队列上限背压：capacity=3，第 4 条 offer 拒绝且 pending 异常完成")
    void queueOverflow_rejected() {
        queue.offer(1L, text(1L, "c1", 1));
        queue.offer(1L, text(1L, "c2", 2));
        queue.offer(1L, text(1L, "c3", 3));
        PendingWrite m4 = text(1L, "c4", 4);

        boolean accepted = queue.offer(1L, m4);

        assertFalse(accepted);
        assertTrue(m4.done().isCompletedExceptionally());
        // 背压不触发 drain（阈值 100 未到），也不落库
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("flush 空队列：返回已完成 future，不碰 DB")
    void flushEmpty() throws Exception {
        queue.flush(1L).get(1, TimeUnit.SECONDS);
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("SYSTEM 消息不刷新活动时间（AC-C4-4：空闲计时仅算文本消息）")
    void systemMessages_noTouchActive() throws Exception {
        queue.offer(1L, system(1L, 1));
        queue.offer(1L, system(1L, 2));
        queue.flush(1L).get(3, TimeUnit.SECONDS);
        verify(sessionRepository, never()).touchActive(any(), any());
    }
}
