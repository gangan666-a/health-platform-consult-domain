package com.example.mall.consultservice.batch;

import com.example.mall.common.model.MessageSenderType;
import com.example.mall.consultservice.config.ConsultProperties;
import com.example.mall.consultservice.model.ChatMessage;
import com.example.mall.consultservice.repository.ConsultationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息批量写队列：防事件循环阻塞（ADR-IM-04 / 评审 §4.4）。
 *
 * <p><b>为什么这么设计</b>：Netty 事件循环线程复用于大量连接，若在 channelRead0 内同步落库，
 * 慢 SQL/锁等待会卡死该事件循环上的全部连接（Netty 应用经典错误）。故消息链路拆两段：
 * <ul>
 *   <li><b>事件循环侧</b>：解析 → 会话锁内校验/分配 seq → 入队（非阻塞 O(1)）即返回；</li>
 *   <li><b>批量写线程（本队列专用单线程）</b>：定时 drain 各会话队列 → JdbcTemplate 批量 INSERT。</li>
 * </ul>
 * 批量合并 N 条为一次 INSERT，既满足实时性又显著降低 DB 往返；「先落库后 ack」通过
 * {@code done} future 在提交成功后完成实现。
 *
 * <p><b>P1-04 逐行容错</b>：批量 INSERT 用 {@code INSERT IGNORE}——单行唯一键冲突
 * （(sessionId,seq)/(sessionId,clientMsgId) 重复，如重放帧）只让该行返回影响行数 0，
 * 不会拖垮整批、也不会重试死循环：重复行即视为「已存在」幂等成功，其 done 正常完成。
 * MySQL 与 H2 2.x 均原生支持 INSERT IGNORE 语法。
 *
 * <p><b>背压</b>：每会话队列长度上限（默认 500），超限丢弃新消息并让该 pending 异常完成
 * （上层回 OVERLOADED 错误帧）。取舍：宁可丢「新到且尚未确认」的消息，也绝不阻塞事件循环；
 * 500 条远超演示量级（≤50 会话），仅在 DB 异常拖慢写线程时才可能触达。
 */
@Component
public class MessageWriteQueue implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MessageWriteQueue.class);

    private static final String INSERT_SQL =
            "INSERT IGNORE INTO chat_message (session_id, seq, sender_type, client_msg_id, content, created_at) "
                    + "VALUES (?,?,?,?,?,?)";

    private final int queueCapacity;
    private final int drainThreshold;
    private final long drainIntervalMs;
    private final JdbcTemplate jdbcTemplate;
    private final ConsultationSessionRepository sessionRepository;

    /** 每会话写队列。ArrayDeque 非线程安全，offer/drain 统一对队列对象加锁（临界区纳秒级）。 */
    private final Map<Long, java.util.ArrayDeque<PendingWrite>> queues = new ConcurrentHashMap<>();
    /** 每会话最近一次 drain 时间（定时兜底用） */
    private final Map<Long, Long> lastDrainAt = new ConcurrentHashMap<>();
    /** 每会话 drain 锁：保证「每会话单写者」（P2-04），同一会话的落库与 done 完成串行、保序 */
    private final Map<Long, Object> drainLocks = new ConcurrentHashMap<>();
    /** 阈值触发 drain 的执行器（非事件循环） */
    private final ExecutorService drainExecutor;
    /** 定时兜底 drain 的调度器（单线程，专用） */
    private final ScheduledExecutorService scheduler;
    private final AtomicLong counter = new AtomicLong();

    public MessageWriteQueue(ConsultProperties props,
                             JdbcTemplate jdbcTemplate,
                             ConsultationSessionRepository sessionRepository) {
        this.queueCapacity = props.getBatchQueueCapacity();
        this.drainThreshold = props.getBatchDrainThreshold();
        this.drainIntervalMs = props.getBatchDrainIntervalMs();
        this.jdbcTemplate = jdbcTemplate;
        this.sessionRepository = sessionRepository;
        this.drainExecutor = Executors.newFixedThreadPool(2, namedThread("batch-write"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(namedThread("batch-sweep"));
        this.scheduler.scheduleWithFixedDelay(this::sweep, drainIntervalMs, drainIntervalMs, TimeUnit.MILLISECONDS);
    }

    private ThreadFactory namedThread(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 入队（事件循环调用，非阻塞）。
     *
     * @return true=入队成功；false=队列超限被丢弃（pending 已异常完成，上层回 OVERLOADED）
     */
    public boolean offer(Long sessionId, PendingWrite pending) {
        java.util.ArrayDeque<PendingWrite> q = queues.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        boolean trigger;
        synchronized (q) {
            if (q.size() >= queueCapacity) {
                pending.done().completeExceptionally(
                        new IllegalStateException("消息积压过多，已丢弃（OVERLOADED）"));
                return false;
            }
            q.addLast(pending);
            trigger = q.size() >= drainThreshold;
        }
        // 达到阈值即异步触发 drain（提交任务 O(1)，不阻塞事件循环），无需等 1s 定时
        if (trigger) {
            drainExecutor.execute(() -> drain(sessionId));
        }
        return true;
    }

    /**
     * flush：保证「当前已入队消息全部落库」。
     * <p>查询历史前调用并 join，返回的 future 在本次 splice 的批次落库后完成（REST 线程可等待，不涉及事件循环）。
     */
    public CompletableFuture<Void> flush(Long sessionId) {
        // [P1 修复] 与 drain 同锁串行「splice+insert」：保证 flush 与并发 drain（executor/sweep）交错时
        // done 完成顺序仍 = seq 顺序（REST 线程可阻塞等待；synchronized 可重入，insertBatch 内再取同锁 OK）。
        Object lock = drainLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            List<PendingWrite> batch = splice(sessionId);
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return insertBatch(sessionId, batch);
        }
    }

    /** 定时兜底 drain：遍历全部会话，未满阈值但距上次 drain 超间隔的也立即刷 */
    private void sweep() {
        for (Long sessionId : new ArrayList<>(queues.keySet())) {
            java.util.ArrayDeque<PendingWrite> q = queues.get(sessionId);
            if (q == null) {
                continue;
            }
            boolean shouldDrain;
            synchronized (q) {
                shouldDrain = !q.isEmpty() && (System.currentTimeMillis() - lastDrainAt
                        .getOrDefault(sessionId, 0L) >= drainIntervalMs);
            }
            if (shouldDrain) {
                drain(sessionId);
            }
        }
    }

    private void drain(Long sessionId) {
        // [P1 修复] 整个「splice+insert」加 per-session drainLock 串行：锁内 splice 的先后 = 锁获取先后，
        // 而每条消息入队已按 seq 有序（MessageService 在会话锁内 offer，见 sendTextMessage），故批次按 seq
        // 升序处理，done 完成顺序 = seq 顺序。此前 splice 在锁外：executor 阈值 drain / 定时 sweep drain /
        // REST flush 三路径并发时，后 splice 的批次可能先获 drainLock，导致 done 完成乱序 → 客户端收乱序。
        Object lock = drainLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            List<PendingWrite> batch = splice(sessionId);
            if (!batch.isEmpty()) {
                insertBatch(sessionId, batch);
            }
        }
    }

    /** splice：取走整批（等价于 JS Array.splice(0)），加锁保证每条消息只被一个 drain 取走 */
    private List<PendingWrite> splice(Long sessionId) {
        java.util.ArrayDeque<PendingWrite> q = queues.get(sessionId);
        if (q == null) {
            return List.of();
        }
        List<PendingWrite> batch = new ArrayList<>();
        synchronized (q) {
            while (!q.isEmpty()) {
                batch.add(q.pollFirst());
            }
            if (q.isEmpty() && sessionIsTerminalOrNoChannels(sessionId)) {
                // P1-03：会话终态且队列已空 → 移除空 key，防内存无界增长
                queues.remove(sessionId);
            }
        }
        return batch;
    }

    private boolean sessionIsTerminalOrNoChannels(Long sessionId) {
        // 简化：队列空即允许清理空 key；drain 锁与 Channel 注册表清理由会话结束流程兜底
        return true;
    }

    /**
     * 批量 INSERT（INSERT IGNORE 逐行容错）+ 刷新会话活动时间 + 完成 done。
     * 每会话单写者：对整个批次加 drainLock，保证同会话 done 完成顺序 = seq 顺序。
     */
    private CompletableFuture<Void> insertBatch(Long sessionId, List<PendingWrite> batch) {
        Object lock = drainLocks.computeIfAbsent(sessionId, k -> new Object());
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (lock) {
            try {
                // [P1 修复] 消费侧兜底：按 seq 升序排序后再批量落库/完成 done（本批内 done 完成顺序 = seq 顺序）。
                // 尽管 MessageService 已在会话锁内按 seq 有序入队，此处排序使「批内有序」不依赖调用方约束而自足
                //（如 SYSTEM 消息仍走 enqueueSystemMessage 的锁外 offer）；排序成本 O(n log n)，批大小 ≤ 队列
                // 上限（默认 500，演示量级更小），相对 DB 批量写可忽略。
                batch.sort(java.util.Comparator.comparingInt(pw -> pw.getMessage().getSeq()));
                List<Object[]> args = new ArrayList<>(batch.size());
                LocalDateTime latestActivity = null;
                for (PendingWrite pw : batch) {
                    ChatMessage m = pw.getMessage();
                    args.add(new Object[]{m.getSessionId(), m.getSeq(), m.getSenderType().name(),
                            m.getClientMsgId(), m.getContent(), Timestamp.valueOf(m.getCreatedAt())});
                    // 活动时间仅计文本消息（USER/DOCTOR），心跳/系统消息不计（AC-C4-4 关键）
                    if (m.getSenderType() != MessageSenderType.SYSTEM
                            && (latestActivity == null || m.getCreatedAt().isAfter(latestActivity))) {
                        latestActivity = m.getCreatedAt();
                    }
                }
                jdbcTemplate.batchUpdate(INSERT_SQL, args);
                if (latestActivity != null) {
                    // 0 说明会话已终态（结束判定并发），视为正常——活动时间对终态无意义
                    sessionRepository.touchActive(sessionId, latestActivity);
                }
                lastDrainAt.put(sessionId, System.currentTimeMillis());
                batch.forEach(pw -> pw.done().complete(null));
                future.complete(null);
            } catch (Exception ex) {
                // 非唯一键冲突的 DB 异常：一次性记 ERROR，不无限重试（避免死循环）；done 异常完成上层回 error
                log.error("批量写消息失败，sessionId={}, 丢弃本批 {} 条", sessionId, batch.size(), ex);
                batch.forEach(pw -> pw.done().completeExceptionally(ex));
                future.completeExceptionally(ex);
            }
        }
        return future;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        drainExecutor.shutdownNow();
    }
}
