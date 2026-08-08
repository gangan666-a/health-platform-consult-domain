package com.example.mall.consultservice.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 在线连接注册表：只存「连接 + 令牌」等无需持久化的内存态，业务状态以 DB 为权威（ADR-IM-02）。
 * <p>
 * 结构（评审 §3.2 + P1-03 清理路径）：
 * <ul>
 *   <li>{@code sessionChannels}：sessionId → 在线 channel 集（CopyOnWriteArraySet，广播读多写少、无锁遍历）；</li>
 *   <li>{@code channelSession}：channel → sessionId（channelInactive O(1) 反查清理）；</li>
 *   <li>{@code tokens}：token → (sessionId, role) 绑定（join 帧校验身份与角色）；{@code sessionTokens} 反向索引供终态回收；</li>
 *   <li>{@code sessionSlots}：sessionId → {role → channel}，每会话限 USER+DOCTOR 两个槽位。</li>
 * </ul>
 *
 * <p><b>P0-01 接管语义（关旧绑新）</b>：同身份（同 session 同 role）重复 join 时关闭旧连接、绑定新连接，
 * 而不是拒绝——保证静默断线（断网/休眠无 FIN）缓冲期内重连成功（EX-IM-9★/AC-C4-2★）。
 * 接管在会话锁内原子完成（P2-01），杜绝「旧槽未释放致新连接被拒」或「两连接同槽」半状态。
 *
 * <p><b>P1-03 显式清理</b>：{@link #purge} 在会话进入终态时移除该会话全部注册表条目（channel 集/槽位/令牌），
 * 防止空 key 无界增长；{@link #unbind} 在 channel 关闭时移除反向映射并回收空 key。
 */
@Component
public class WsSessionRegistry {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_DOCTOR = "DOCTOR";

    /** channel 上的会话 id 缓存（事件循环内 O(1) 判断，免查 Map） */
    public static final AttributeKey<Long> ATTR_SESSION_ID = AttributeKey.valueOf("sessionId");
    /** channel 上的角色缓存 */
    public static final AttributeKey<String> ATTR_ROLE = AttributeKey.valueOf("role");

    /** token → (sessionId, role) */
    public record TokenBinding(Long sessionId, String role) {
    }

    private final ConcurrentMap<Long, CopyOnWriteArraySet<Channel>> sessionChannels = new ConcurrentHashMap<>();
    private final ConcurrentMap<Channel, Long> channelSession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TokenBinding> tokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CopyOnWriteArraySet<String>> sessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ConcurrentMap<String, Channel>> sessionSlots = new ConcurrentHashMap<>();
    private final ConcurrentMap<Channel, String> channelRole = new ConcurrentHashMap<>();

    /** 签发令牌（创建会话 → USER 角色；医生接单 → DOCTOR 角色，Spring 线程调用） */
    public void issueToken(Long sessionId, String token, String role) {
        tokens.put(token, new TokenBinding(sessionId, role));
        sessionTokens.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(token);
    }

    /** 吊销单个令牌（重新签发用户令牌前吊销旧令牌，防令牌堆积） */
    public void revokeToken(String token) {
        TokenBinding binding = tokens.remove(token);
        if (binding != null) {
            Set<String> set = sessionTokens.get(binding.sessionId());
            if (set != null) {
                set.remove(token);
            }
        }
    }

    /** join 帧校验：token 归属该 sessionId 才合法（fail-closed） */
    public boolean isTokenValid(String token, Long sessionId) {
        TokenBinding binding = tokens.get(token);
        return binding != null && sessionId.equals(binding.sessionId());
    }

    /** 令牌绑定角色（join 时校验身份 slot：USER 令牌不能以 DOCTOR 身份 join） */
    public String roleOfToken(String token) {
        TokenBinding binding = tokens.get(token);
        return binding == null ? null : binding.role();
    }

    /**
     * 绑定 channel 到会话槽位（接管语义，调用方须持会话锁，P2-01 原子性）。
     * 返回是否发生接管（关闭了旧连接）。
     */
    public boolean bind(Long sessionId, String role, Channel channel) {
        ConcurrentMap<String, Channel> slots = sessionSlots.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        Channel old = slots.put(role, channel);
        if (old != null && old != channel) {
            takeOver(old); // P0-01：静默断线缓冲期内重连 → 关旧绑新
        }
        channel.attr(ATTR_SESSION_ID).set(sessionId);
        channel.attr(ATTR_ROLE).set(role);
        channelSession.put(channel, sessionId);
        channelRole.put(channel, role);
        sessionChannels.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(channel);
        return old != null && old != channel;
    }

    private void takeOver(Channel old) {
        old.close(); // 触发 old 的 channelInactive → unbind(old)（幂等）
    }

    /** channel 关闭时移除反向映射并回收空 key（幂等，可被接管重复触发） */
    public void unbind(Channel channel) {
        Long sessionId = channelSession.remove(channel);
        if (sessionId == null) {
            return;
        }
        String role = channelRole.remove(channel);
        channel.attr(ATTR_SESSION_ID).set(null);
        channel.attr(ATTR_ROLE).set(null);
        CopyOnWriteArraySet<Channel> set = sessionChannels.get(sessionId);
        if (set != null) {
            set.remove(channel);
            if (set.isEmpty()) {
                sessionChannels.remove(sessionId); // P1-03：回收空 key
            }
        }
        ConcurrentMap<String, Channel> slots = sessionSlots.get(sessionId);
        if (slots != null) {
            if (role != null && slots.get(role) == channel) {
                slots.remove(role);
            }
            if (slots.isEmpty()) {
                sessionSlots.remove(sessionId); // P1-03：回收空 key
            }
        }
    }

    /** 会话进入终态时显式清理：关闭全部 channel、回收 channel 集/槽位/令牌（P1-03） */
    public void purge(Long sessionId) {
        CopyOnWriteArraySet<Channel> set = sessionChannels.remove(sessionId);
        if (set != null) {
            for (Channel ch : set) {
                channelSession.remove(ch);
                channelRole.remove(ch);
                ch.close();
            }
        }
        sessionSlots.remove(sessionId);
        Set<String> tokenSet = sessionTokens.remove(sessionId);
        if (tokenSet != null) {
            for (String token : tokenSet) {
                tokens.remove(token);
            }
        }
    }

    /** 本会话在线 channel（广播用，读多写少） */
    public Set<Channel> channels(Long sessionId) {
        CopyOnWriteArraySet<Channel> set = sessionChannels.get(sessionId);
        return set == null ? Collections.emptySet() : set;
    }

    /** 该会话该角色当前是否有在线 channel（channelInactive 判「是否已被接管」，P0-01 重连接管） */
    public boolean isRoleOnline(Long sessionId, String role) {
        ConcurrentMap<String, Channel> slots = sessionSlots.get(sessionId);
        return slots != null && slots.get(role) != null;
    }

    public boolean isBound(Channel channel) {
        return channelSession.containsKey(channel);
    }

    public Long sessionIdOf(Channel channel) {
        return channelSession.get(channel);
    }

    public String roleOf(Channel channel) {
        return channelRole.get(channel);
    }
}
