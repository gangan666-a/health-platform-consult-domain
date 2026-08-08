package com.example.mall.consultservice.netty;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WsSessionRegistry 纯单元测试（EmbeddedChannel 模拟连接）。
 * - token 签发/校验/角色绑定，fail-closed（错会话 token 拒绝）；
 * - P0-01 接管：同会话同角色重复 join → 关旧绑新（旧 channel 关闭、新 channel 生效）；
 * - 每会话 USER+DOCTOR 双槽位；
 * - P1-03 清理：unbind 回收空 key、purge 关闭全部 channel 并吊销令牌。
 */
class WsSessionRegistryTest {

    private WsSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistry();
    }

    private Channel newChannel() {
        return new EmbeddedChannel();
    }

    private void flush(Channel... channels) {
        for (Channel c : channels) {
            if (c instanceof EmbeddedChannel ec) {
                ec.runPendingTasks();
                ec.checkException();
            }
        }
    }

    @Test
    @DisplayName("签发 token → 校验有效、角色正确")
    void issueToken_valid() {
        registry.issueToken(1L, "TOKEN-A", WsSessionRegistry.ROLE_USER);
        assertTrue(registry.isTokenValid("TOKEN-A", 1L));
        assertEquals(WsSessionRegistry.ROLE_USER, registry.roleOfToken("TOKEN-A"));
    }

    @Test
    @DisplayName("fail-closed：token 不属于该会话 → 无效；未知 token → 无效且无角色")
    void token_failClosed() {
        registry.issueToken(1L, "TOKEN-A", WsSessionRegistry.ROLE_USER);
        assertFalse(registry.isTokenValid("TOKEN-A", 999L));
        assertFalse(registry.isTokenValid("GHOST", 1L));
        assertNull(registry.roleOfToken("GHOST"));
    }

    @Test
    @DisplayName("bind：绑定后 isRoleOnline/sessionIdOf/roleOf/channels 一致")
    void bind_maps() {
        Channel ch = newChannel();
        boolean takenOver = registry.bind(1L, WsSessionRegistry.ROLE_USER, ch);
        assertFalse(takenOver);
        assertTrue(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_USER));
        assertTrue(registry.isBound(ch));
        assertEquals(1L, registry.sessionIdOf(ch));
        assertEquals(WsSessionRegistry.ROLE_USER, registry.roleOf(ch));
        assertEquals(1, registry.channels(1L).size());
    }

    @Test
    @DisplayName("P0-01 接管：同会话同角色二次 join → 返回接管标志、旧连接被关闭、新连接生效")
    void bind_takeOverClosesOld() {
        Channel old = newChannel();
        Channel fresh = newChannel();
        assertFalse(registry.bind(1L, WsSessionRegistry.ROLE_USER, old));

        boolean takenOver = registry.bind(1L, WsSessionRegistry.ROLE_USER, fresh);
        assertTrue(takenOver);

        flush(old, fresh);
        assertFalse(old.isActive()); // 旧连接已关闭（P0-01 关旧绑新）
        assertTrue(fresh.isActive());
        // 模拟 WsFrameHandler.channelInactive：旧连接关闭后反查清理
        registry.unbind(old);
        assertEquals(fresh, registry.channels(1L).stream().findFirst().orElseThrow());
        assertTrue(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_USER));
        // 旧 channel 已 unbind，反查为空
        assertFalse(registry.isBound(old));
    }

    @Test
    @DisplayName("每会话 USER+DOCTOR 双槽位：两角色可共存，同角色再 join 触发接管")
    void twoRoleSlots() {
        Channel user = newChannel();
        Channel doctor = newChannel();
        registry.bind(1L, WsSessionRegistry.ROLE_USER, user);
        registry.bind(1L, WsSessionRegistry.ROLE_DOCTOR, doctor);
        assertTrue(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_USER));
        assertTrue(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_DOCTOR));
        assertEquals(2, registry.channels(1L).size());

        Channel user2 = newChannel();
        registry.bind(1L, WsSessionRegistry.ROLE_USER, user2);
        flush(user, doctor, user2);
        assertFalse(user.isActive());
        registry.unbind(user); // 模拟旧连接 channelInactive 清理
        assertEquals(2, registry.channels(1L).size()); // USER 槽被 user2 接管，仍为 2 个
    }

    @Test
    @DisplayName("unbind：移除反向映射、释放槽位并回收空 key")
    void unbind_cleans() {
        Channel ch = newChannel();
        registry.bind(1L, WsSessionRegistry.ROLE_USER, ch);
        registry.unbind(ch);
        assertFalse(registry.isBound(ch));
        assertFalse(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_USER));
        assertTrue(registry.channels(1L).isEmpty());
        assertNull(registry.sessionIdOf(ch));
    }

    @Test
    @DisplayName("unbind 幂等：未绑定的 channel 无害返回")
    void unbind_idempotent() {
        registry.unbind(newChannel());
        registry.unbind(newChannel());
    }

    @Test
    @DisplayName("P1-03 purge：关闭全部 channel、回收槽位并吊销该会话全部令牌")
    void purge_cleansChannelsAndTokens() {
        Channel user = newChannel();
        Channel doctor = newChannel();
        registry.issueToken(1L, "T-USER", WsSessionRegistry.ROLE_USER);
        registry.issueToken(1L, "T-DOC", WsSessionRegistry.ROLE_DOCTOR);
        registry.bind(1L, WsSessionRegistry.ROLE_USER, user);
        registry.bind(1L, WsSessionRegistry.ROLE_DOCTOR, doctor);

        registry.purge(1L);

        flush(user, doctor);
        assertFalse(user.isActive());
        assertFalse(doctor.isActive());
        assertFalse(registry.isRoleOnline(1L, WsSessionRegistry.ROLE_USER));
        assertTrue(registry.channels(1L).isEmpty());
        assertFalse(registry.isTokenValid("T-USER", 1L)); // 令牌一并吊销（P1-03 防堆积）
        assertFalse(registry.isTokenValid("T-DOC", 1L));
        // 不影响其他会话
        registry.issueToken(2L, "T-OTHER", WsSessionRegistry.ROLE_USER);
        assertTrue(registry.isTokenValid("T-OTHER", 2L));
    }

    @Test
    @DisplayName("revokeToken：吊销单个令牌并移除反向索引")
    void revokeToken() {
        registry.issueToken(1L, "T-A", WsSessionRegistry.ROLE_USER);
        registry.issueToken(1L, "T-B", WsSessionRegistry.ROLE_DOCTOR);
        registry.revokeToken("T-A");
        assertFalse(registry.isTokenValid("T-A", 1L));
        assertTrue(registry.isTokenValid("T-B", 1L));
    }
}
