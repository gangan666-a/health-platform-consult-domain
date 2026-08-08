package com.example.mall.consultservice.service;

import com.example.mall.common.model.ConsultationStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话内存态注册表（sessionId → SessionState）。
 * <p>
 * 所有会话创建/接诊等 REST 路径在签发 WS token 的同时预加载 SessionState（DB 权威状态 + MAX(seq)），
 * 保证 WS join/发消息在事件循环内无需查库即可快速裁决（事件循环零阻塞的前提，ADR-IM-04）。
 * 会话进入终态时由 {@link #remove} 回收，防内存无界增长（P1-03）。
 */
@Component
public class SessionStateRegistry {

    private final ConcurrentMap<Long, SessionState> states = new ConcurrentHashMap<>();

    /** 预加载（创建/接诊时调用，Spring 线程可阻塞查库） */
    public SessionState getOrLoad(Long sessionId, ConsultationStatus status, int maxSeq) {
        return states.computeIfAbsent(sessionId, id -> new SessionState(id, status, maxSeq));
    }

    public SessionState get(Long sessionId) {
        return states.get(sessionId);
    }

    /** 会话进入终态时回收内存态（seq 计数/seen-map/离线标记一并释放） */
    public void remove(Long sessionId) {
        states.remove(sessionId);
    }

    public Collection<SessionState> all() {
        return states.values();
    }
}
