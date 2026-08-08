package com.example.mall.consultservice;

import com.example.mall.common.model.ConsultSessionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Netty WebSocket 端到端集成测试（@SpringBootTest + H2 内存库 + 真实 Netty WS 服务端）。
 * <p>
 * 端口：@DynamicPropertySource 动态分配高位空闲端口（生产 8086 不污染），REST 走 MockMvc（MOCK 环境），
 * WS 用 JDK {@link HttpClient} WebSocket 客户端直连 {@code ws://localhost:{port}/ws}。
 * 测试资源置短时序参数：断线缓冲 3s << 空闲 8s，保证「断线超时」与「空闲超时」判据确定性分离；
 * 批量写队列 drain-threshold=2 / interval=200ms 保证「先落库后 ack」实时可见。
 * <p>
 * 覆盖场景：
 * a) 双端聊天（user↔doctor 双向收发 + ack）；b) 会话隔离；c) P0-01 接管（关旧绑新）；
 * d) 结束判定（医生主动 end + 断线缓冲超时）；e) 并发消息 seq 有序（会话锁 + 每会话单写者）；
 * f) 并发首接单（双线程 REST accept，仅一个成功）；g) P2-05 幂等去重；h) 处方 WS 开具→REST 消费→恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Netty WS 端到端集成测试（H2 + 真实 Netty）")
class NettyConsultationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** 静态确定的高位空闲端口：@DynamicPropertySource 注入 consult.ws-port，与 WS 连接共用 */
    private static final int WS_PORT;
    static {
        try (ServerSocket s = new ServerSocket(0)) {
            WS_PORT = s.getLocalPort();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void wsPort(DynamicPropertyRegistry registry) {
        registry.add("consult.ws-port", () -> WS_PORT);
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * 产品 id 生成器：每个测试用唯一产品 id，避免 createOrReuse「同药品未结束会话复用」跨测试复用
     *（同一 H2 库 create-drop 仅建一次，跨测试持久），导致后续测试复用前一测试已 ONGOING 的会话。
     */
    private static final java.util.concurrent.atomic.AtomicInteger PRODUCT_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(100);

    private final List<WsClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WsClient c : clients) {
            c.abort();
        }
        clients.clear();
    }

    // ==================== REST 帮助 ====================

    private ConsultSessionDTO createSession(long productId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/consult/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"productName\":\"阿莫西林胶囊\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readValue(r.getResponse().getContentAsString(StandardCharsets.UTF_8), ConsultSessionDTO.class);
    }

    private ConsultSessionDTO accept(Long sessionId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/consult/sessions/" + sessionId + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorId\":\"d1\",\"doctorName\":\"医生A\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readValue(r.getResponse().getContentAsString(StandardCharsets.UTF_8), ConsultSessionDTO.class);
    }

    private WsClient join(ConsultSessionDTO dto, String token) throws Exception {
        WsClient c = WsClient.connect("ws://localhost:" + WS_PORT + "/ws");
        clients.add(c);
        c.send(joinFrame(dto.getSessionId(), token));
        Map<String, Object> joined = c.await("joined", 5000);
        assertNotNull(joined, "join 应返回 joined 帧");
        assertEquals(dto.getSessionId().longValue(), ((Number) joined.get("sessionId")).longValue());
        return c;
    }

    private WsClient joinUser(ConsultSessionDTO session) throws Exception {
        return join(session, session.getToken());
    }

    private WsClient joinDoctor(ConsultSessionDTO accepted) throws Exception {
        return join(accepted, accepted.getToken());
    }

    // ==================== 帧构建 ====================

    private static String joinFrame(Long id, String token) {
        return "{\"type\":\"join\",\"sessionId\":" + id + ",\"token\":\"" + token + "\"}";
    }

    private static String msgFrame(Long id, String clientMsgId, String content) {
        return "{\"type\":\"message\",\"sessionId\":" + id + ",\"clientMsgId\":\"" + clientMsgId
                + "\",\"content\":\"" + content + "\"}";
    }

    private static String endFrame(Long id) {
        return "{\"type\":\"end\",\"sessionId\":" + id + "}";
    }

    private static String prescriptionFrame(Long id, Long productId) {
        return "{\"type\":\"prescription\",\"sessionId\":" + id
                + ",\"items\":[{\"productId\":" + productId + ",\"productName\":\"阿莫西林胶囊\","
                + "\"dosage\":\"0.5g\",\"frequency\":\"tid\",\"quantity\":2}],\"note\":\"多喝水\"}";
    }

    // ==================== a) 双端聊天 ====================

    @Test
    @DisplayName("a) 双端聊天：user/doctor 双向收发消息 + ack")
    void doubleSideChat() throws Exception {
        ConsultSessionDTO session = createSession(11L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        user.send(msgFrame(session.getSessionId(), "u1", "医生您好"));
        Map<String, Object> uMsg = user.await("message", 5000);
        assertNotNull(uMsg);
        assertEquals("USER", uMsg.get("senderType"));
        assertEquals("医生您好", uMsg.get("content"));
        int seq1 = ((Number) uMsg.get("seq")).intValue();
        Map<String, Object> uAck = user.await("ack", 5000);
        assertEquals(seq1, ((Number) uAck.get("seq")).intValue());
        assertEquals("u1", uAck.get("clientMsgId"));
        Map<String, Object> dMsg = doctor.await("message", 5000);
        assertEquals(seq1, ((Number) dMsg.get("seq")).intValue());
        assertEquals("医生您好", dMsg.get("content"));

        doctor.send(msgFrame(session.getSessionId(), "d1", "请描述症状"));
        Map<String, Object> dMsg2 = doctor.await("message", 5000);
        assertEquals("DOCTOR", dMsg2.get("senderType"));
        assertEquals("请描述症状", dMsg2.get("content"));
        int seq2 = ((Number) dMsg2.get("seq")).intValue();
        assertTrue(seq2 > seq1, "会话内 seq 单调递增");
        Map<String, Object> uMsg2 = user.await("message", 5000);
        assertEquals(seq2, ((Number) uMsg2.get("seq")).intValue());
        assertEquals("请描述症状", uMsg2.get("content"));
    }

    // ==================== b) 会话隔离 ====================

    @Test
    @DisplayName("b) 会话隔离：A 会话消息不会推送到 B 会话")
    void sessionIsolation() throws Exception {
        ConsultSessionDTO s1 = createSession(21L);
        ConsultSessionDTO s2 = createSession(22L);
        ConsultSessionDTO a1 = accept(s1.getSessionId());
        ConsultSessionDTO a2 = accept(s2.getSessionId());
        WsClient user1 = joinUser(s1);
        WsClient user2 = joinUser(s2);
        joinDoctor(a1);
        joinDoctor(a2);
        // 排空 user2 基线：joined（join 已消费）+ s2 accept 触发的 system「医生已接入」异步广播
        //（批量写 drain-interval=200ms，广播在落库完成后到达，可能晚于 join，故多等 >drain 周期）
        user2.pumpAll(2000);

        user1.send(msgFrame(s1.getSessionId(), "u1", "只发给会话A"));
        assertNotNull(user1.await("ack", 5000));
        // 会话 B 不应收到该消息（1s 窗口内无帧）
        assertNull(user2.nextFrame(1000), "跨会话消息泄漏到会话 B");
    }

    // ==================== c) P0-01 接管 ====================

    @Test
    @DisplayName("c) P0-01 接管：同会话同角色重复 join → 关旧绑新，新连接继续可用")
    void takeoverClosesOld() throws Exception {
        ConsultSessionDTO session = createSession(31L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient oldCh = joinUser(session);
        // 二次 join（同用户 token）→ 接管
        WsClient freshCh = joinUser(session);
        clients.add(freshCh);

        // 旧连接被服务端关闭
        assertTrue(oldCh.awaitClose(5000), "旧连接应被服务端关闭");
        // 新连接发消息仍可用
        freshCh.send(msgFrame(session.getSessionId(), "f1", "接管后仍可发消息"));
        Map<String, Object> ack = freshCh.await("ack", 5000);
        assertNotNull(ack);
    }

    // ==================== d) 结束判定 ====================

    @Test
    @DisplayName("d1) 医生主动结束：双端收到 session_ended(ENDED/DOCTOR_ENDED)，REST 状态一致")
    void doctorEnd_broadcastEnded() throws Exception {
        ConsultSessionDTO session = createSession(41L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        doctor.send(endFrame(session.getSessionId()));

        Map<String, Object> uEnd = user.await("session_ended", 8000);
        assertNotNull(uEnd);
        assertEquals("ENDED", uEnd.get("status"));
        assertEquals("DOCTOR_ENDED", uEnd.get("endReason"));
        Map<String, Object> dEnd = doctor.await("session_ended", 8000);
        assertEquals("ENDED", dEnd.get("status"));

        mockMvc.perform(get("/api/consult/sessions/" + session.getSessionId()))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("DOCTOR_ENDED"));
    }

    @Test
    @DisplayName("d2) 断线缓冲：用户断线超 grace(3s) → 医生收到 session_ended(DISCONNECT_TIMEOUT)")
    void disconnectBuffer_endsByScheduler() throws Exception {
        ConsultSessionDTO session = createSession(51L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        user.abort(); // 模拟用户断线（channelInactive → 记录离线时刻）

        // 调度器 1s 扫表，缓冲 3s 后结束 → 医生收到 session_ended(DISCONNECT_TIMEOUT)
        Map<String, Object> end = doctor.await("session_ended", 10_000);
        assertNotNull(end, "断线超时应广播 session_ended");
        assertEquals("ENDED", end.get("status"));
        assertEquals("DISCONNECT_TIMEOUT", end.get("endReason"));
        // REST 状态与原因一致
        mockMvc.perform(get("/api/consult/sessions/" + session.getSessionId()))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("DISCONNECT_TIMEOUT"));
    }

    // ==================== e) 并发消息 seq 有序 ====================

    @Test
    @DisplayName("e) 并发消息：双端 30 条并发，全部到达、seq 无重复无空洞；服务端推送顺序与 seq 严格递增一致")
    void concurrentMessageOrdering() throws Exception {
        ConsultSessionDTO session = createSession(61L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        Long id = session.getSessionId();
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 15; i++) {
                final int n = i;
                sends.add(CompletableFuture.runAsync(() -> user.send(msgFrame(id, "u" + n, "用户-" + n)), pool));
                sends.add(CompletableFuture.runAsync(() -> doctor.send(msgFrame(id, "d" + n, "医生-" + n)), pool));
            }
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        // 收集 user 侧全部 message 广播帧（30 条），恢复严格断言（P1 修复后）：
        // ①全部到达；②seq 无重复、连续无空洞（无丢失无 P2-05 误判）；③服务端推送顺序与 seq 严格递增一致
        //（PRD「并发消息不乱序」）。修复后保证链：seq 在会话锁内分配且 offer 同锁（入队顺序=seq 分配顺序，
        // 见 MessageService.sendTextMessage）→ 每会话 drain 加锁串行 splice+insert（跨批次 done 按 seq 完成，
        // 见 MessageWriteQueue.drain/flush）→ 批内再按 seq 排序兜底（见 insertBatch）→ 广播顺序=seq 顺序。
        Set<Integer> seen = new HashSet<>();
        int lastSeq = -1;
        int received = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        while (received < 30 && System.currentTimeMillis() < deadline) {
            Map<String, Object> f = user.nextFrame(3000);
            if (f == null) {
                break;
            }
            if ("message".equals(f.get("type"))) {
                int seq = ((Number) f.get("seq")).intValue();
                assertTrue(seen.add(seq), "seq 重复出现: " + seq);
                // 严格全局递增：任意两条广播（不分 USER/DOCTOR）到达序必须与 seq 一致
                assertTrue(seq > lastSeq,
                        "并发消息推送乱序：已收最后 seq=" + lastSeq + "，当前 seq=" + seq);
                lastSeq = seq;
                received++;
            }
        }
        assertEquals(30, received, "应收到全部 30 条消息");
        // seq 连续无空洞（accept 的 system 消息占 seq=1，故首条消息 seq=2，区间宽度须恰好 30）
        int min = Integer.MAX_VALUE;
        int max = -1;
        for (int s : seen) {
            min = Math.min(min, s);
            max = Math.max(max, s);
        }
        assertEquals(30, max - min + 1, "seq 应连续无空洞");
    }

    // ==================== f) 并发首接单 ====================

    @Test
    @DisplayName("f) 并发首接单：双线程 accept 仅一个成功（CAS 裁决），另一个 400")
    void concurrentFirstAccept() throws Exception {
        ConsultSessionDTO session = createSession(71L);
        Long id = session.getSessionId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> f1 = pool.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/consult/sessions/" + id + "/accept")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"doctorId\":\"d1\",\"doctorName\":\"医生A\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> f2 = pool.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/consult/sessions/" + id + "/accept")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"doctorId\":\"d2\",\"doctorName\":\"医生B\"}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int s1 = f1.get(10, TimeUnit.SECONDS);
            int s2 = f2.get(10, TimeUnit.SECONDS);
            // 恰好一个成功、一个失败
            assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "并发接单只能成功一次");
            assertEquals(1, (s1 == 400 ? 1 : 0) + (s2 == 400 ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        // 最终 DB 权威状态 ONGOING
        mockMvc.perform(get("/api/consult/sessions/" + id))
                .andExpect(jsonPath("$.status").value("ONGOING"));
    }

    // ==================== g) P2-05 幂等去重 ====================

    @Test
    @DisplayName("g) 幂等去重：重放同 clientMsgId → 同 seq ack、不重复广播")
    void clientMsgIdDedup() throws Exception {
        ConsultSessionDTO session = createSession(81L);
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        user.send(msgFrame(session.getSessionId(), "retry1", "你好"));
        Map<String, Object> ack1 = user.await("ack", 5000);
        int seq1 = ((Number) ack1.get("seq")).intValue();
        // 消费医生侧第一条广播
        assertNotNull(doctor.await("message", 5000));

        // 重放同 clientMsgId（网络重发）→ 幂等成功：同 seq ack、不再广播
        user.send(msgFrame(session.getSessionId(), "retry1", "你好(重发)"));
        Map<String, Object> ack2 = user.await("ack", 5000);
        assertEquals(seq1, ((Number) ack2.get("seq")).intValue(), "重发应返回同一 seq");
        assertNull(doctor.nextFrame(1200), "重发不应二次广播");
    }

    // ==================== h) 处方全链路 ====================

    @Test
    @DisplayName("h) 处方：医生 WS 开具 → 双端收 prescription_issued → REST 消费 COMPLETED → 恢复 ACTIVE")
    void prescriptionFullFlow() throws Exception {
        ConsultSessionDTO session = createSession(91L);
        long productId = session.getProductId();
        ConsultSessionDTO accepted = accept(session.getSessionId());
        WsClient user = joinUser(session);
        WsClient doctor = joinDoctor(accepted);

        // 医生 WS 开方（事件循环外执行器异步处理）
        doctor.send(prescriptionFrame(session.getSessionId(), productId));
        Map<String, Object> issued = user.await("prescription_issued", 10_000);
        assertNotNull(issued, "应广播 prescription_issued");
        String no = String.valueOf(issued.get("prescriptionNo"));
        assertTrue(no.startsWith("RX"));
        // 双端收到 session_ended(PRESCRIPTION_ISSUED)
        Map<String, Object> ended = user.await("session_ended", 10_000);
        assertEquals("PRESCRIPTION_ISSUED", ended.get("status"));

        // REST 查询处方 ACTIVE → 消费（P1-02 带 orderNo）→ 会话 COMPLETED
        mockMvc.perform(get("/api/consult/prescriptions/" + no))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/consult/prescriptions/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prescriptionNo\":\"" + no + "\",\"orderNo\":\"ORD-1\","
                                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}"))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.consumedOrderNo").value("ORD-1"));
        mockMvc.perform(get("/api/consult/sessions/" + session.getSessionId()))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("COMPLETED"));
        // 恢复（P1-01）→ ACTIVE，清空消费信息
        mockMvc.perform(post("/api/consult/prescriptions/" + no + "/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-1\"}"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.consumedOrderNo").isEmpty());
    }

    // ==================== JDK HttpClient WebSocket 客户端 ====================

    static final class WsClient implements WebSocket.Listener {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private WebSocket ws;

        static WsClient connect(String uri) throws Exception {
            WsClient c = new WsClient();
            HttpClient client = HttpClient.newHttpClient();
            c.ws = client.newWebSocketBuilder().buildAsync(URI.create(uri), c).join();
            c.opened.get(5, TimeUnit.SECONDS);
            return c;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            opened.complete(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frames.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        synchronized void send(String json) {
            // 同一 WebSocket 的 sendText 串行化（JDK 客户端发送方法非并发安全），跨客户端仍并发
            ws.sendText(json, true).join();
        }

        void abort() {
            ws.abort();
        }

        /** 是否在超时前收到服务端关闭（onClose/onError）；超时返回 false */
        boolean awaitClose(long timeoutMs) {
            try {
                closed.get(timeoutMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        /** 取下一帧原始 JSON；超时返回 null */
        String nextRaw(long timeoutMs) throws InterruptedException {
            return frames.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** 取下一帧并解析为 Map；超时返回 null */
        @SuppressWarnings("unchecked")
        Map<String, Object> nextFrame(long timeoutMs) throws InterruptedException {
            String raw = nextRaw(timeoutMs);
            if (raw == null) {
                return null;
            }
            try {
                return MAPPER.readValue(raw, Map.class);
            } catch (Exception ex) {
                return Map.of("type", "__unparseable__");
            }
        }

        /** 等待指定类型帧（中间帧丢弃）；超时返回 null */
        Map<String, Object> await(String type, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> f = nextFrame(deadline - System.currentTimeMillis());
                if (f == null) {
                    return null;
                }
                if (type.equals(f.get("type"))) {
                    return f;
                }
            }
            return null;
        }

        /** 清空指定窗口内到达的所有帧（用于队列基线）。与 poll 不同，无论是否有帧都等满窗口，
         *  确保异步广播（如 accept 触发的 system 消息经 200ms 批量写周期延迟送达）被完整排空。 */
        void pumpAll(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (frames.poll(50, TimeUnit.MILLISECONDS) == null) {
                    // 窗口内无帧也继续等到截止，防提前退出漏排异步帧
                    Thread.sleep(20);
                }
            }
        }
    }
}
