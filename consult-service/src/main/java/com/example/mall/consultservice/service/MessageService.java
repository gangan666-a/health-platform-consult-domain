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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务：事件循环内的「校验 → 分配 seq → 入批量写队列」，落库后由写队列回调广播/ack（先落库后 ack，NFR-6/M10）。
 * <p>
 * 关键约束：本类在 <b>事件循环线程</b> 被调用，绝不能同步查库/阻塞（ADR-IM-04）。
 * 会话状态校验用内存态 SessionState（由所有 CAS 路径同步更新，DB 仍为最终权威）；历史查询走 REST 线程。
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final int CONTENT_MAX_LENGTH = 500;

    private final SessionStateRegistry stateRegistry;
    private final WsSessionRegistry wsRegistry;
    private final WsNotifier notifier;
    private final MessageWriteQueue writeQueue;
    private final ChatMessageRepository messageRepository;

    public MessageService(SessionStateRegistry stateRegistry,
                          WsSessionRegistry wsRegistry,
                          WsNotifier notifier,
                          MessageWriteQueue writeQueue,
                          ChatMessageRepository messageRepository) {
        this.stateRegistry = stateRegistry;
        this.wsRegistry = wsRegistry;
        this.notifier = notifier;
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
    }

    /**
     * 处理用户/医生文本消息（事件循环调用，零阻塞）。拒绝/去重场景直接回帧，成功场景经写队列落库后回调。
     */
    public void sendTextMessage(Long sessionId, MessageSenderType senderType, String clientMsgId,
                                String content, Channel senderChannel) {
        SessionState state = stateRegistry.get(sessionId);
        if (state == null) {
            notifier.sendError(senderChannel, WsFrameCodec.Code.SESSION_NOT_FOUND, "会话不存在");
            return;
        }
        if (content == null || content.isBlank()) {
            notifier.sendError(senderChannel, WsFrameCodec.Code.MESSAGE_TOO_LONG, "消息内容不能为空");
            return;
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            notifier.sendError(senderChannel, WsFrameCodec.Code.MESSAGE_TOO_LONG, "消息内容过长（最多 500 字）");
            return;
        }

        Integer seq;
        boolean dedup;
        boolean accepted = true;
        state.lock.lock();
        try {
            // 终态会话拒绝发消息（AC-C4-8/EX-IM-17）
            ConsultationStatus status = state.getStatus();
            if (status != null && status.isTerminal()) {
                notifier.sendError(senderChannel, WsFrameCodec.Code.SESSION_CLOSED, "会话已结束，无法发送消息");
                return;
            }
            // P2-05：会话锁内幂等去重——重放同 clientMsgId 返回同 seq，不重复广播、不二次计 seq
            Integer seen = state.seenSeq(clientMsgId);
            if (seen != null) {
                dedup = true;
                seq = seen;
            } else {
                dedup = false;
                seq = state.nextSeq();
                state.markSeen(clientMsgId, seq);
            }

            if (!dedup) {
                // [P1 修复：跨连接消息乱序] writeQueue.offer 移入会话锁内，与 seq 分配同锁，
                // 保证「入队顺序 = seq 分配顺序」。此前 offer 在锁外：用户/医生分属不同事件循环线程
                // 并发发送时，入队先后可偏离 seq 分配先后，批内落库/广播顺序即偏离 seq（PRD 并发不乱序）。
                // 锁粒度与性能：offer 仅对每会话 ArrayDeque 加锁（纳秒级）并可能提交一次 drain 任务（O(1)），
                // 无 DB/网络阻塞，锁持有时间仍为纳秒级，不构成事件循环阻塞热点（ADR-IM-04）。
                ChatMessage message = new ChatMessage(sessionId, senderType, clientMsgId, content, seq);
                PendingWrite pending = new PendingWrite(message);
                // 落库成功后：广播给本会话全部 channel（含发送者回显）+ 回 ack；失败回 error（不丢已 ack 消息）
                pending.done().whenComplete((v, ex) -> {
                    if (ex == null) {
                        notifier.broadcastMessage(sessionId, seq, senderType.name(), content, message.getCreatedAt());
                        notifier.sendAck(senderChannel, clientMsgId, seq, message.getCreatedAt());
                    } else {
                        log.warn("消息落库失败，sessionId={}, seq={}", sessionId, seq);
                        notifier.sendError(senderChannel, WsFrameCodec.Code.INTERNAL, "消息发送失败，请重试");
                    }
                });
                accepted = writeQueue.offer(sessionId, pending);
            }
        } finally {
            state.lock.unlock();
        }

        if (dedup) {
            // 重复帧视为幂等成功，回 ack（带原 seq），不重广播（AC-C2-5 幂等）
            notifier.sendAck(senderChannel, clientMsgId, seq, LocalDateTime.now());
            return;
        }
        if (!accepted) {
            // 背压丢弃（队列超上限），done 已异常完成
            notifier.sendError(senderChannel, WsFrameCodec.Code.OVERLOADED, "消息发送繁忙，请稍后重试");
        }
    }

    /**
     * 落一条 SYSTEM 消息并广播（医生接入/会话结束/开方等关键事件）。返回消息（含 seq）供调用方拼其他帧。
     * 在 REST/写线程调用（非事件循环），允许等待 flush 保证「先落库再广播结束帧」。
     */
    public ChatMessage enqueueSystemMessage(Long sessionId, String content) {
        SessionState state = stateRegistry.get(sessionId);
        int seq;
        if (state != null) {
            state.lock.lock();
            try {
                seq = state.nextSeq();
            } finally {
                state.lock.unlock();
            }
        } else {
            seq = messageRepository.maxSeq(sessionId) + 1;
        }
        ChatMessage message = new ChatMessage(sessionId, MessageSenderType.SYSTEM, null, content, seq);
        PendingWrite pending = new PendingWrite(message);
        pending.done().whenComplete((v, ex) -> {
            if (ex == null) {
                notifier.broadcastSystem(sessionId, seq, content, message.getCreatedAt());
            }
        });
        writeQueue.offer(sessionId, pending);
        return message;
    }

    /** 查询历史：先 flush 写队列（保证「刚发的消息查询可见」，读后写一致），再按 seq 升序返回 */
    public List<ChatMessageDTO> history(Long sessionId, Integer afterSeq) {
        writeQueue.flush(sessionId).join();
        List<ChatMessage> messages = (afterSeq == null)
                ? messageRepository.findBySessionIdOrderBySeqAsc(sessionId)
                : messageRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq);
        return messages.stream().map(this::toDTO).toList();
    }

    public ChatMessageDTO toDTO(ChatMessage m) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(m.getId());
        dto.setSessionId(m.getSessionId());
        dto.setSenderType(m.getSenderType());
        dto.setClientMsgId(m.getClientMsgId());
        dto.setContent(m.getContent());
        dto.setSeq(m.getSeq());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }
}
