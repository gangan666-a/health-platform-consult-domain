# 《药品品类 + IM 在线问诊（Netty WebSocket）+ 处方购药》方案设计评审报告（M2 Design Review）

| 项目 | 内容 |
| --- | --- |
| 评审对象 | `docs/08-design-IM.md`（M2 研发方案设计 v1.0） |
| 评审基线 | git HEAD `6145721`（当前代码现状）+ `docs/07-PRD-IM.md`（M2 PRD v1.0） |
| 评审人 | 架构评审专家（Architecture Review Board） |
| 评审日期 | 2026-08-07 |
| 关联文档 | `docs/03-design-review.md`（M1 评审格式参考）、`README.md` |
| 评审方式 | 纯文档评审 + 代码抽查交叉验证（不修改任何代码） |
| 结论 | **有条件通过（必须修改 P0-01 / P1-01 ~ P1-04 后放行实现）** |

---

## 一、评审范围与方法

### 1.1 评审文档
- `docs/07-PRD-IM.md`：M2 PRD，含 AC-D1/C1/C2/C3/C4/P1/P2/P3/IM 共 **62 条**验收标准（设计 §0 标题称「52 条」为笔误，详见 P3-01）与 **27 条**异常场景（EX-IM-1~28 缺 21 号，设计 §11.1 已正确识别）。
- `docs/08-design-IM.md`：方案设计，含 ADR-IM-01~09、会话隔离（§3）、并发控制（§4）、会话结束判定（§5）、处方生命周期（§6）、API 契约（§7）、DB 设计（§8）、测试策略（§10）、覆盖矩阵（§11）、风险 R-10~R-18（§12）。

### 1.2 代码抽查清单（验证设计论断与现状一致性）
| 文件 | 验证要点 | 结论 |
| --- | --- | --- |
| `pom.xml`（父聚合） | 模块清单（含 consult-service?） | 当前仅 5 模块（mall-common/product/order/payment/recommend），**无 consult-service**，设计为新增，需在父 pom 增模块并引入 netty 依赖 |
| `init.sql` | schema 建库授权 | 当前建 3 库；设计 §8.6 正确追加 `shopdb_consult` 建库+授权 |
| `product-service/.../model/Product.java` | 是否有 `rxFlag` | **无**，设计新增 `rx_flag`（NOT NULL DEFAULT FALSE）与现状一致 |
| `product-service/.../service/ProductService.java` | 原子扣减/回补、价格校验 | 原子扣减 `deductStock` + 影响行数判定成立；价格 ≤0 校验已实现（M1 P1-03 已修） |
| `order-service/.../service/OrderService.java` | `createOrder` 签名、补偿、事务 | 无 `prescriptionNo`；`@Transactional`；补偿失败已记 ERROR 日志（M1 P1-02 已修）。**新增 Rx 编排需注意补偿分支完整性与事务边界（P1-01）** |
| `order-service/.../client/RestClientConfig.java` | 超时配置 | 已存在 connect 3s/read 5s（推荐 12s）（M1 P1-01 已修）；`ConsultClient` 沿用同一 `RestClient.Builder` 可行 |
| `order-service/.../client/ClientSupport.java` | 错误体转换 | 存在但仍属服务内复制（M1 P2-02 未下沉，M2 设计亦未处理）→ P2-13 |
| `mall-common/.../model/ProductDTO.java` | 是否有 `rxFlag` | **无**，设计新增（mall-common），与现状一致 |
| `order-service/.../model/Order.java`、`form/OrderForm.java` | 是否有 `prescriptionNo` | **均无**，设计新增，与现状一致 |
| `product-service/.../config/DataInitializer.java` | upsert 逻辑 | 仅回填 category；需扩展 rxFlag 回填（列 DEFAULT FALSE + boolean 默认 false 天然满足 AC-D1-5） |
| `recommend-service/.../ai/AiRecommendationService.java`、`rule/RuleRecommendationService.java` | 是否有 rxFlag 过滤 | **均无**，设计 ADR-IM-08 需在两处加「非 Rx」过滤 |
| `order-service/.../web/ShopController.java` | `confirmOrder` Rx 拦截、Rx 详情页入口 | 无 Rx 拦截；设计 §7.4 未明确 Rx 详情页「凭已有处方购买」入口 → P2-10 |
| `**/src/test/**`、`**/pom.xml` | 测试依赖与测试文件 | **14 个测试文件 + spring-boot-starter-test/h2 已就绪**（M1 P2-03 已修）；M2 需新增 consult-service 测试基建与 Netty 集成测试 |

**总体抽查结论**：M2 设计的模块边界、RestClient 风格、Product/Order/OrderForm 模型现状、pom/init.sql 结构与现状**一致**；M1 评审的 P0/P1 四项均已修复（RestClient 超时、补偿留痕、价格校验、测试基建）。设计论断与代码无硬冲突，主要问题集中在「异步批量写 + 内存注册表 + 断线重连」的并发/生命周期细节与「处方消费补偿分支」的完整性。

---

## 二、总体评价

方案整体质量较高：ADR 决策记录完整、术语澄清（PRESCRIPTION_ISSUED=endReason / EXPIRED=处方状态）自洽、会话隔离三层防线 + fail-closed 穷举、并发模型（事件循环零阻塞 + 会话锁 + DB CAS + 批量写队列）思路正确、五种结束判定收敛到单一状态机、处方一次性消费用单条 CAS 为唯一边界、测试策略覆盖 anti-happy-path 且给出真实的 Netty 集成测试路径（JDK HttpClient WebSocket + 随机端口），是**可实施的设计**。

但抽查发现以下必须修正的问题：

1. **P0-01（阻断）**：§3.3「每身份在线连接 1，同身份重复在线 → 拒绝」与 §5.3「断线 60s 缓冲期内重连成功」在**静默断线**（断网/笔记本休眠，无 FIN）场景下直接冲突——服务端依赖心跳 30s×2≈60s 才判死旧连接并释放槽位，客户端在缓冲期内重连（新连接、同身份）会被「该身份已在线」拒绝，**EX-IM-9★ / AC-C4-2★ 在最常见断线场景下必失败**。
2. **P1-01（漏补偿）**：§6.5 编排「消费处方→扣库存→落单」仅在「本地落单失败」补偿，**处方消费成功后扣库存失败的分支未恢复处方**，处方形成「已占但无订单」悬挂，违反 EX-IM-22★（明确含「扣库存/落单失败」）。
3. **P1-02（契约不一致）**：consume 请求体 `{userId, productId, quantity}` 无 `orderNo`，但 §4.3 CAS 写 `consumed_order_no=:orderNo`。
4. **P1-03（注册表泄漏）**：`sessionChannels`/`sessionSlots` 的 map 空 key 无删除路径，会话累积 → 内存无界增长。
5. **P1-04（批量写缺陷）**：批量 INSERT 中单行唯一键冲突（clientMsgId 重放/seq 冲突）会拖垮整批并导致重试死循环。

---

## 三、需求覆盖核对（62 条 AC / 27 条 EX-IM）

### 3.1 验收标准 AC 覆盖
设计 §11.2 矩阵逐组映射到测试位置，抽查核对（PRD §12 实为 **62** 条，非设计标题所称 52 条）：

| 组 | 条数 | 覆盖 | 风险点 |
| --- | --- | --- | --- |
| AC-D1-1~5 | 5 | 全可覆盖 | AC-D1-5 回填靠列 DEFAULT FALSE + boolean 默认，成立 |
| AC-C1-1~4 | 4 | 全可覆盖 | 复用幂等（同用户同药品未结束）有 IDX(user_id, product_id) 支撑 |
| AC-C2-1~7 | 7 | 全可覆盖 | AC-C2-5 断线补齐依赖「先落库后 ack」+ afterSeq，成立 |
| AC-C3-1~6 | 6 | 全可覆盖 | 越权/非法流转有 fail-closed 支撑 |
| AC-C4-1~10 | 10 | **AC-C4-2 静默断线重连存在设计冲突** | 关联 **P0-01** |
| AC-P1-1~6 | 6 | 全可覆盖 | 开方同事务 CAS 会话结束成立 |
| AC-P2-1~12 | 12 | **AC-P2-11 补偿分支不完整（扣库存失败未恢复处方）** | 关联 **P1-01**；AC-P2-2 数量锁定需 confirmOrder 拉处方数量（设计有 `GET /api/prescriptions/{no}`，可实现） |
| AC-P3-1~5 | 5 | 全可覆盖 | 过期懒判定 + 定时扫描双保险成立 |
| AC-IM-1~7 | 7 | AC-IM-3 依赖 60s 缓冲重连（关联 P0-01）；其余成立 | — |

**合计 62 条中：60 条具备实现基础；AC-C4-2 / AC-P2-11 两条存在阻断/缺口（P0-01 / P1-01）。**

### 3.2 异常场景 EX-IM 覆盖（27 条）
设计 §11.1 已正确识别 PRD 枚举实际为 27 条（1~20、22~28，缺 21 号）并逐条映射。抽查核对关键 ★ 场景：
- EX-IM-9（断线 60s 重连）→ **P0-01 冲突**（静默断线场景）。
- EX-IM-22（消费后扣库存/落单失败补偿）→ **P1-01 分支缺失**（仅覆盖落单失败，未覆盖扣库存失败）。
- EX-IM-14/15/25/27（并发确定性）→ 会话锁 + CAS 成立（但需锁纪律保障，见 P2-06）。
- EX-IM-10/16（WAITING 不自动结束、医生离线不结束）→ 转移矩阵正确。
- EX-IM-23（OTC 建会话拒绝）→ consult→product 防御性校验，成立。
- EX-IM-28（同用户同药品并发建会话去重）→ 复用幂等 + IDX 支撑，成立。

**结论**：需求覆盖度约 96%，存在 1 条核心 AC（断线重连）与 1 条 ★ 异常（补偿分支）的硬性缺口，需实现期修复。

---

## 四、问题清单（编号 / 级别 / 位置 / 问题 / 建议）

| 编号 | 级别 | 位置（文档/类/方法/字段） | 问题 | 修改建议 |
| --- | --- | --- | --- | --- |
| P0-01 | P0 | §3.3「每身份在线连接 1，同身份重复在线→拒绝」；§5.3 断线缓冲；`SessionRegistry.sessionSlots` | **静默断线（无 FIN，如断网/休眠）下重连被拒，违反 EX-IM-9★/AC-C4-2★**：服务端对静默断线依赖 `IdleStateHandler` 读空闲 30s→ping→再 30s≈60s 才判死旧连接并释放槽位；客户端在 60s 缓冲期内重连时，旧连接仍注册在 `sessionSlots`，join 命中「同身份重复在线→拒绝」，60s 内重连无法成功。TCP 正常关闭（FIN）无此问题，但断网/休眠正是最常见断线场景，故核心 AC 会失败 | join 去重改为**「接管」语义**：同身份已存在在线 channel 时，判定旧 channel 是否仍存活（`channel.isActive()` 或上次读写时间）；已死则关闭旧 channel、绑定新 channel、按 lastSeq 补发/返回增量起点，而非拒绝。该语义同时统一覆盖「重连」与「多 Tab」（PRD 未要求禁多 Tab，后开者接管前开者）。补 Netty 集成测试：模拟服务端不判死、客户端 60s 内重连，断言重连成功、会话继续、消息补齐 |
| P1-01 | P1 | §6.5 createOrder 编排 ③ 扣库存失败分支；`OrderService.createOrder` | **处方消费成功后扣库存失败（库存不足/商品服务异常）→ 处方未恢复**：§6.5 仅在 ⑤「本地落单失败」做「回补库存+恢复处方」，③ `deductStock` 抛异常直接传播，此时处方已 USED 却无订单 → 悬挂「已占但无订单」，用户无法再凭此处方购买，违反 EX-IM-22★（明确「扣库存/落单失败」均需恢复处方），§11.1 对 EX-IM-22 的覆盖声明不实 | `createOrder` 用**单一补偿门卫**：进入 Rx 消费分支后记 `prescriptionConsumedByThisAttempt`、`stockDeductedByThisAttempt` 两个标记；① consume 成功 → 置位①；② deductStock 成功 → 置位②；③ 后续任何失败（扣库存抛错/本地落单失败）→ 若①则 `ConsultClient.restorePrescription`（USED→ACTIVE），若②则 `restoreStock`。补 `OrderServiceTest`：consume 成功后 deductStock 抛「库存不足」→ 断言 restorePrescription 被调、处方回 ACTIVE、未落单 |
| P1-02 | P1 | §7.2 `POST /api/prescriptions/{no}/consume` 请求体 vs §4.3 CAS SQL | **API 契约与 DB 写不一致**：consume 请求体仅 `{userId, productId, quantity}`，但 §4.3 CAS `SET ... consumed_order_no=:orderNo` 引用 orderNo，实现时无从取值 | consume 请求体增加 `orderNo` 字段（order-service 在 createOrder 开始即预生成 orderNo 后传入）；SQL 与契约统一；`PrescriptionDTO.consumedOrderNo` 由该值回写 |
| P1-03 | P1 | §3.2 注册表清理时机 / §5.5 会话结束清理表；`SessionRegistry.sessionChannels/sessionSlots` | **注册表 map 空 key 无删除路径 → 内存无界增长**：`channelInactive` 只从集合移除元素，§5.5「清理注册表」仅列「关闭 channel、清理 tokens」，未明确移除 `sessionChannels[sessionId]`、`sessionSlots[sessionId]` 的**空 key**。每建一个会话（即使已 ENDED）留下一个空 key，长期累积泄漏 | 定义 `SessionRegistry.purge(sessionId)`：会话进入终态（USER_ENDED/DOCTOR_ENDED/DISCONNECT_TIMEOUT/IDLE_TIMEOUT/PRESCRIPTION_ISSUED 全部路径）时，由结束流程与 `SessionEndScheduler` 统一调用，移除 sessionChannels/sessionSlots 空 key、移除 tokens 中该会话令牌；channelInactive 后集合为空且会话已终态时同步移除 |
| P1-04 | P1 | §4.4 `BatchWriteQueue` 批量 INSERT | **批量 INSERT 单行唯一键冲突拖垮整批 + 重试死循环**：批量批处理中若一行触发 `UK(session_id,seq)` 或 `UK(session_id,client_msg_id)` 冲突（重放帧/seq 冲突），整批失败 → 重试 N 次仍含冲突行 → 永久失败，done 永不完成，消息既未落库也未 ack | 批量写改为**逐行容错**：捕获唯一键冲突单行（查回已存在 seq/msg 完成 done，其余继续）；或整批用 `INSERT IGNORE / ON DUPLICATE KEY`（H2 与 MySQL 方言需统一，关联 P2-09）；重试上限后按 §4.4 完成 exceptionally 并标记 degraded |
| P2-01 | P2 | §3.3/§5.3 | 与 P0-01 关联：多 Tab 与重连的「接管」判定需给出旧 channel 存活判定标准（`channel.isActive()`/上次读写时间）与旧 channel 关闭后槽位释放的原子性（同一 session 锁内完成「关旧绑新」） | 在会话锁内原子完成「校验 token → 释放旧槽位 → 绑定新 channel」，杜绝「旧槽未释放导致新连接被拒」或「两连接同时在线」半状态 |
| P2-02 | P2 | §7.3 WS 握手 / §1.2 浏览器直连 8086 | WS 跨端口直连未做 Origin 校验：任何页面可向 `ws://localhost:8086` 发起连接（虽 fail-closed 需 token，但可被用于探测/占连接） | 握手处理器校验 `Origin` 头仅允许 `http://localhost:8082`，拒绝其余；防 CSWS |
| P2-03 | P2 | §6.4/§12 R-17 `restore` | `restore` 无鉴权且无上下文，恶意/误调用可将已消费处方恢复为 ACTIVE（若该订单已支付则形成处方复用面） | restore CAS 增加 `AND consumed_order_no=:orderNo`（只有「本次消费的订单」才能恢复），缩小爆炸半径；后续按 R-13 加内部密钥 |
| P2-04 | P2 | §4.4 写线程 | 设计写线程为 1~2 个：2 个线程并发 drain 同一会话队列时跨批可能乱序完成 done/ack | 保证**每会话单写者**（每会话队列同一时刻只被一个 drain 持有，可用每会话 drain 锁或直接单写线程）；或显式声明仅 1 个写线程 |
| P2-05 | P2 | §4.4 clientMsgId 去重 | clientMsgId 去重「查库未命中→入队」非原子：同 clientMsgId 两个并发帧都可能通过检查 → 双计 seq、双落库（DB 唯一键兜底但冲突路径未定义处理） | 在 `SessionState` 加 `clientMsgId→seq` seen-map（会话锁内判定）；DB 唯一键仅兜底，冲突分支查回原 seq 返回重复 ack |
| P2-06 | P2 | §4.3 seq 计数器 / §8.2 last_seq | 内存 seq 计数器在会话首次建连前未加载：重启后首条消息若未先读 DB `MAX(seq)`，seq 从 0 分配与历史冲突（UK 冲突） | 首条消息在会话锁内惰性加载 `lastSeq = MAX(chat_message.seq)` 后分配；明确 `session.last_seq` 的维护者（写线程批量提交后更新）与 `MAX(seq)` 的权威关系 |
| P2-07 | P2 | §5.4 断线缓冲兜底调度器 | 「断线缓冲兜底」需知道「谁离线、何时离线」，但注册表不持久化参与者在线态，进程重启后兜底无从判断「离线是否已超 60s」（仅靠内存定时器时，重启后该类会话永不被 DISCONNECT_TIMEOUT 兜底） | `SessionState` 记录 `lastDisconnectAt`（每参与者），调度器遍历注册表中「有参与者离线且 now-lastDisconnectAt>60s」的会话 CAS 结束；重启丢失可接受（演示级，R-12 同源），但设计需明确兜底数据源 |
| P2-08 | P2 | §6.1/§8.2 处方号/会话号 | `RX+yyyyMMddHHmmss+4位随机` / `CS+...` 同一秒内 4 位随机碰撞时 UK 冲突，无重试逻辑 | 唯一键冲突时重试生成（或随机位数扩到 6 位/加毫秒）；补单元测试覆盖碰撞重试 |
| P2-09 | P2 | §10.3 Netty 集成测试 | H2 与 MySQL 方言差异（`INSERT IGNORE/ON DUPLICATE`、唯一键语义）可能使批量写/去重/并发用例在 H2 无法真实复现 MySQL 行为 | 评估 Testcontainers MySQL，或 H2 用 MySQL 模式并校验方言兼容；批量写去重用例在两种库上均验证 |
| P2-10 | P2 | §7.4 页面路由 / FR-P2 | Rx 详情页「存在 ACTIVE 处方时展示『凭已有处方购买』」入口未在设计页面路由表明确（仅 `/prescriptions` 列表） | `viewProduct` 增加「查该用户该药品 ACTIVE 处方」（`GET /api/prescriptions/user/{userId}` 过滤 productId）→ 有则展示「凭已有处方购买」链接；确认订单页 quantity 锁定 + 处方号预填（AC-P2-2） |
| P2-11 | P2 | PRD §2/§7 + 设计 §3/R-13 | PRD 声称「浏览器无法篡改参数访问他人会话」，与演示身份（userId 客户端可伪造、无鉴权）矛盾：隔离是**结构性**（路由只查本会话 + 归属校验），不防恶意冒充 userId | 修正 PRD/设计表述为「防误操作/防跨会话误投，不防恶意冒充 userId（演示无鉴权，R-13）」；在 §3 明确隔离成立前提（演示身份不可信，隔离仅防误用） |
| P2-12 | P2 | §5.5/§4.4 会话结束清理 | 会话结束需先 flush 写队列残留消息再关 channel/token，设计未明确结束流程与写线程的同步（避免结束 SYSTEM 消息与在途用户消息落库竞态、残留消息丢失） | 结束流程在会话锁内：先 drain/flush 该会话队列（等待 done），再落 SYSTEM 结束消息、推 session_ended、purge 注册表 |
| P2-13 | P2 | `order-service/.../client/ClientSupport.java` + consult 新增 | `ClientSupport` 仍为服务内复制（M1 P2-02 未落地），M2 新增 consult-service 将出现第三份 | 将 `ClientSupport.toBusinessException` 下沉 `mall-common`，五服务复用 |
| P3-01 | P3 | 设计 §0 标题 | 「逐条落实其 52 条验收标准」与 §11.2 矩阵（62 条）不符；PRD §12 实为 **62** 条 | 修正计数为 62 |
| P3-02 | P3 | 设计 §11.1 | 「28 条异常」实为 27 条（PRD 缺 21 号）；设计已正确说明，仅统一表述 | 统一为「27 条（1~20、22~28）」 |
| P3-03 | P3 | §3.2/§8 会话键 | 注册表/写队列用 `sessionId`（BIGINT）还是 `sessionNo`（CS...）作键未统一（§3.2 registry 用 String，§8.3 message 用 session_id BIGINT） | 统一以 `sessionId`（主键）为注册表/写队列键，`sessionNo` 仅作对外展示 |
| P3-04 | P3 | §8.2 last_seq | `session.last_seq` 与 `chat_message.MAX(seq)` 双口径 | 明确 `MAX(seq)` 为权威、last_seq 为缓存及维护者（P2-06 关联） |
| P3-05 | P3 | §5.5 SYSTEM 消息 | 结束/接入/开方 SYSTEM 消息是否走批量写队列、何时 flush 未明确 | 明确 SYSTEM 消息与普通消息同走写队列（保序），关键 SYSTEM（结束）随结束流程 flush |
| P3-06 | P3 | ADR-IM-01 生命周期 | Netty 优雅停机顺序未用 `SmartLifecycle` 显式 phase（拒新连→drain→shutdown eventLoop→再关 DB 池） | `SmartLifecycle.getPhase()` 使 Netty 先于 DB 连接池关闭 |
| P3-07 | P3 | PRD FR-C4 | PRD「含医生正在输入」与 NG-5（无输入中/已读回执）冲突；设计按「已落库文本消息」解释正确 | PRD/设计注明：活动刷新仅指已提交的文本消息（无输入中事件） |
| P3-08 | P3 | §4.4 degraded | 写队列 degraded 背压状态何时恢复未定义 | 定义恢复条件（积压回落阈值后退出 degraded） |

---

## 五、分维度详细评审

### 5.1 需求覆盖
- 62 条 AC 覆盖度约 96%。关键缺口：**AC-C4-2（断线 60s 重连）在静默断线场景被 P0-01 阻断**；**AC-P2-11 / EX-IM-22（补偿）扣库存失败分支缺失（P1-01）**。
- 覆盖矩阵完整（§11.1/§11.2），EX-IM 编号 21 缺失被正确识别（P3-02）；AC 计数笔误「52/62」（P3-01）。
- 防 happy path：测试策略（§10）对每条 AC/EX 均给测试位置且明确 anti-happy-path 断言（精确 message、幂等零副作用、并发确定性），属实。

### 5.2 架构正确性
- **模块边界**：新增 consult-service（REST :8085 + Netty WS :8086）边界清晰，`order→consult→product`（consult 防御性校验 rxFlag）为单向只读 DAG，无编译期循环。✓
- **依赖矩阵**：与现状一致（order 依赖 product/payment/recommend + 新增 consult）；浏览器仅经 BFF REST，唯一例外是 WS 直连 consult :8086（长连接语义合理，但需 Origin 校验，P2-02）。✓
- **数据所有权**：会话/消息/处方三表归 consult 所有，订单 `prescriptionNo` 仅快照；处方状态权威在 consult，正确。✓
- **RestClient 风格一致性**：ConsultClient 复用 `RestClientConfig` 的带超时 Builder + `ClientSupport.toBusinessException`，与 ProductClient 同模式，成立；`ClientSupport` 未下沉 mall-common（P2-13）。

### 5.3 会话隔离（A）
- **token 机制**：createSession / doctor-join 签发、join 帧校验、绑定 `(sessionId,role,participantId)`，未绑定 channel fail-closed——结构性隔离成立（路由只查本会话 channel 集，结构上无跨会话引用，M7=0）。✓
- **安全局限（PRD 表述需修正）**：演示身份 userId 客户端可伪造，ws-token 端点按「userId 匹配」发令牌，恶意用户可冒充他人获取令牌。隔离**防误操作/防跨会话误投，不防恶意冒充**（R-13 承认）。PRD §2 声称「浏览器无法篡改参数访问他人会话」不成立 → P2-11。
- **注册表并发一致性**：`ConcurrentHashMap + CopyOnWriteArraySet`（读多写少）选型合理；channel.attr 冗余缓存 O(1)。**内存回收缺口**：空 key 无清理路径 → P1-03。
- **每会话限 2 连接 / 多 Tab**：实现为「每身份在线 1」→ 静默断线重连被拒（P0-01）；需「接管」语义（P2-01）。

### 5.4 并发控制（B）
- **事件循环零阻塞**：ADR-IM-04 + §4.1 贯彻「内存操作+入队」，DB 经写队列异步，`writeAndFlush` 线程安全，正确。✓
- **会话锁**：`ReentrantLock` 串行化裁决类操作，锁内不碰 DB，纳秒级持有，正确。**关键纪律**：WS 消息裁决与 REST 状态变更（接诊/结束/开方）必须共用同一会话锁，且内存 `SessionState.status` 在锁内同步更新——否则存在「内存态 stale 放行已结束会话消息」的窗口（设计未显式声明，需实现期强制，P2-12 关联）。
- **BatchWriteQueue**：按会话分队列 + 定时 drain + flush + 背压，设计合理；缺陷见 P1-04（整批冲突）、P2-04（多写线程跨批乱序）、P2-05（clientMsgId 并发去重）、P2-12（结束与 flush 同步）。
- **seq/clientMsgId**：seq 在锁内分配、DB UK 兜底，思路正确；重启惰性加载缺口 P2-06；clientMsgId 去重竞态 P2-05。
- **状态 CAS**：全部 `UPDATE...WHERE status=...` 影响行数裁决，DB 为唯一权威，双保险成立。✓

### 5.5 会话结束判定（C）
- **四判据覆盖**：主动（用户/医生）、断线超时 60s、空闲 30min（仅 ONGOING、心跳不计）、开方完成——与 PRD FR-C4 逐条对齐。✓
- **终态定义自洽**：会话唯一终态 `ENDED` + `endReason`（含 PRESCRIPTION_ISSUED）；`EXPIRED` 为处方状态。设计 §0/§5.1 澄清到位。✓
- **竞态**：结束 vs 消息/开方并发 → 会话锁 + CAS 确定性成立（前提锁纪律，见 5.4）。
- **误判**：WAITING 不因医生未接入结束（AC-C4-6）、医生离线不结束（AC-C4-10）均正确。✓
- **漏判/冲突**：**静默断线 60s 内重连被「每身份在线 1」拒绝（P0-01）**——判据 2 与隔离规则冲突；**断线缓冲兜底数据源未定义（P2-07）**。

### 5.6 处方安全（D）
- **fail-closed 校验链顺序**：第 0 步（快速失败给精确文案）→ 第 1 步（单条 CAS 原子裁决）两步配合正确；TOCTOU 由第 1 步 CAS 兜底。✓
- **一次性消费**：`WHERE no=? AND status='ACTIVE' AND valid_until>NOW() AND user_id=? AND product_id=? AND quantity=?` 单条原子，并发行锁串行，复用率=0 成立。✓
- **补偿**：P1-01 分支缺失（扣库存失败未恢复处方）；P1-02 契约缺 orderNo；双补偿方向（回补库存 + 恢复处方）与 M1 语义一致，restore CAS `WHERE status='USED'` 天然防重复恢复。✓
- **处方号安全**：服务端生成 + UK 兜底，4 位随机同秒碰撞需重试（P2-08）。
- **医生越权开方**：`doctorId == session.doctorId` + 仅 ONGOING + 处方药品=会话药品，AC-P1-4/6 成立。✓

### 5.7 技术风险
- **REST:8085 与 WS:8086 分端口**：Spring MVC(Tomcat) 与 Netty 无法共享端口、WebFlux 被排除，分端口是合理取舍（R-10）。WS 跨端口需 Origin 校验（P2-02）。
- **Netty 生命周期**：ApplicationRunner 启动 + @PreDestroy/SmartLifecycle 停机思路正确；建议显式 phase（P3-06）。
- **演示身份/restore 无鉴权**：R-13/R-17 承认且可接受，restore 建议订单号绑定加固（P2-03）。

### 5.8 可测性
- **Netty 集成测试**：`java.net.http.HttpClient.newWebSocketBuilder()` 真实起 Netty + 随机端口，可行（JDK HttpClient 原生支持 WS 文本帧）；短时序参数（grace/idle/drain）走测试 profile，思路正确。✓
- **并发/隔离/结束判定/处方一次性可测**：均有明确用例与断言口径（seq 单调、复用率=0、终态拒消息、连接上限）。✓
- **防 happy path**：§10 明确 anti-happy-path，断言精确 message、幂等零副作用、并发确定性。✓
- **风险**：H2 vs MySQL 方言差异（P2-09）；竞态用例（结束 vs 消息）存在时序 flaky 风险，需允许重试/断言放宽到「结果确定」而非「谁先」。

### 5.9 集成正确性
- **下单时序**：先消费处方 → 扣库存 → 落单。方向可接受（把「能否成交」裁决前置到并发边界），但必须补「扣库存失败恢复处方」（P1-01）。消费先后顺序本身不构成缺陷，补偿完备性才是。
- **rxFlag 数据迁移**：`ddl-auto=update` 加 `rx_flag NOT NULL DEFAULT FALSE`，老数据默认 false；M1 种子无「药品」品类，无存量药品需回填；AC-D1-5 成立。✓
- **DeepSeek 推荐剔除 Rx**：候选池 + AI 结果 + 规则选品三层过滤 `rxFlag=true`，决策合理（Rx 不可直接购买，推荐它误导用户）；需在 AI 结果校验与规则选品两处同时加（ADR-IM-08 已列）。✓

---

## 六、R-10 ~ R-18 待评审风险逐条意见

| 编号 | 方案原评级 | 评审裁定 | 意见 |
| --- | --- | --- | --- |
| R-10 | 中 | 接受（附 P2） | WS/REST 分端口合理（Spring MVC 与 Netty 无法共享、WebFlux 排除）；WS 握手补 Origin 校验（P2-02）。浏览器直连 8086 的跨端口语义需在文档注明。 |
| R-11 | 低 | 接受（P3） | 处方单药品模型符合 PRD「处方=会话目标药品」；多药品组合留扩展。 |
| R-12 | 低 | 接受（P3） | 内存 token 重启失效、重连先 REST 复用取新 token，演示级可接受。 |
| R-13 | 中 | 接受（P2 修正） | 演示身份隔离为**结构性**，不防恶意冒充 userId；PRD「浏览器无法篡改参数访问他人会话」表述需修正（P2-11）。 |
| R-14 | 中 | 接受（P2） | 写队列异步 ≤50ms 延迟远低于 500ms 阈值；Netty 集成测试需固化「先落库后 ack」时序断言（§10.3 已列）。 |
| R-15 | 中 | 接受（P3） | `ddl-auto=update` 演示可接受；上线前收敛 Flyway（沿 M1 R-02）；Rx 老订单无处方号展示层容错。 |
| R-16 | 低 | 接受（P3） | Netty 连接/速率上限为代码级，演示可接受；生产走网关。 |
| R-17 | 中 | 接受（P2 加固） | restore 无鉴权可接受（演示），但建议 CAS 绑定 `consumed_order_no` 缩小爆炸半径（P2-03）。 |
| R-18 | 低 | 接受 | 空闲/断线常量集中可配；需防生产误配为 0（配置校验下限，如 grace≥5s）。 |

---

## 七、评审结论

### 结论：有条件通过（须先修复以下 5 项后放行实现）

**必须修改项（阻塞实现）：**
- **P0-01（阻断）**：解决「每身份在线 1」与「断线 60s 重连」在静默断线场景的冲突——join 改为「接管」语义，保证 EX-IM-9★ / AC-C4-2★ 通过。
- **P1-01**：补「处方消费成功后扣库存失败 → 恢复处方」补偿分支，保证 EX-IM-22★ 完整（无悬挂处方）。
- **P1-02**：consume 请求体增加 `orderNo`，与 CAS `consumed_order_no` 对齐。
- **P1-03**：定义 `SessionRegistry.purge(sessionId)`，会话进入终态时清理注册表空 key 与令牌，防内存无界增长。
- **P1-04**：批量写队列单行唯一键冲突改为逐行容错/`INSERT IGNORE`，杜绝整批失败重试死循环。

**实现期应收敛（P2/P3）：** 接管判定标准（P2-01）、WS Origin 校验（P2-02）、restore 订单号绑定（P2-03）、写线程每会话单写者（P2-04）、clientMsgId 会话锁内去重（P2-05）、seq 惰性加载（P2-06）、断线兜底数据源（P2-07）、处方号碰撞重试（P2-08）、H2/MySQL 方言（P2-09）、Rx 详情页凭方购买入口（P2-10）、PRD 隔离表述修正（P2-11）、结束与写队列 flush 同步（P2-12）、ClientSupport 下沉 mall-common（P2-13）。

**放行条件**：P0-01 + P1-01~04 完成设计修订并纳入测试后，进入实现阶段（F4/F5/F6）；其余 P2/P3 随实现分批收敛，不阻断主流程。

---

## 八、总结（≤200 字）

结论：**有条件通过**。共 **P0×1、P1×4**。P0：静默断线 60s 内重连被「每身份在线 1」拒绝，EX-IM-9/AC-C4-2 必失败，join 须改「接管」语义；P1：处方消费后扣库存失败不恢复处方（EX-IM-22 悬挂）、consume 契约缺 orderNo、注册表空 key 不清理致内存泄漏、批量写单行冲突整批失败。修复上述 5 项后放行实现。架构方向、会话隔离结构、CAS 一次性消费、四判据状态机、Netty 集成测试策略均正确，风险可控。
