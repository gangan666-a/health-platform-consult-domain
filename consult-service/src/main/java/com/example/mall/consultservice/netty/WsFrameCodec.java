package com.example.mall.consultservice.netty;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WS 帧编解码：UTF-8 文本 JSON，统一含 {@code type}。客户端 → 服务端 join/message/prescription/end/ping；
 * 服务端 → 客户端 joined/message/system/prescription_issued/session_ended/ack/pong/error。
 */
public final class WsFrameCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 错误码（对应 PRD 文案映射，见设计 §7.3） */
    public static final class Code {
        private Code() {
        }

        public static final String TOKEN_INVALID = "TOKEN_INVALID";
        public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
        public static final String SESSION_CLOSED = "SESSION_CLOSED";
        public static final String OVER_CONNECTION_LIMIT = "OVER_CONNECTION_LIMIT";
        public static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";
        public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
        public static final String BAD_REQUEST = "BAD_REQUEST";
        public static final String OVERLOADED = "OVERLOADED";
        public static final String INTERNAL = "INTERNAL";
    }

    private WsFrameCodec() {
    }

    /** 解析客户端帧，非法 JSON 返回 null */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public static String type(Map<String, Object> frame) {
        Object t = frame.get("type");
        return t == null ? "" : String.valueOf(t);
    }

    public static String serialize(Map<String, Object> frame) {
        try {
            return MAPPER.writeValueAsString(frame);
        } catch (Exception ex) {
            throw new IllegalStateException("WS 帧序列化失败", ex);
        }
    }

    public static String joined(Long sessionId, String role, String status, int lastSeq) {
        return serialize(frame("joined", "sessionId", sessionId, "role", role, "status", status, "lastSeq", lastSeq));
    }

    public static String message(Long sessionId, int seq, String senderType, String content, LocalDateTime createdAt) {
        return serialize(frame("message", "sessionId", sessionId, "seq", seq,
                "senderType", senderType, "content", content, "createdAt", createdAt == null ? null : createdAt.toString()));
    }

    public static String system(Long sessionId, int seq, String content, LocalDateTime createdAt) {
        return serialize(frame("system", "sessionId", sessionId, "seq", seq,
                "content", content, "createdAt", createdAt == null ? null : createdAt.toString()));
    }

    public static String prescriptionIssued(Long sessionId, String prescriptionNo, LocalDateTime validUntil) {
        return serialize(frame("prescription_issued", "sessionId", sessionId,
                "prescriptionNo", prescriptionNo, "validUntil", validUntil == null ? null : validUntil.toString()));
    }

    public static String sessionEnded(Long sessionId, String status, String endReason, LocalDateTime endedAt) {
        return serialize(frame("session_ended", "sessionId", sessionId, "status", status,
                "endReason", endReason, "endedAt", endedAt == null ? null : endedAt.toString()));
    }

    public static String ack(String clientMsgId, int seq, LocalDateTime createdAt) {
        return serialize(frame("ack", "clientMsgId", clientMsgId, "seq", seq,
                "createdAt", createdAt == null ? null : createdAt.toString()));
    }

    public static String pong() {
        return serialize(frame("pong"));
    }

    public static String ping() {
        return serialize(frame("ping"));
    }

    public static String error(String code, String message) {
        return serialize(frame("error", "code", code, "message", message));
    }

    private static Map<String, Object> frame(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
