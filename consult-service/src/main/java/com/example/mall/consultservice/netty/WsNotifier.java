package com.example.mall.consultservice.netty;

import com.example.mall.common.model.ConsultationStatus;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 帧推送门面：把广播/ack/error 封装为「查注册表 + 写 channel」。
 * channel.writeAndFlush 线程安全，可从任意线程调用（投递到目标 channel 的事件循环执行）。
 */
@Component
public class WsNotifier {

    private final WsSessionRegistry registry;

    public WsNotifier(WsSessionRegistry registry) {
        this.registry = registry;
    }

    /** 广播实时消息帧（含发送者回显） */
    public void broadcastMessage(Long sessionId, int seq, String senderType, String content, LocalDateTime createdAt) {
        String payload = WsFrameCodec.message(sessionId, seq, senderType, content, createdAt);
        broadcast(sessionId, payload);
    }

    /** 广播系统消息帧（医生接入/会话结束/开方等关键事件） */
    public void broadcastSystem(Long sessionId, int seq, String content, LocalDateTime createdAt) {
        broadcast(sessionId, WsFrameCodec.system(sessionId, seq, content, createdAt));
    }

    public void broadcastPrescriptionIssued(Long sessionId, String prescriptionNo, LocalDateTime validUntil) {
        broadcast(sessionId, WsFrameCodec.prescriptionIssued(sessionId, prescriptionNo, validUntil));
    }

    public void broadcastSessionEnded(Long sessionId, ConsultationStatus status, String endReason, LocalDateTime endedAt) {
        broadcast(sessionId, WsFrameCodec.sessionEnded(sessionId, status.name(), endReason, endedAt));
    }

    public void sendAck(Channel channel, String clientMsgId, int seq, LocalDateTime createdAt) {
        write(channel, WsFrameCodec.ack(clientMsgId, seq, createdAt));
    }

    public void sendError(Channel channel, String code, String message) {
        write(channel, WsFrameCodec.error(code, message));
    }

    public void sendJoined(Channel channel, Long sessionId, String role, ConsultationStatus status, int lastSeq) {
        write(channel, WsFrameCodec.joined(sessionId, role, status.name(), lastSeq));
    }

    public void sendPong(Channel channel) {
        write(channel, WsFrameCodec.pong());
    }

    public void sendPing(Channel channel) {
        write(channel, WsFrameCodec.ping());
    }

    public void sendFrame(Channel channel, String json) {
        write(channel, json);
    }

    private void broadcast(Long sessionId, String json) {
        Set<Channel> channels = registry.channels(sessionId);
        for (Channel ch : channels) {
            write(ch, json);
        }
    }

    private void write(Channel channel, String json) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }
}
