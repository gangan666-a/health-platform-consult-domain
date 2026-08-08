package com.example.mall.consultservice.netty;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.common.model.MessageSenderType;
import com.example.mall.common.model.PrescriptionItem;
import com.example.mall.common.model.WritePrescriptionRequest;
import com.example.mall.consultservice.service.MessageService;
import com.example.mall.consultservice.service.PrescriptionService;
import com.example.mall.consultservice.service.SessionLifecycle;
import com.example.mall.consultservice.service.SessionState;
import com.example.mall.consultservice.service.SessionStateRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * WS 帧处理器：事件循环内零阻塞（ADR-IM-04/§4.1）。
 * <p>
 * 事件循环线程只做「反序列化 → 会话锁内校验/分配/绑定 → 入队或回帧」；落库经批量写队列（消息）与
 * 独立执行器（结束/开方），绝不在 channelRead0 内查库/阻塞。
 * <p>
 * <b>fail-closed 隔离</b>（§3.3）：channel 未绑定会话 → 除 join/ping 外全部静默丢弃；入站帧 payload
 * sessionId 与绑定不一致 → 丢弃；prescription 帧仅 DOCTOR 槽位；终态会话拒绝 join/message。
 * <b>P0-01 接管</b>：join 在会话锁内「关旧绑新」，静默断线缓冲期内重连成功。
 * <b>心跳</b>：读空闲两周期（约 2×探活周期）无响应 → 判死关闭计入断线路径。
 */
@Sharable
@Component
public class WsFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WsFrameHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 连续失联计数：读空闲发 ping，两个周期无任何入站帧则判死关闭 */
    private static final AttributeKey<Integer> ATTR_PING_MISSED = AttributeKey.valueOf("pingMissed");

    private final WsSessionRegistry registry;
    private final SessionStateRegistry stateRegistry;
    private final WsNotifier notifier;
    private final MessageService messageService;
    private final SessionLifecycle lifecycle;
    private final PrescriptionService prescriptionService;
    private final ExecutorService taskExecutor;

    public WsFrameHandler(WsSessionRegistry registry,
                          SessionStateRegistry stateRegistry,
                          WsNotifier notifier,
                          MessageService messageService,
                          SessionLifecycle lifecycle,
                          PrescriptionService prescriptionService,
                          ExecutorService nettyTaskExecutor) {
        this.registry = registry;
        this.stateRegistry = stateRegistry;
        this.notifier = notifier;
        this.messageService = messageService;
        this.lifecycle = lifecycle;
        this.prescriptionService = prescriptionService;
        this.taskExecutor = nettyTaskExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        Channel ch = ctx.channel();
        // 任何入站帧都视为对端存活：清零连续失联计数
        ch.attr(ATTR_PING_MISSED).set(0);

        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame());
            return;
        }
        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        if (!(frame instanceof TextWebSocketFrame)) {
            return; // 二进制帧忽略（fail-closed）
        }
        Map<String, Object> f = WsFrameCodec.parse(((TextWebSocketFrame) frame).text());
        if (f == null) {
            notifier.sendError(ch, WsFrameCodec.Code.BAD_REQUEST, "非法帧格式");
            return;
        }
        switch (WsFrameCodec.type(f)) {
            case "join" -> handleJoin(ch, f);
            case "message" -> handleMessage(ch, f);
            case "prescription" -> handlePrescription(ch, f);
            case "end" -> handleEnd(ch);
            case "ping" -> notifier.sendPong(ch);
            default -> notifier.sendError(ch, WsFrameCodec.Code.BAD_REQUEST, "未知帧类型");
        }
    }

    /** join：token 校验 → 会话锁内「关旧绑新」（P0-01）+ 清断线标记 → 回 joined（含 lastSeq 供增量补齐） */
    private void handleJoin(Channel ch, Map<String, Object> f) {
        Long sessionId = asLong(f.get("sessionId"));
        Object rawToken = f.get("token");
        String token = rawToken == null ? null : String.valueOf(rawToken);
        if (sessionId == null || token == null || token.isBlank()) {
            notifier.sendError(ch, WsFrameCodec.Code.BAD_REQUEST, "join 需携带 sessionId 与 token");
            return;
        }
        if (!registry.isTokenValid(token, sessionId)) {
            notifier.sendError(ch, WsFrameCodec.Code.TOKEN_INVALID, "token 无效或不属于该会话");
            return;
        }
        String role = registry.roleOfToken(token);
        SessionState state = stateRegistry.get(sessionId);
        if (state == null) {
            notifier.sendError(ch, WsFrameCodec.Code.SESSION_NOT_FOUND, "会话不存在");
            return;
        }
        state.lock.lock();
        try {
            ConsultationStatus status = state.getStatus();
            if (status != null && status.isTerminal()) {
                notifier.sendError(ch, WsFrameCodec.Code.SESSION_CLOSED, "会话已结束");
                return;
            }
            registry.bind(sessionId, role, ch); // P0-01：关旧绑新（take-over）
            state.clearDisconnect(role);        // 重连接管成功：清断线缓冲标记
            notifier.sendJoined(ch, sessionId, role, status, state.currentSeq());
        } finally {
            state.lock.unlock();
        }
    }

    /** message：校验帧归属 → 事件循环安全入队（校验/分配 seq 在消息服务内会话锁完成） */
    private void handleMessage(Channel ch, Map<String, Object> f) {
        Long sessionId = ch.attr(WsSessionRegistry.ATTR_SESSION_ID).get();
        if (sessionId == null) {
            return; // 未绑定会话：静默丢弃（fail-closed）
        }
        if (!sessionId.equals(asLong(f.get("sessionId")))) {
            notifier.sendError(ch, WsFrameCodec.Code.NOT_AUTHORIZED, "sessionId 与连接绑定不一致");
            return;
        }
        String role = registry.roleOf(ch);
        MessageSenderType senderType = WsSessionRegistry.ROLE_DOCTOR.equals(role)
                ? MessageSenderType.DOCTOR : MessageSenderType.USER;
        String clientMsgId = f.get("clientMsgId") == null ? null : String.valueOf(f.get("clientMsgId"));
        String content = f.get("content") == null ? null : String.valueOf(f.get("content"));
        messageService.sendTextMessage(sessionId, senderType, clientMsgId, content, ch);
    }

    /** prescription：仅 DOCTOR 槽位；开方涉及 DB + 广播，卸载到独立执行器（事件循环零阻塞） */
    private void handlePrescription(Channel ch, Map<String, Object> f) {
        Long sessionId = ch.attr(WsSessionRegistry.ATTR_SESSION_ID).get();
        if (sessionId == null) {
            return;
        }
        if (!sessionId.equals(asLong(f.get("sessionId")))) {
            notifier.sendError(ch, WsFrameCodec.Code.NOT_AUTHORIZED, "sessionId 与连接绑定不一致");
            return;
        }
        if (!WsSessionRegistry.ROLE_DOCTOR.equals(registry.roleOf(ch))) {
            notifier.sendError(ch, WsFrameCodec.Code.NOT_AUTHORIZED, "仅医生可开具处方");
            return;
        }
        List<PrescriptionItem> items = parseItems(f.get("items"));
        if (items == null || items.isEmpty()) {
            notifier.sendError(ch, WsFrameCodec.Code.BAD_REQUEST, "处方明细不能为空");
            return;
        }
        String note = f.get("note") == null ? null : String.valueOf(f.get("note"));
        Long sid = sessionId;
        taskExecutor.execute(() -> {
            try {
                WritePrescriptionRequest req = new WritePrescriptionRequest();
                req.setSessionId(sid);
                req.setItems(items);
                req.setNote(note);
                prescriptionService.prescribe(req);
            } catch (BusinessException ex) {
                notifier.sendError(ch, WsFrameCodec.Code.BAD_REQUEST, ex.getMessage());
            } catch (Exception ex) {
                log.error("WS 开方失败, sessionId={}", sid, ex);
                notifier.sendError(ch, WsFrameCodec.Code.INTERNAL, "开方失败，请稍后重试");
            }
        });
    }

    /** end：主动结束（USER/DOCTOR），卸载到独立执行器 → 统一结束流程（广播/清理） */
    private void handleEnd(Channel ch) {
        Long sessionId = ch.attr(WsSessionRegistry.ATTR_SESSION_ID).get();
        if (sessionId == null) {
            return;
        }
        String role = registry.roleOf(ch);
        String reason = WsSessionRegistry.ROLE_DOCTOR.equals(role) ? "DOCTOR_ENDED" : "USER_ENDED";
        Long sid = sessionId;
        taskExecutor.execute(() -> {
            try {
                lifecycle.endSession(sid, reason);
            } catch (Exception ex) {
                log.warn("WS 主动结束失败, sessionId={}", sid, ex);
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        Long sessionId = registry.sessionIdOf(ch);
        String role = registry.roleOf(ch);
        registry.unbind(ch);
        if (sessionId != null && role != null) {
            lifecycle.onDisconnect(sessionId, role); // 内部判 isRoleOnline，接管后不记录离线
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            Channel ch = ctx.channel();
            Integer missed = ch.attr(ATTR_PING_MISSED).get();
            if (missed == null) {
                missed = 0;
            }
            missed++;
            if (missed >= 2) {
                // 连续两个探活周期无响应 → 判死关闭（计入断线缓冲路径）
                ctx.close();
                return;
            }
            ch.attr(ATTR_PING_MISSED).set(missed);
            notifier.sendPing(ch);
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    private List<PrescriptionItem> parseItems(Object raw) {
        if (!(raw instanceof List<?>)) {
            return null;
        }
        try {
            return MAPPER.convertValue(raw, new TypeReference<List<PrescriptionItem>>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
