package com.example.mall.consultservice.service;

import com.example.mall.common.model.ConsultationStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每会话内存态（仅快速裁决，非权威；DB 为唯一权威）。
 * <p>
 * 会话锁 {@code lock} 串行化同一会话的裁决类操作（发消息校验/分配 seq、接诊、开方、结束、断线判定），
 * 锁内只做内存操作与入队（纳秒级），绝不碰 DB（ADR-IM-04/§4.1）。
 * <p>
 * <b>seq</b>：会话内单调递增；首次使用时以 DB {@code MAX(seq)} 惰性加载对齐（P2-06），
 * 会话进入终态后由 {@link SessionStateRegistry#remove} 回收。
 * <p>
 * <b>clientMsgId 去重 seen-map（P2-05）</b>：会话锁内判定「查库未命中→入队」的竞态，
 * 防止同 clientMsgId 双计 seq；DB 唯一键仅兜底。
 */
public class SessionState {

    /** 每会话串行锁：跨事件循环串行化裁决类操作 */
    public final ReentrantLock lock = new ReentrantLock();

    private final Long sessionId;
    /** 内存态缓存状态（所有 CAS 路径同步更新，供事件循环 O(1) 快速裁决，免阻塞查库） */
    private volatile ConsultationStatus status;
    /** 会话内单调 seq：演示量级用 int 足够（4B 条/会话），避免 AtomicLong→int 转换损耗 */
    private final AtomicInteger seq = new AtomicInteger();
    /** 断线缓冲：role → 最近离线时刻（epoch ms，0=在线），锁内访问 */
    private final Map<String, Long> lastDisconnectAt = new HashMap<>();
    /** 幂等去重：clientMsgId → seq（P2-05），锁内访问，有界 */
    private final Map<String, Integer> seenClientMsgIds = new ConcurrentHashMap<>();
    private final AtomicInteger seenCount = new AtomicInteger();

    SessionState(Long sessionId, ConsultationStatus status, int maxSeq) {
        this.sessionId = sessionId;
        this.status = status;
        this.seq.set(maxSeq);
    }

    public Long getSessionId() {
        return sessionId;
    }

    public ConsultationStatus getStatus() {
        return status;
    }

    public void setStatus(ConsultationStatus status) {
        this.status = status;
    }

    /** 分配下一条 seq（线程安全；调用方持会话锁保证与状态校验一致） */
    public int nextSeq() {
        return seq.incrementAndGet();
    }

    /** 当前已分配最大 seq（join 响应/增量补拉起点） */
    public int currentSeq() {
        return seq.get();
    }

    /** 记录某角色离线时刻（channelInactive，锁内调用） */
    public void recordDisconnect(String role) {
        lastDisconnectAt.put(role, System.currentTimeMillis());
    }

    /** 清空某角色离线标记（重连接管成功，锁内调用） */
    public void clearDisconnect(String role) {
        lastDisconnectAt.remove(role);
    }

    /** 某角色离线至今的毫秒数；0=该角色无离线记录（在线或从未接入） */
    public long disconnectSinceMillis(String role) {
        Long at = lastDisconnectAt.get(role);
        return at == null ? 0L : System.currentTimeMillis() - at;
    }

    /** 是否存在任一角色离线（用于调度器断线兜底，锁内调用） */
    public boolean hasDisconnected() {
        return !lastDisconnectAt.isEmpty();
    }

    /** 是否存在任一角色离线时长超过 graceMs（调度器断线兜底，锁内调用） */
    public boolean anyDisconnectOver(long graceMs) {
        long now = System.currentTimeMillis();
        for (Long at : lastDisconnectAt.values()) {
            if (at != null && now - at >= graceMs) {
                return true;
            }
        }
        return false;
    }

    /** 幂等去重：返回该 clientMsgId 已分配的 seq；未见过返回 null（P2-05，锁内调用） */
    public Integer seenSeq(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return null;
        }
        return seenClientMsgIds.get(clientMsgId);
    }

    /** 记录 clientMsgId → seq；seen-map 有界（防无界增长，超过上限清空重建，量级远低于真实消息量） */
    public void markSeen(String clientMsgId, int seq) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return;
        }
        seenClientMsgIds.put(clientMsgId, seq);
        if (seenCount.incrementAndGet() >= 5000) {
            seenClientMsgIds.clear();
            seenCount.set(0);
        }
    }
}
