package com.example.mall.consultservice.service;

import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.MessageSenderType;
import com.example.mall.consultservice.batch.MessageWriteQueue;
import com.example.mall.consultservice.batch.PendingWrite;
import com.example.mall.consultservice.model.ChatMessage;
import com.example.mall.consultservice.netty.WsFrameCodec;
import com.example.mall.consultservice.netty.WsNotifier;
import com.example.mall.consultservice.netty.WsSessionRegistry;
import com.example.mall.consultservice.repository.ChatMessageRepository;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageService 单元测试（Mockito + 真实 SessionStateRegistry）。
 * 事件循环内零阻塞路径：状态校验、内容校验、终态拒绝、clientMsgId 幂等去重（P2-05）、
 * 入队成功/背压丢弃、enqueueSystemMessage、历史查询（先 flush 再查）。
 * <p>
 * 注：终态拒绝「会话已结束，无法发送消息」在 WS 传输层不可确定性触发（结束后服务端即关闭 channel），
 * 故在单元层精确覆盖（AC-C4-8/EX-IM-17）。
 */
class MessageServiceTest {

    private SessionStateRegistry stateRegistry;
    private WsSessionRegistry wsRegistry;
    private WsNotifier notifier;
    private MessageWriteQueue writeQueue;
    private ChatMessageRepository messageRepository;
    private MessageService service;
    private Channel ch;

    @BeforeEach
    void setUp() {
        stateRegistry = new SessionStateRegistry();
        wsRegistry = mock(WsSessionRegistry.class);
        notifier = mock(WsNotifier.class);
        writeQueue = mock(MessageWriteQueue.class);
        messageRepository = mock(ChatMessageRepository.class);
        service = new MessageService(stateRegistry, wsRegistry, notifier, writeQueue, messageRepository);
        ch = new EmbeddedChannel();
        when(writeQueue.offer(eq(1L), any(PendingWrite.class))).thenReturn(true);
    }

    private PendingWrite captureOffered() {
        ArgumentCaptor<PendingWrite> cap = ArgumentCaptor.forClass(PendingWrite.class);
        verify(writeQueue).offer(eq(1L), cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("发消息：会话不存在（内存态缺失）→ 回 SESSION_NOT_FOUND")
    void send_stateNull() {
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "hi", ch);
        verify(notifier).sendError(ch, WsFrameCodec.Code.SESSION_NOT_FOUND, "会话不存在");
    }

    @Test
    @DisplayName("发消息：内容为空 → MESSAGE_TOO_LONG 消息内容不能为空")
    void send_blankContent() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "  ", ch);
        verify(notifier).sendError(ch, WsFrameCodec.Code.MESSAGE_TOO_LONG, "消息内容不能为空");
    }

    @Test
    @DisplayName("发消息：内容超 500 字 → MESSAGE_TOO_LONG 消息内容过长（最多 500 字）")
    void send_tooLongContent() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "a".repeat(501), ch);
        verify(notifier).sendError(ch, WsFrameCodec.Code.MESSAGE_TOO_LONG, "消息内容过长（最多 500 字）");
    }

    @Test
    @DisplayName("发消息：终态会话拒绝 → SESSION_CLOSED 会话已结束，无法发送消息")
    void send_terminalSession() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ENDED, 0);
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "hi", ch);
        verify(notifier).sendError(ch, WsFrameCodec.Code.SESSION_CLOSED, "会话已结束，无法发送消息");
        verify(writeQueue, never()).offer(any(), any());
    }

    @Test
    @DisplayName("发消息成功：分配 seq → 入队，落库成功后广播+ack（先落库后 ack）")
    void send_success() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "hello", ch);
        PendingWrite pending = captureOffered();
        // 模拟批量写线程落库成功 → 触发广播与 ack
        pending.done().complete(null);

        verify(notifier).broadcastMessage(eq(1L), eq(1), eq("USER"), eq("hello"), any(LocalDateTime.class));
        verify(notifier).sendAck(ch, "c1", 1, pending.getMessage().getCreatedAt());
    }

    @Test
    @DisplayName("P2-05 幂等去重：重放同 clientMsgId → 回原 seq 的 ack、不二次入队、不二次广播")
    void send_dedup() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        service.sendTextMessage(1L, MessageSenderType.USER, "dup", "msg1", ch);
        PendingWrite first = captureOffered();
        first.done().complete(null);
        verify(notifier).broadcastMessage(eq(1L), eq(1), eq("USER"), eq("msg1"), any());

        service.sendTextMessage(1L, MessageSenderType.USER, "dup", "msg1-again", ch);

        // 未再次 offer，未再次广播（int 参数用 anyInt，避免 any() 对原始类型解包 NPE）
        verify(writeQueue, times(1)).offer(any(), any());
        verify(notifier, times(1)).broadcastMessage(any(), anyInt(), any(), any(), any(LocalDateTime.class));
        // 两次都回同一 seq 的 ack（幂等成功）
        verify(notifier, times(2)).sendAck(eq(ch), eq("dup"), eq(1), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("背压：入队被拒（队列超限）→ 回 OVERLOADED（并触发 done 异常 → INTERNAL 双帧，记录实际行为）")
    void send_backpressure() {
        stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 0);
        // 模拟真实 offer 拒绝：done 异常完成 + 返回 false
        when(writeQueue.offer(eq(1L), any(PendingWrite.class))).thenAnswer(inv -> {
            PendingWrite p = inv.getArgument(1);
            p.done().completeExceptionally(new IllegalStateException("积压丢弃"));
            return false;
        });
        service.sendTextMessage(1L, MessageSenderType.USER, "c1", "hello", ch);

        // done 异常回调回 INTERNAL
        verify(notifier).sendError(ch, WsFrameCodec.Code.INTERNAL, "消息发送失败，请重试");
        // offer 返回 false 回 OVERLOADED
        verify(notifier).sendError(ch, WsFrameCodec.Code.OVERLOADED, "消息发送繁忙，请稍后重试");
    }

    @Test
    @DisplayName("enqueueSystemMessage：内存态存在 → 锁内分配 seq，落库成功后广播 system")
    void systemMessage_statePresent() {
        SessionState st = stateRegistry.getOrLoad(1L, ConsultationStatus.ONGOING, 5);
        ChatMessage m = service.enqueueSystemMessage(1L, "医生已接入");
        assertEquals(6, m.getSeq());
        assertEquals(6, st.currentSeq());
        assertEquals(MessageSenderType.SYSTEM, m.getSenderType());
        PendingWrite pending = captureOffered();
        pending.done().complete(null);
        verify(notifier).broadcastSystem(eq(1L), eq(6), eq("医生已接入"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("enqueueSystemMessage：内存态缺失 → 用 DB MAX(seq)+1 对齐（P2-06）")
    void systemMessage_stateMissing() {
        when(messageRepository.maxSeq(1L)).thenReturn(8);
        ChatMessage m = service.enqueueSystemMessage(1L, "会话已结束");
        assertEquals(9, m.getSeq());
        verify(writeQueue).offer(eq(1L), any(PendingWrite.class));
    }

    @Test
    @DisplayName("历史：先 flush 写队列（读后写一致），再按 seq 升序查询；afterSeq 增量补拉")
    void history_flushThenQuery() {
        when(writeQueue.flush(1L)).thenReturn(CompletableFuture.completedFuture(null));
        ChatMessage m1 = new ChatMessage(1L, MessageSenderType.USER, "c1", "msg1", 1);
        when(messageRepository.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(m1));

        List<ChatMessageDTO> list = service.history(1L, null);
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getSeq());
        assertEquals("msg1", list.get(0).getContent());
        verify(messageRepository).findBySessionIdOrderBySeqAsc(1L);

        service.history(1L, 3);
        verify(messageRepository).findBySessionIdAndSeqGreaterThanOrderBySeqAsc(1L, 3);
    }

    @Test
    @DisplayName("history：afterSeq 补拉返回空列表不 NPE")
    void history_afterSeqEmpty() {
        when(writeQueue.flush(1L)).thenReturn(CompletableFuture.completedFuture(null));
        when(messageRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(1L, 99)).thenReturn(List.of());
        List<ChatMessageDTO> list = service.history(1L, 99);
        assertNotNull(list);
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("toDTO：字段完整映射")
    void toDto() {
        ChatMessage m = new ChatMessage(1L, MessageSenderType.DOCTOR, null, "你好", 3);
        ChatMessageDTO dto = service.toDTO(m);
        assertEquals(1L, dto.getSessionId());
        assertEquals(MessageSenderType.DOCTOR, dto.getSenderType());
        assertEquals(3, dto.getSeq());
        assertNull(dto.getClientMsgId());
    }
}
