# 《药品品类 + IM 在线问诊 + 处方购药》研发方案设计（M2）

| 项目 | 内容 |
| --- | --- |
| 文档名称 | simple-mall（AI 智能导购）M2——药品品类 + IM 在线问诊（Netty WebSocket）+ 处方购药 |
| 文档版本 | v1.0 |
| 文档作者 | 研发架构师（R&D Architect / Tech Lead） |
| 评审角色 | 架构组、后端研发、QA、DBA |
| 评审状态 | 待评审 |
| 技术栈基线 | Spring Boot 3.5.16 · Spring MVC + Thymeleaf · Spring Data JPA · MySQL 8.4 · Java 25 · **Netty（嵌入）WebSocket** · DeepSeek API |
| 关联文档 | README.md、docs/02-solution-design.md（M1 方案，沿用其结构与规范）、docs/07-PRD-IM.md（**M2 PRD v1.0 定稿，验收标准/异常场景为准**） |

> 本方案为**纯设计文档**，不含任何代码改动。需求以 PRD 07 为唯一依据，本方案逐条落实其 52 条验收标准（AC-D1/C1/C2/C3/C4/P1/P2/P3/IM 全组）与 28 条异常场景（EX-IM-1~28），覆盖矩阵见第 11 章。
>
> **术语澄清（评审重点）**：PRD 的「会话状态」枚举为 `WAITING / ONGOING / ENDED`（ENDED 为唯一终态）；`PRESCRIPTION_ISSUED` 是**结束原因 endReason**（非会话状态），`EXPIRED` 是**处方状态**（非会话状态）。本方案严格按 PRD §6 / §14.1 / §14.2 命名，并以 `endReason` 区分五种结束方式。涉及「终态(ENDED/EXPIRED/PRESCRIPTION_ISSUED)」的评审口径时，映射为：`ENDED(PRESCRIPTION_ISSUED)`、`ENDED(DISCONNECT_TIMEOUT)`、`ENDED(IDLE_TIMEOUT)` 等「ENDED + endReason」组合（见第 6 章状态机）。

---

## 目录

- [0. 术语与约定](#0-术语与约定)
- [1. 架构总览](#1-架构总览)
- [2. 关键设计决策（ADR 风格）](#2-关键设计决策adr-风格)
- [3. 问题 A：IM 会话隔离](#3-问题aim-会话隔离)
- [4. 问题 B：并发控制](#4-问题b并发控制)
- [5. 问题 C：会话结束判定](#5-问题c会话结束判定)
- [6. 问题 D：处方生命周期与安全](#6-问题d处方生命周期与安全)
- [7. API 契约](#7-api-契约)
- [8. 数据库设计](#8-数据库设计)
- [9. 与现有服务集成 / 页面 / 容错降级](#9-与现有服务集成--页面--容错降级)
- [10. 测试策略（研发视角）](#10-测试策略研发视角)
- [11. 需求覆盖矩阵（AC / EX-IM）](#11-需求覆盖矩阵ac--ex-im)
- [12. 待评审风险点](#12-待评审风险点)
- [附：方案核心决策与待评审风险点（≤200 字）](#附方案核心决策与待评审风险点200-字)

---

## 0. 术语与约定

| 术语 | 含义 |
| --- | --- |
| 会话（Session） | 用户就某一 Rx 药品发起的在线问诊。状态 `WAITING 等待接入 / ONGOING 进行中 / ENDED 已结束（终态）`；`endReason` ∈ `USER_ENDED / DOCTOR_ENDED / DISCONNECT_TIMEOUT / IDLE_TIMEOUT / PRESCRIPTION_ISSUED`。 |
| 会话令牌（token） | 服务端为「会话 + 身份」签发的一次性绑定凭证（`sessionNo + 签名` 或内存随机串），WS `join` 帧携带，校验通过后才把 channel 绑定到会话。 |
| 注册表（Registry） | 内存态「在线连接」注册表：`sessionId→Set<Channel>`、`Channel→sessionId`、`token→(sessionId,role,participantId)`。只存连接与令牌，不存业务状态（业务状态以 DB 为权威）。 |
| 参与者（Participant） | 会话内的用户（USER，`session.userId`）与医生（DOCTOR，`session.doctorId`=演示身份 `demo-doctor`）。每会话至多 2 个参与者、各至多 1 条在线连接。 |
| fail-closed | 默认拒绝语义：channel 未绑定会话 → 不发不转发；入站帧归属不符 → 丢弃；状态/处方非法 → 拒绝。任何校验失败都不得产生部分副作用。 |
| seq | 会话内单调递增的消息序号，DB 唯一键 `(session_id, seq)` 兜底，保证同会话双端并发发送不乱序。 |
| clientMsgId | 客户端生成的幂等键（UUID），DB 唯一键 `(session_id, client_msg_id)`，客户端重试/重复帧只消费一次。 |
| 批量写队列（BatchWriteQueue） | 消息持久化防事件循环阻塞的机制：按会话分队列、定时 drain 合并批量 INSERT、`flush()` 保证「落库后才 ack/转发」、可 await 背压。见 §4.4。 |
| 断线缓冲 | 连接断开后允许重连的宽限期（默认 60s，可配置），期内重连会话继续、消息按 seq 补齐。 |
| 空闲超时 | `ONGOING` 会话双方连续 30 分钟无**文本消息**（心跳不计）自动结束。`WAITING` 阶段不计时。 |
| 可下单窗口 | 处方 `validUntil`（默认 7 天）内可凭处方下单，与会话是否结束无关。 |
| 处方 | `no` 处方号、session/user/doctor/product 绑定、数量 + 用法用量、状态 `ACTIVE / USED / EXPIRED / REVOKED`。 |
| 演示身份 | 浏览器首次访问生成 `userId`（localStorage/Cookie）；医生固定 `demo-doctor`。服务端最终校验归属（M1「服务端强制校验」原则）。 |

**全文档约定**（沿用 M1）：
1. REST 错误体统一 `{"message":"..."}`；WS 错误统一 `{"type":"error","code":"...","message":"..."}`。
2. 状态字段 DB 存枚举 `name`，JSON 传枚举 `name`，中文文案走 DTO `statusLabel`。
3. 服务间通信仅 `Spring RestClient`（含新增 `ConsultClient`），均配置超时与异常转换（`ClientSupport.toBusinessException`）。
4. 金额 `BigDecimal` / `DECIMAL(12,2)`；时间 `LocalDateTime` ISO 串；字段 `camelCase`。
5. **端口分配（评审点 R-10）**：consult-service 的 REST（Spring MVC/Tomcat）占用 :8085（沿用「服务端口=REST 端口」约定）；Netty WebSocket 服务端独立绑定 `consult.ws.port=8086`。原因：Spring MVC 依赖 Servlet 容器（Tomcat），无法与 Netty 共用同一端口；WebFlux 被明确排除。页面 JS 客户端连 `ws://localhost:8086/ws/chat`（见 ADR-IM-01）。

---

## 1. 架构总览

### 1.1 系统架构图

```
                         浏览器（唯一入口 http://localhost:8082）
                             问诊聊天 / 我的问诊 / 医生控制台 / 我的处方 / Rx 下单
                            │                    │
               Thymeleaf 页面（order-service 渲染） │  JS WebSocket 客户端
                            │                    │  ws://localhost:8086/ws/chat
                            ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        order-service  (BFF / 编排层)                     │
│   端口 :8082  ·  schema: shopdb_order                                     │
│   ShopController(全部页面) + OrderApi + Client×4（Product/Payment/Recommend/Consult） │
└──────┬──────────────┬────────────────┬───────────────┬───────────────────┘
       │ REST 商品/库存 │ REST 发起支付   │ REST AI 推荐   │ REST 问诊/处方（新增）
       ▼              ▼                ▼               ▼
┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────────────────────────┐
│product-service││payment-service││recommend-    ││       consult-service（新增）     │
│ 纯 REST :8081 ││ 纯 REST :8083 ││service       ││  :8085 REST(Spring MVC/Tomcat)    │
│ shopdb_product││ shopdb_payment││ 纯 REST :8084││  :8086 Netty WebSocket(独立端口)   │
│ +rxFlag/药品  ││               ││ 无 DB        ││  schema shopdb_consult            │
└──────┬────────┘└──────────────┘└──────┬───────┘│  ┌────────────────────────────┐   │
       │                                │         │  │ 会话/消息/处方 服务 + 状态机 │   │
       └───────────────▲────────────────┘         │  │ 会话注册表(内存) / 批量写队列 │   │
                       │ REST（consult 建会话时    │  │ Netty WS Handler/心跳/限流    │   │
                       │ 校验商品 rxFlag=true）     │  └────────────────────────────┘   │
                       └──────────────────────────┘                                   │
                                                                                     │
  依赖方向：order → product / payment / recommend / consult；consult → product（rxFlag 校验）。
  浏览器 ↔ consult 之间仅「实时 WS 长连接」直达（页面渲染与 REST 仍全部走 BFF，杜绝 CORS/鉴权扩散）。
```

### 1.2 模块边界与职责

| 模块 | 类型 | 端口 | 数据库 | 职责边界 |
| --- | --- | --- | --- | --- |
| `mall-common` | 共享库（纯 Java） | — | — | DTO、`BusinessException`、`ApiError`、状态枚举。新增：`ProductDTO.rxFlag`；`SessionDTO/ChatMessageDTO/PrescriptionDTO`、`SessionStatus/PrescriptionStatus` 枚举。**只放契约，不放业务逻辑**。 |
| `product-service` | 微服务（纯 REST） | 8081 | `shopdb_product` | 商品 CRUD + 原子库存扣减/回补 + 品类。新增：`Product.rxFlag`、药品品类种子数据（OTC 5 + Rx 4）、`rxFlag` 回填默认 false。 |
| `order-service` | 微服务（BFF） | 8082 | `shopdb_order` | 全部页面（含问诊聊天页/医生控制台/我的问诊/我的处方）+ 订单 REST。新增：`OrderForm/Order.prescriptionNo`、`createOrder` Rx 处方校验与原子消费/补偿、`ConsultClient`、Rx 下单拦截。 |
| `consult-service` | 微服务（**新增**） | 8085(REST) / 8086(WS) | `shopdb_consult` | 会话（WAITING/ONGOING/ENDED + endReason）、IM 实时消息（Netty WS）、消息持久化与 seq、断线缓冲/空闲超时调度、处方（ACTIVE/USED/EXPIRED/REVOKED）开具/消费/撤销/过期、处方原子一次性消费。 |
| `payment-service` | 微服务（纯 REST） | 8083 | `shopdb_payment` | 模拟支付（沿用 M1，无改动）。 |
| `recommend-service` | 微服务（纯 REST，无 DB） | 8084 | 无 | AI 智能导购。新增：候选池与 AI 结果校验**剔除 `rxFlag=true` 的 Rx 药品**（Rx 不可被推荐直接购买）。 |

**关键原则**：
- **数据所有权**：每服务只能写自己 schema 的表，跨服务数据一律 REST。`consult-service` 的三张表（会话/消息/处方）全部归 consult 所有；订单的 `prescriptionNo` 只是快照，处方状态以 consult 为权威。
- **前端聚合**：浏览器唯一入口是 order-service；实时 WS 直连是唯一例外（长连接语义无法经 HTTP BFF 代理），但仅承载聊天帧，不承载任何业务裁决（业务裁决全部在 consult 服务端）。
- **双服务器共存**：consult-service 内 Spring Boot(Tomcat) 提供 Spring MVC REST，Netty 独立起 WebSocket 服务端（见 ADR-IM-01）。

### 1.3 服务依赖方向（依赖矩阵）

**运行期调用方向**（`A → B` 表示 A 调用 B）：

| 调用方 \ 被调用方 | product | order | payment | recommend | consult | DeepSeek | 浏览器 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| order-service | ✓ 商品/库存 | — | ✓ 发起支付 | ✓ AI 推荐 | ✓ 问诊/会话/处方 | — | — |
| consult-service | ✓ 建会话校验 rxFlag | — | — | — | — | — | ✓ WS 帧（浏览器→consult） |
| payment-service | — | ✓ 校验订单/确认回调 | — | — | — | — | — |
| recommend-service | — | — | — | — | — | ✓ chat/completions | — |
| product-service | — | — | — | — | — | — | — |
| mall-common | 被所有服务编译期依赖（纯 DTO，无运行期调用） | | | | | | |

**编译期依赖**（Maven）：`product/order/payment/recommend/consult` → `mall-common`。五个服务之间互不依赖对方代码，**无编译期循环依赖**。

```
依赖方向图（有向无环，DAG）：

   mall-common (DTO 契约)
        ▲ ▲ ▲ ▲ ▲
        │ │ │ │ │         编译期
   product order payment recommend consult

   order ──▶ product ──▶ (无)
   order ──▶ payment ──▶ order   （事件式回调，M1 已评审）
   order ──▶ recommend ──▶ DeepSeek（外部）
   order ──▶ consult ──▶ product（新增：consult 校验商品 rxFlag）
   browser ──▶ consult（WS 长连接，仅聊天帧）
```

- **关于 `order → consult → product` 的链路**：order 经 BFF 建会话时 consult 需要商品 rxFlag 做防御性校验（FR-C1：非 Rx 药品拒绝建会话）。这条边是「consult 读 product 只读接口」，单向无环，合法。
- **规避规则**：consult 不得反向调用 order；order 只通过 `ConsultClient` 访问 consult 的最小 REST 面；医生/用户身份校验全部在 consult 服务端完成，order 不做二次鉴权。

---

## 2. 关键设计决策（ADR 风格）

### ADR-IM-01 Netty 直接嵌入做 WebSocket 服务端；REST 仍用 Spring MVC（双端口）

- **决策**：consult-service 内 Spring Boot（Tomcat + Spring MVC）承载 REST（:8085）；另起一个独立 Netty 服务端（`ServerBootstrap` + `NioEventLoopGroup` + `WebSocketServerProtocolHandler`）承载 WebSocket（`consult.ws.port=8086`）。**不用 Spring WebFlux / STOMP**。
- **理由**：① 需求明确「Netty 直接嵌入、不用 WebFlux/STOMP」；② Spring MVC REST 复用项目既有 `@RestController` + `@RestControllerAdvice` 与 `ClientSupport` 错误转换模式，五个服务契约一致；③ 会话注册表、连接生命周期、帧协议、心跳/限流需要**细粒度控制**，Netty `ChannelHandler` 链最直接；④ 消息链路是「高频小消息 + 异步持久化」，与 Servlet 线程模型（一请求一线程、阻塞读写）不匹配，Netty 事件循环模型天然适配。
- **端口冲突的解决**：Spring MVC 依赖 Servlet 容器（Tomcat），无法与 Netty 共享同一端口；WebFlux 被排除，故 WS 与 REST 必须分端口。本期采用「REST :8085 + WS :8086」（保留「服务端口=REST 端口」的约定；WS 端口可配置 `consult.ws.port`）。
- **取舍**：若评审要求 WS 与 REST 同端口，唯一可行方案是用单一 Netty 同时承载 HTTP(REST)+WS 而**放弃 Spring MVC**（服务间 REST 契约需改用自定义 JSON over Netty，成本高，超本期范围）——列入 R-10。
- **生命周期**：Netty 服务器作为 Spring Bean 启动（`ApplicationRunner`），`@PreDestroy`/`SmartLifecycle.stop()` 优雅停机（先拒绝新连接、再 drain 写队列、最后 shutdown eventLoop）。

### ADR-IM-02 DB 是业务状态唯一权威；内存注册表只存「在线连接 + 令牌」

- **决策**：会话状态、消息、处方全部以 `shopdb_consult` 落库为唯一权威；内存 `SessionRegistry` 只保存**无需持久化**的连接态（channel 集合、反向映射、token 绑定）。
- **理由**：状态机流转、处方消费等正确性必须靠 DB 原子操作（CAS）兜底（§4.3），内存态不可信；但 channel 指针、在线参与者集合是 JVM 对象，落库无意义且频繁变更成本高，放内存。会话结束、断线重连等判定同时依赖「DB 状态」与「内存在线态」两个视图，二者通过会话锁与定时调度保持一致。
- **取舍**：内存注册表在进程重启后丢失，在线 channel 全部断开；客户端重连时先调 REST（createSession 幂等复用返回会话）重新取 token 再建连，演示级可接受（NFR-2 仅要求 50 并发在线，单机）。

### ADR-IM-03 fail-closed 会话隔离（token 绑定 + 归属校验 + 每会话 2 连接）

- **决策**：会话隔离采用「签发令牌 → join 校验 → 绑定 channel↔session → 入站/路由全按绑定会话」的 fail-closed 链（§3）。
- **理由**：浏览器可伪造任何参数（PRD NFR-4/NFR-10），隔离正确性必须由服务端强制。token 使「建会话」与「建连接」解耦，避免 userId 直接作为连接凭据被仿冒；路由只查本会话 channel 集，从结构上杜绝跨会话投递（M7 隔离违规 = 0）。

### ADR-IM-04 事件循环内绝不做阻塞 IO；消息落库异步化 + 批量写队列

- **决策**：Netty 事件循环线程只做「内存操作 + 入队」，DB 读写全部交给**独立异步写线程**（`BatchWriteQueue`，§4.4）；消息「先落库成功再转发/确认」（PRD NFR-6）通过「入队即返回 `CompletableFuture`，flush 提交成功后回调转发/ack」实现，不阻塞事件循环。
- **理由**：数据库 JDBC 是阻塞 IO，若在 `channelRead0` 内同步落库，慢 SQL/锁等待会卡死该事件循环上的全部连接（事件循环复用），是 Netty 应用的经典错误。批量写合并 N 条消息为一次 INSERT，既满足实时性又显著降低 DB 往返。
- **取舍**：异步化引入「事件循环 → 写队列 → DB」的延迟（约一个 drain 周期，≤50ms），对 ≤500ms 端到端延迟（NFR-1）远低于阈值；「先 ack 后转发」改为「落库后 ack 并转发」，保证发送方只在该消息已持久化时才认为成功（M10 不丢 = 0）。

### ADR-IM-05 每会话串行化 + CAS 状态流转

- **决策**：同一会话的所有「裁决类」操作（发消息、接诊、开方、结束、空闲/断线判定）经**会话锁**串行化；状态变更一律用 `UPDATE ... WHERE status='X'`（影响行数 = 1 才成功）做 CAS（§4.3）。
- **理由**：双端（USER/DOCTOR）channel 可能挂在不同事件循环，跨 channel 的并发必须显式串行化才能保证 seq 单调与「结束 vs 消息」确定性（EX-IM-14/25/27）；CAS 让并发冲突在 DB 原子层面裁决，赢者提交、输者看到 `affectedRows=0` 后按明确语义拒绝，杜绝半状态（NFR-8）。

### ADR-IM-06 会话结束 = 多判据 + 状态机 + 兜底调度器

- **决策**：五种结束判定（主动用户/主动医生/断线超时/空闲超时/开方完成）统一收敛到会话状态机（§5）；其中断线缓冲、空闲超时由**定时调度器**兜底扫描（§5.4），配合 `IdleStateHandler` 心跳探活（§5.3）。
- **理由**：会话结束是 G5/NFR-7 的核心，必须「确定、可测、不误判」。多判据共享同一状态机与 CAS，保证并发判定一致；调度器兜底覆盖「进程内无事件触达」的静默场景（如客户端假死不重连）。
- **防误判**：`WAITING` 不因医生未接入自动结束（AC-C4-6/EX-IM-10/16）；空闲计时仅从 `ONGOING` 开始；心跳不计入活动；短断线 60s 内重连不结束。

### ADR-IM-07 处方一次性消费 = 单条 CAS UPDATE 为唯一边界；失败补偿恢复

- **决策**：处方消费在 `consume` 接口内用**单条原子比对更新**一次性完成「存在→ACTIVE→未过期→归属→药品匹配→数量一致→置 USED」的全部校验（§6.4）；`order-service` 在「消费成功 → 扣库存 → 落单」后，若本地失败则补偿「回补库存 + 恢复处方 ACTIVE」（EX-IM-22/AC-P2-11）。
- **理由**：处方防复用（G4/M2 复用率 = 0）必须由 DB 唯一原子操作为边界，代码「先查后改」在并发下必然被穿透（EX-IM-26/AC-P2-10）。补偿语义与 M1 库存补偿一致（ADR-02），把「处方被占但无订单」的悬挂窗口最小化。
- **归属前提**：医生只能给**自己已接入（接管）**的会话开方（`session.doctorId` 匹配，AC-P1-6），会话必须 `ONGOING`（AC-P1-4），处方药品 = 会话目标药品（FR-P1）。

### ADR-IM-08 Rx 药品不进 AI 推荐候选池

- **决策**：recommend-service 的候选池（catalog）与 AI 结果校验（三层幻觉校验）中，**剔除 `rxFlag=true` 的 Rx 药品**（不推荐、不展示「买 Rx」），OTC 药品正常参与推荐。
- **理由**：Rx 药品不可「直接购买」，推荐它购买在合规语义上误导用户（体验与安全双输）。AI 可能幻觉地把 Rx 药品挑进推荐，必须由规则层硬过滤。
- **实现**：`ProductDTO` 增 `rxFlag` 字段随 catalog 传递；`AiRecommendationService.parseAndValidate` 增加「非 Rx」过滤层，`RuleRecommendationService` 选品时排除 `rxFlag=true`。

### ADR-IM-09 order 的 Rx 下单编排顺序：消费处方 → 扣库存 → 落单 → 失败双补偿

- **决策**：`createOrder(productId, quantity, ..., prescriptionNo)` 对 Rx 商品执行「① `ConsultClient.consumePrescription` 原子消费 → ② 扣库存 → ③ 本地落单（含 `prescriptionNo` 快照）」，任意后续失败都做「回补库存 + 恢复处方」双补偿（best-effort，失败留日志人工对账）。
- **理由**：与 M1「先扣库存再落单」同构——把「能否成交」的裁决前置到唯一并发边界（此处处方消费+库存扣减），落单只是快照。顺序上「先消费处方」避免「扣了库存却占着处方」；若库存扣减失败，处方尚未消费（或已消费需恢复）。
- **兜底**：`ConsultClient` 不可用时 Rx **fail-closed**（抛「问诊服务不可用，暂无法凭处方下单」，不扣库存、不落单、不消费处方，AC-IM-6）；OTC 商品不受影响，走 M1 原流程。

---

## 3. 问题 A：IM 会话隔离

### 3.1 隔离模型总览

```
浏览器（用户 A，userId=U1）
  │  POST /api/sessions {userId:U1, productId:5}        ──▶ createSession
  │  ◀─ CreateSessionResponse {sessionNo:CS..., token:T1}
  │  GET /consult/CS...（BFF 渲染 + 取 token）
  │  WS  ws://localhost:8086/ws/chat?sessionNo=CS...
  │  ── join {sessionNo:CS..., token:T1}  ──────────────▶ consult-service
  │                                                        校验 token→(CS, USER, U1)
  │                                                        绑定 channel1 ↔ CS
  │                                                        channel1 ∈ sessions[CS]
  │  ── message {sessionNo:CS, content:"咳嗽三天"}  ──────▶ 校验 channel1.sessionId==CS
  │                                                        seq=5 分配 → 落库 → 转发
  │  ◀─ message {seq:5, ...}（路由仅查 sessions[CS]）
```

隔离的三层防线（服务端最终校验，页面隐藏入口只是体验）：

1. **凭据层**：WS 建连必须携带会话令牌 `token`；token 由 `createSession`（用户）或 `doctor/join`（医生）签发，绑定 `(sessionId, role, participantId)`。令牌校验失败 → 拒绝建连/拒绝绑定（fail-closed）。
2. **归属层**：所有 REST（查会话/查消息/查处方/结束/撤销/消费）与 WS 帧，服务端校验请求方身份 = `session.userId` / `prescription.userId` / `session.doctorId`，不一致一律拒绝「无权访问该会话/处方」（NFR-10，AC-C3-5/AC-P2-9/AC-IM-1）。
3. **路由层**：消息路由只查**本会话**的 channel 集，结构上不存在跨会话 channel 引用，杜绝误投（M7 隔离违规 = 0）。

### 3.2 注册表结构（内存）

```java
// SessionRegistry（单例组件，仅在线连接态，不存业务状态）
class SessionRegistry {
    // 正向：会话 → 在线 channel 集（CopyOnWriteArraySet：读多写少，广播无锁遍历）
    ConcurrentMap<String, CopyOnWriteArraySet<Channel>> sessionChannels = new ConcurrentHashMap<>();
    // 反向：channel → 绑定的 sessionId（channel 关闭时 O(1) 反查清理）
    ConcurrentMap<Channel, String> channelSession = new ConcurrentHashMap<>();
    // 令牌 → 绑定信息（join 帧校验用）
    ConcurrentMap<String, TokenBinding> tokens = new ConcurrentHashMap<>();
    // 每会话在线参与者身份（角色去重 + 限 2 连接）：sessionId → 已占用槽位
    ConcurrentMap<String, CopyOnWriteArraySet<ParticipantSlot>> sessionSlots = new ConcurrentHashMap<>();
}

record TokenBinding(String sessionId, String role /*USER|DOCTOR*/, String participantId) {}
record ParticipantSlot(String role, String participantId) {}  // 每会话至多 {USER,U1} + {DOCTOR,demo-doctor}
```

- **Channel 归属**：`channel.attr(ATTR_SESSION).set(sessionId)` 冗余缓存，供事件循环内 O(1) 判断，避免每次查 Map。
- **为什么 CopyOnWriteArraySet**：广播是高频读（遍历全体在线 channel），写（join/leave）是低频。`CopyOnWriteArraySet` 读迭代无需锁，可在事件循环内安全遍历且不阻塞；代价是写时复制数组（join/leave 频繁的场景可换 `Collections.synchronizedSet`，见 ADR-IM-04 取舍说明）。本场景单会话最多 2 个 channel，写成本可忽略。
- **清理时机**：`channelInactive` → 从 `channelSession` 移除、从 `sessionChannels[sessionId]` 移除、从 `sessionSlots` 释放槽位；`token` 不随连接关闭删除（允许重连复用），仅随会话进入终态或显式注销时清除（防令牌堆积，见 §5.5）。

### 3.3 fail-closed 规则（穷举）

| 规则 | 行为 |
| --- | --- |
| channel 未绑定会话 | 服务端**不向该 channel 发任何消息**；除 `join`/`ping` 外的入站帧一律丢弃（不回 `error`，静默）。 |
| join 帧 token 无效/归属不符 | 拒绝绑定，关闭连接（或回 `error{TOKEN_INVALID}` 后关闭）；sessionNo 与 token 绑定 sessionId 不一致 → 拒绝。 |
| 入站帧 payload sessionId ≠ 绑定会话 | 丢弃该帧（路由只认 `channelSession[channel]`，payload 的 sessionNo 仅用于校验，不一致即丢）。 |
| 每会话连接上限 = 2（USER + DOCTOR） | join 时 `sessionSlots` 已满（2 槽均占用且在线）→ 拒绝（回 `error{OVER_CONNECTION_LIMIT}`）；同身份重复在线（如同一用户开两个 Tab）→ 拒绝，提示「该身份已在线」。 |
| 角色越权 | `prescription` 帧只允许 DOCTOR 槽位发送；USER 槽位发送 → 拒绝。 |
| 终态会话 | `ENDED` 会话拒绝 join / message / prescription / doctor-join，仅允许查历史（REST 只读）。 |
| 消息内容/大小 | `content` 空或 >500 字 → 拒绝（AC-C2-6）；WS 帧长上限 4KB（`WebSocketServerProtocolHandler` maxFramePayloadLength），超限由 Netty 关闭连接。 |

**隔离与路由的正确性论证**：任意时刻一条消息只可能由「已绑定会话 S 的 channel」发起；服务端先验归属（身份=参与者），再验帧归属（payload sessionId == S），再分配 seq 落库，最终**只向 `sessionChannels[S]` 广播**。由于注册表以 sessionId 为 key 组织，不存在任何跨会话 channel 引用的代码路径；篡改 payload 的 `sessionNo` 只会触发丢弃，无法把消息投到别的会话（M7 = 0）。

---

## 4. 问题 B：并发控制

### 4.1 线程模型与「绝不在事件循环内做阻塞 IO」

```
线程划分：
┌─ Netty 事件循环（boss 1 + worker = CPU 核数，可配置）──┐
│  channelRead0 / IdleStateHandler 事件                │
│  只做：反序列化 → 会话锁内校验/分配 seq → 入写队列(非阻塞) │
│        → 读 channel 转发（writeAndFlush 线程安全）      │
└─────────────────────────────────────────────────────┘
┌─ BatchWriteQueue 写线程（专用 1~2 线程，非事件循环）────┐
│  定时 drain 各会话队列 → 批量 INSERT → 完成 CompletableFuture │
└─────────────────────────────────────────────────────┘
┌─ REST / 定时调度线程（Spring 线程池 + @Scheduled）─────┐
│  createSession / doctor-join / end / prescribe / consume │
│  空闲超时扫描 / 断线缓冲超时 / 处方过期扫描              │
└─────────────────────────────────────────────────────┘
```

- **同 channel 串行化**：Netty 保证同一 channel 的事件（读、写、关闭）都投递到其绑定的**单一事件循环线程**，故单 channel 的收发天然串行、无重入竞态。
- **跨 channel 的会话内串行化**：双端 channel 可能挂在不同事件循环，靠**会话锁**（`SessionState.lock`，`ReentrantLock`）串行化所有裁决类操作（§4.3）。锁内只做内存操作与入队，不碰 DB → 锁持有时间纳秒级，不构成阻塞热点。
- **禁止阻塞 IO**：事件循环内绝不出现 JDBC/HTTP/锁等待/`Thread.sleep`；DB 一律经写队列异步，REST 触发的状态变更走 Spring 线程。`channel.writeAndFlush` 是线程安全的，从任意线程调用会被投递到目标 channel 的事件循环执行。

### 4.2 并发容器选型与同步边界

| 数据 | 容器/同步 | 取舍说明 |
| --- | --- | --- |
| `sessionChannels` | `ConcurrentHashMap` + 值 `CopyOnWriteArraySet<Channel>` | 广播读多、写少；COWAS 无锁遍历，事件循环内安全。写（join/leave）低频（每会话≤2），写复制成本可忽略。 |
| `channelSession` / `tokens` / `sessionSlots` | `ConcurrentHashMap` / COWAS | 高并发 get/put，无状态依赖，无需全局锁。 |
| 会话状态（内存视图） | `SessionState` 内 `ReentrantLock` | 保护「读状态→校验→分配 seq→入队」的复合操作，跨事件循环串行化。DB 为权威，内存视图仅供快速裁决与 seq 分配。 |
| seq 计数器 | `SessionState` 锁内递增 | 与 `lastSeq`（DB）对齐；服务重启后以 DB `MAX(seq)` 恢复起点。 |
| 写队列 | 每会话 `ConcurrentLinkedQueue<PendingWrite>` | 事件循环内 O(1) 入队，无锁、非阻塞；写线程 drain。 |

> **同步边界（关键原则）**：所有「读-判断-写」复合操作（发消息、接诊、开方、结束、断线/空闲判定、处方消费）必须落在**会话锁或 DB 原子语句**内；单个 Map get/put 天然原子不需要额外锁。禁止「先读 Map 再在外面改」的竞态写法。

### 4.3 状态流转 CAS（影响行数裁决）

状态变更一律走 DB 原子比对更新，`affectedRows = 1` 才算成功（并发冲突时输家立即感知并拒绝，无半状态）：

```sql
-- 接诊（WAITING → ONGOING）
UPDATE consultation_session SET status='ONGOING', doctor_id=:doctorId, joined_at=NOW()
 WHERE session_no=:no AND status='WAITING';
-- 主动结束（WAITING|ONGOING → ENDED）
UPDATE consultation_session SET status='ENDED', end_reason=:reason, ended_at=NOW()
 WHERE session_no=:no AND status IN ('WAITING','ONGOING');
-- 开方完成自动结束（ONGOING → ENDED, PRESCRIPTION_ISSUED，与处方 INSERT 同事务）
UPDATE consultation_session SET status='ENDED', end_reason='PRESCRIPTION_ISSUED', ended_at=NOW()
 WHERE session_no=:no AND status='ONGOING';
-- 处方一次性消费（ACTIVE → USED，全条件原子比对）
UPDATE prescription SET status='USED', consumed_order_no=:orderNo, used_at=NOW()
 WHERE no=:no AND status='ACTIVE' AND valid_until>NOW()
   AND user_id=:userId AND product_id=:productId AND quantity=:quantity;
-- 处方恢复（USED → ACTIVE，补偿）
UPDATE prescription SET status='ACTIVE', consumed_order_no=NULL, used_at=NULL
 WHERE no=:no AND status='USED';
-- 处方撤销（ACTIVE → REVOKED）
UPDATE prescription SET status='REVOKED', revoked_at=NOW(), revoke_reason=:reason
 WHERE no=:no AND status='ACTIVE';
```

- **双保险**：DB CAS 是唯一并发正确性边界；会话锁把同一会话内的冲突**先在内存消解**（避免无谓 DB 争用），但**绝不依赖内存判断作为最终裁决**（最终裁决永远在 DB）。
- **一致性示例（EX-IM-14/27）**：`结束` 与 `发消息` 并发 → 会话锁串行化：先结束时消息操作读到 `ENDED` 拒绝「会话已结束」；先消息时 seq 已分配落库，随后结束正常。结果确定、无半状态。

### 4.4 消息持久化：批量写队列（防事件循环阻塞）

**问题**：每条消息直接同步落库会阻塞事件循环；消息又是「高频小追加」。

**设计**（`BatchWriteQueue`）：

```
事件循环侧（入队，非阻塞）：
  message 帧 → 会话锁内校验/分配 seq → 封装 PendingWrite{msg, CompletableFuture<Void> done}
            → sessionQueue.offer(pending)   // ConcurrentLinkedQueue，O(1)，不阻塞
            → 返回（事件循环立即空闲）

写线程侧（drain，合并批量）：
  定时（默认每 50ms 或积压 ≥32 条）：
    for each 会话队列：drain 出本批次
    sessionQuery + chatMessageRepository 批量 INSERT（jdbc 批处理，按会话保持 FIFO）
    成功 → 依次完成本会话批次的 done 回调（顺序 = seq 顺序）→ 路由回调转发/ack
    失败 → 批次回退（重试 N 次）→ 仍失败 → 完成 done exceptionally → 记 ERROR + 会话标记 degraded

查询前 flush（读后写可见）：
  getMessages / 重连补齐 前调用 flush(sessionId)：
    同步 drain 该会话队列并 INSERT 落库，保证「先落库成功再返回/转发」的语义
    对外表现为可 await 的 CompletableFuture（REST 层 join() 后响应）

可 await 背压：
  单会话队列积压 > 阈值（默认 2000）→ 该会话标记 degraded：
    对新消息回 error{OVERLOADED} 并拒绝；对最慢 channel 发送背压提示
  （演示级单机，事件循环无阻塞；生产可扩展为水位线 + 丢弃策略/连接拒绝）
```

- **顺序性**：seq 在会话锁内分配；写线程按**会话 FIFO** drain，批量 INSERT 保持每会话内 seq 严格递增；转发回调按同一顺序执行（会话调度器 §4.5），保证接收端 seq 单调（M9 乱序率 = 0，AC-IM-2）。
- **不丢（M10/NFR-6）**：`done` 回调只有在 flush 提交成功后才触发；发送方收到 `ack` 即该消息已落库。断线重连后按「本地最大 seq+1」增量补拉（§9.4），历史无缺失。
- **幂等去重（clientMsgId）**：客户端每条消息携带 `clientMsgId`（UUID）；DB 唯一键 `(session_id, client_msg_id)`；重放同 clientMsgId → 返回重复 ack（不重新广播、不二次计 seq）。覆盖客户端「超时重发/断线重发」场景（AC-C2-5）。

### 4.5 会话调度器（每会话串行转发 + 心跳/超时）

- **会话调度器**：每会话一个轻量单线程执行器（`SessionDispatcher`），串行执行「转发到本会话各 channel」的写操作，保证跨事件循环的输出顺序与 seq 一致。实现可用每会话 `EventExecutor` 或 `scheduledExecutor` 提交串行任务。
- **心跳/探活**：`IdleStateHandler`（读空闲 30s）→ 触发 `ping` 探活；连续 2 个周期无任何读写 → 判定对端死连 → 关闭 channel → 进入断线缓冲（§5.2）。客户端每 15s 发 `ping`（PRD NFR-1：心跳 15s/次、心跳超时 30s）。
- **连接/速率/大小上限**：

| 项 | 上限 | 说明 |
| --- | --- | --- |
| 每会话在线连接 | 2（USER + DOCTOR） | 超限拒绝 join（§3.3） |
| 每身份在线连接 | 1（同 userId/doctorId） | 防多 Tab 抢占，重复 join 拒绝 |
| WS 帧长 | 4KB | `WebSocketServerProtocolHandler(maxFramePayloadLength=4096)` |
| 消息 content | ≤500 字 | PRD AC-C2-6 |
| join/消息速率 | 会话锁内节流：同会话 join 尝试 ≤5 次/30s；消息 ≤20 条/会话/秒 | 超速回 `error{RATE_LIMITED}` 或丢弃 |
| 写队列积压 | 每会话 ≤2000 条 | 超阈值 → degraded + 背压（§4.4） |
| 全局并发会话 | ≥50（演示量级） | NFR-2；单机 Netty，超出后拒绝新 join 并提示 |

---

## 5. 问题 C：会话结束判定

### 5.1 状态机（严格对齐 PRD §6 / §14.1）

```
                    医生接入（doctor/join）
        ┌───────────────────────────────────────────┐
        ▼                                           │
   ┌─────────┐    user 主动结束(USER_ENDED)     ┌─────────┐
   │ WAITING │ ───────────────────────────────▶ │  ENDED   │
   │ 等待接入 │ ◀── user 断线超 60s ────────────  │（终态）   │
   └─────────┘        (DISCONNECT_TIMEOUT)       │ 只读：    │
        │                                       │ 禁发消息  │
        │ 医生接入（CAS，仅 WAITING→ONGOING）     │ 禁开方    │
        ▼                                       │ 禁接诊    │
   ┌─────────┐   ① user/doctor 主动结束           │          │
   │ ONGOING │ ── (USER_ENDED / DOCTOR_ENDED) ─▶ │          │
   │ 进行中   │   ② 任一端断线超 60s 未重连          │          │
   └─────────┘ ── (DISCONNECT_TIMEOUT) ────────▶ │          │
        │       ③ 双方 30min 无文本消息             │          │
        │       (IDLE_TIMEOUT)                  │          │
        │       ④ 医生开方成功（同事务）             │          │
        └──── (PRESCRIPTION_ISSUED) ───────────▶ │          │
        └───────────────────────────────────────────┘
```

**转移矩阵（合法表）**：

| 当前状态 | 动作 | 目标（status + endReason） | 允许 | 备注 |
| --- | --- | --- | --- | --- |
| WAITING | 医生接入（doctor/join） | ONGOING | ✓ | CAS `WHERE status='WAITING'`；doctorId 写入 |
| WAITING | 用户主动结束 | ENDED(`USER_ENDED`) | ✓ | 医生未接入也能结束 |
| WAITING | 用户发消息 | —（留在 WAITING） | ✓ | 消息持久化，提示「等待医生接入」 |
| WAITING | 医生开方 | — | ✗ | 仅 ONGOING 可开方（AC-P1-4） |
| WAITING | 用户断线超 60s 未重连 | ENDED(`DISCONNECT_TIMEOUT`) | ✓ | 用户是 WAITING 唯一在线方 |
| WAITING | 医生未接入（长时间） | 不结束 | ✗ | **防误判**（AC-C4-6/EX-IM-10/16） |
| ONGOING | 用户/医生主动结束 | ENDED(`USER_ENDED`/`DOCTOR_ENDED`) | ✓ | CAS `WHERE status IN ('WAITING','ONGOING')` |
| ONGOING | 用户/医生发消息 | —（留在 ONGOING，刷新 lastActiveAt） | ✓ | 更新活动时间 |
| ONGOING | 医生开方 | ENDED(`PRESCRIPTION_ISSUED`) | ✓ | 与处方 INSERT 同事务（§6.3） |
| ONGOING | 空闲超时（30min 无文本） | ENDED(`IDLE_TIMEOUT`) | ✓ | 调度器扫描，仅 ONGOING 计时 |
| ONGOING | 任一端断线超 60s | ENDED(`DISCONNECT_TIMEOUT`) | ✓ | 断线缓冲（§5.2） |
| ENDED | 任意操作（发消息/开方/接诊/结束） | — | ✗ | 终态只读（AC-C4-8/AC-C3-6/AC-P1-3） |

**非法流转拦截**：状态变更集中在 `SessionStateMachine.transit(current, action)` 一张表集中裁决（R-06 建议落地），并在 CAS UPDATE 前置校验；`ENDED` 会话的任何入站业务帧在会话锁内直接拒绝「会话已结束，无法…」。

> **关于「终态(ENDED/EXPIRED/PRESCRIPTION_ISSUED)」评审口径**：会话只有一个终态 `ENDED`，五种结束方式通过 `endReason` 区分（含 `PRESCRIPTION_ISSUED`）；`EXPIRED` 不是会话状态而是处方状态（§6）。本表即 PRD §14.1 的精确实现。

### 5.2 判据 1·主动结束（用户/医生）

- 入口：REST `POST /api/sessions/{sessionNo}/end`（body 含 `operatorType: USER|DOCTOR`、`operatorId`）或 WS `end` 帧。
- 校验：`operatorId` 归属（USER=session.userId / DOCTOR=session.doctorId）；状态 WAITING（仅 USER）或 ONGOING（两者）。
- 执行：CAS → `ENDED(endReason=USER_ENDED|DOCTOR_ENDED)`；落一条 `SYSTEM` 消息「会话已结束」；向在线 channel 推 `session_ended` 帧；`channel.close()`（graceful）。
- 并发：与发消息/开方并发由会话锁串行（§4.3），先提交者胜。

### 5.3 判据 2·连接断开 + 判据 3·空闲超时（心跳探活）

**断线缓冲（判据 2）**：

```
channelInactive / 心跳判死 → 释放该 channel 槽位（§3.2 清理）
  → 会话在线方集合中移除该参与者（USER 或 DOCTOR）
  → 若该参与者是会话唯一在线方且 60s 内未重连：
      启动 60s 宽限定时器（ScheduledFuture）
      ├─ 60s 内重连（同身份 join 成功绑定）→ 取消定时器，会话继续
      │    重连成功后：服务端按「本地 lastSeq」向新 channel 补发离线期间消息，
      │    客户端也可走 REST 增量补拉（afterSeq），保证不丢不乱序（AC-C4-2/AC-IM-3）
      └─ 60s 未重连 → 定时器触发：CAS → ENDED(DISCONNECT_TIMEOUT)
           落 SYSTEM「对方已断线超时，会话已结束」；向仍在线的对端推 session_ended 并关闭
```

- **心跳探活（判据 3 的支撑）**：`IdleStateHandler` 读空闲 30s → `userEventTriggered(IdleStateEvent)` 发 `ping`；连续两个周期（约 60s）无任何数据 → 判死关闭，计入「断开」路径。心跳**不**刷新 `lastActiveAt`（PRD 明确，AC-C4-4 关键）。
- **关键区分**：断开的是「用户」还是「医生」都适用缓冲；但 `WAITING` 且医生从未接入时，没有医生 channel 可断，会话只可能因用户断线超时结束——医生「不在线/忙碌」**不会**结束 WAITING 会话（EX-IM-16）。

**空闲超时（判据 4，命名归入判据 3 目录以对齐 PRD 三判据+两判据合并叙述）**：

- 仅 `ONGOING` 会话，双方连续 **30 分钟无文本消息**（`lastActiveAt` 距今 > 30min）→ 调度器 CAS → `ENDED(IDLE_TIMEOUT)`，落 SYSTEM「会话因长时间无消息已结束」，推 `session_ended` 并关闭。
- `lastActiveAt` 只在**文本消息**成功落库时刷新（心跳、系统消息不计入）；`WAITING` 阶段不计时（扫描条件 `status='ONGOING'`）。

### 5.4 兜底调度器（防「无事件触达」的静默超时）

单实例 `SessionEndScheduler`（`@Scheduled` / `ScheduledExecutorService`，默认每 30s 一次）：

```
任务1 断线缓冲兜底：遍历「有参与者离线且宽限定时器已触发」的会话
      → 已过 60s 仍离线 → CAS → ENDED(DISCONNECT_TIMEOUT)（防定时器丢失）
任务2 空闲超时扫描：SELECT session_no FROM consultation_session
      WHERE status='ONGOING' AND last_active_at < NOW() - 30min
      → 逐会话 CAS → ENDED(IDLE_TIMEOUT)（跳过已终态，保证不误判/不重复）
任务3 处方过期扫描：SELECT no FROM prescription WHERE status='ACTIVE' AND valid_until < NOW()
      → 批量 CAS → EXPIRED（与「查询/消费时懒判定」双保险，§6.5）
```

- 所有扫描用 CAS，与并发操作（发消息/开方/主动结束）碰撞时输家自动跳过，不会重复结束或误判（M3 会话误判结束率 = 0、AC-IM-4）。
- 调度器触发的结束一律走同一状态机 + 会话锁，与主动结束完全同构。

### 5.5 会话结束后的清理

| 动作 | 内容 |
| --- | --- |
| DB | 状态 `ENDED` + `endReason` + `ended_at`；`SYSTEM` 结束消息落库（可查历史） |
| 注册表 | 关闭在线 channel；清理 `tokens` 中该会话的令牌（防令牌堆积与复用） |
| 写队列 | drain 残留消息落库后完成（不丢历史） |
| 可下单窗口 | 与会话结束**无关**：处方 `validUntil` 内仍可下单（PRD 明确，AC-C4-7） |
| 追问 | 需新建会话（新会话与旧处方独立） |

---

## 6. 问题 D：处方生命周期与安全

### 6.1 处方实体与状态机

**实体字段**（`prescription` 表，§8.3）：

| 字段 | 说明 |
| --- | --- |
| `no` | 处方号：`RX + yyyyMMddHHmmss + 4 位随机`，DB 唯一索引兜底（FR-P1） |
| `sessionId / userId / doctorId / productId` | 归属快照：会话/用户/医生/目标药品，开方时落库 |
| `productName` | 药品名快照（展示用） |
| `quantity` | 数量（下单数量必须一致，EX-IM-6） |
| `dosage / frequency / usage` | 单次用量 / 每日次数 / 用法（必填，EX-IM-24/AC-P1-5） |
| `status` | `ACTIVE / USED / EXPIRED / REVOKED` |
| `validUntil` | 有效期截止（默认开方 + 7 天） |
| `issuedAt / consumedOrderNo / usedAt / revokedAt / revokeReason` | 生命周期时间戳 |

> PRD 中处方为「**单一目标药品 + 用法用量**」模型（处方药品 = 会话目标药品）；「items」语义落为单药品字段。多药品组合处方留作扩展（见 R-11）。

**状态机（严格对齐 PRD §6 / §14.2）**：

```
                下单原子消费（单条 CAS UPDATE）
   ┌──────────────────────────────────────────────┐
   ▼                                              │
┌──────────┐  now > validUntil（懒判定 + 定时扫描） ┌─────────┐
│  ACTIVE  │ ───────────────────────────────────▶ │ EXPIRED │（终态）
│   有效    │   ┌─────────────────────────────────▶ │         │
└──────────┘   │ 医生/用户撤销（未使用）              └─────────┘
   │           │  ┌─────────────────────────────┐  ┌─────────┐
   └───────────┼──▶  REVOKED（终态）             │  │  USED   │（终态）
               └───────────────────────────────┘  └─────────┘
```

| 当前状态 | 动作 | 目标 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| ACTIVE | 下单消费（consume） | USED | ✓ | 单条 CAS 原子比对，一次性（§6.4） |
| ACTIVE | now > validUntil | EXPIRED | ✓ | 查询/消费时懒判定落库 + 定时扫描兜底（§6.5） |
| ACTIVE | 医生/用户撤销 | REVOKED | ✓ | 开方医生或所属用户，CAS `WHERE status='ACTIVE'` |
| USED | 再消费 | — | ✗ | 「处方已使用，请重新问诊开具处方」 |
| EXPIRED | 再消费 | — | ✗ | 「处方已过期」 |
| REVOKED | 再消费 | — | ✗ | 「处方已撤销，无法使用」 |
| USED/EXPIRED/REVOKED | 撤销/恢复 | — | ✗ | 终态不可逆 |

### 6.2 医生开方（仅限本人已接管的 ONGOING 会话）

- 前置（fail-closed 校验链）：会话存在 → `status == ONGOING`（AC-P1-4）→ 操作者 `doctorId == session.doctorId`（AC-P1-6，医生只能给自己接管的会话开方）→ 会话未结束。
- 参数校验：`quantity` > 0、`dosage/frequency/usage` 非空（EX-IM-24/AC-P1-5）、`validDays` 合法（默认 7）。
- 处方药品 = `session.productId`（本会话目标药品，不可跨会话开方，FR-P1）。
- 执行：**同一本地事务**内「INSERT prescription(ACTIVE, validUntil=now+validDays)」+「CAS 会话 ONGOING→ENDED(PRESCRIPTION_ISSUED)」。同事务保证开方成功 ⇔ 会话自动结束，无半状态（AC-P1-2）。
- 事后：落 SYSTEM「医生已开具处方 RXxxx，有效期至 XXXX」（AC-C2-7）；向在线 channel 推 `prescription_issued` + `session_ended`；清理注册表（§5.5）。
- 开方后：会话进入终态，可下单窗口由处方 `validUntil` 决定（与会话结束无关，AC-C4-7/AC-P1-2）。

### 6.3 处方消费（原子一次性，fail-closed 校验链）

`consume` 接口（`POST /api/prescriptions/{no}/consume {userId, productId, quantity}`）实现「存在 → ACTIVE → 未过期 → 归属 → 药品匹配 → 数量一致 → 置 USED」校验链：

```
第 0 步（快速失败，友好文案）：
  exists?      不存在 / 伪造        → "处方不存在或无效"（EX-IM-5）
  status?      USED                → "处方已使用，请重新问诊开具处方"（EX-IM-2）
               REVOKED             → "处方已撤销，无法使用"（EX-IM-7）
               EXPIRED             → "处方已过期"（EX-IM-3，懒判定已落库）
  validUntil?  now > validUntil    → 懒置 EXPIRED 后 → "处方已过期"（EX-IM-3）
  userId?      ≠ prescription.userId → "无权使用该处方"（EX-IM-8）
  productId?   ≠ 处方药品          → "处方药品与购买药品不符"（EX-IM-4）
  quantity?    ≠ 处方数量          → "下单数量必须与处方开具数量一致"（EX-IM-6）

第 1 步（并发唯一边界，单条 CAS）：
  UPDATE prescription SET status='USED', consumed_order_no=:orderNo, used_at=NOW()
   WHERE no=:no AND status='ACTIVE' AND valid_until>NOW()
     AND user_id=:userId AND product_id=:productId AND quantity=:quantity;
  affectedRows = 1 → 消费成功（USED，终态）
  affectedRows = 0 → 并发冲突/状态已变：按最新状态重新走第 0 步给出精确拒绝原因
```

- **为什么第 0 步也要**：`affectedRows=0` 只能说明「条件不满足」，无法区分具体原因；第 0 步把每种非法原因映射为 PRD 指定的精确文案（用户体验），第 1 步 CAS 才是并发正确性裁决。两步配合：单请求正确性靠第 1 步，错误语义靠第 0 步。
- **并发防复用**：同一处方两个订单并发消费 → 两条 UPDATE 竞争同一行，DB 行锁串行化，**仅一条 `affectedRows=1`**，另一条重查后返回「处方已使用」（EX-IM-26/AC-P2-10，复用率 = 0）。

### 6.4 补偿与过期判定

- **补偿恢复**：`POST /api/prescriptions/{no}/restore`（order-service 在「消费成功但本地落单失败」时调用）：CAS `USED → ACTIVE`（清空 consumedOrderNo/usedAt）。best-effort，失败留 ERROR 日志人工对账（EX-IM-22/AC-P2-11）。配合 order 侧「回补库存」双补偿（§9.2）。
- **过期判定**：`ACTIVE → EXPIRED` 采用**懒判定 + 定时扫描**双保险：
  - 懒判定：查询「我的处方」/消费时，若 `now > validUntil` 先 CAS 置 EXPIRED 再返回（AC-P3-4/EX-IM-3）。
  - 定时扫描：调度器每日（或每 30s 演示环境）批量 CAS 兜底（§5.4 任务 3）。
- **撤销**：`POST /api/prescriptions/{no}/revoke {operatorType, operatorId}`：仅 `ACTIVE`，且操作者为开方医生或所属用户；CAS `ACTIVE → REVOKED`（AC-P3-3/FR-P3）。

### 6.5 与订单的集成（fail-closed 边界）

`order-service.OrderService.createOrder(productId, quantity, ..., prescriptionNo)`：

```
① 拉取商品 product（含 rxFlag）
② if rxFlag == true:
      prescriptionNo 为空        → 拒绝"处方药需先在线问诊开具处方"（EX-IM-1/AC-D1-4）
      ConsultClient.consumePrescription(no, userId, productId, quantity)
        → 失败：把 consult 返回的精确 message 透传给用户（EX-IM-2~8/AC-P2-3~9）
        → 成功：处方已 USED
③ productClient.deductStock（原子扣库存，M1 既有）
④ 本地落单（CREATED，记录 prescriptionNo 快照）→ 成功返回
⑤ 本地落单失败：
      try 回补库存（restoreStock）
      try 恢复处方（ConsultClient.restorePrescription，USED→ACTIVE）
      均失败 → ERROR 日志留人工对账（EX-IM-22/AC-P2-11）
⑥ consult-service 不可用：
      ConsultClient 抛"问诊服务不可用，暂无法凭处方下单" → Rx 下单被拒、不扣库存不落单（AC-IM-6）
      OTC 商品跳过②，不受影响（AC-P2-12）
```

- 订单 `prescriptionNo` 仅作快照与展示；处方状态权威在 consult（一单一处修改入口）。
- 页面侧：确认订单页 `quantity` 锁定 = 处方数量、`prescriptionNo` 预填不可改（AC-P2-2）。

---

## 7. API 契约

### 7.0 通用约定

- REST 错误体统一 `{"message":"..."}`，HTTP `400`（业务）/ `500`（内部）；`ClientSupport.toBusinessException` 跨服务透传 message。
- WS 错误帧 `{"type":"error","code":"...","message":"..."}`；WS 业务帧见 §7.3。
- 状态枚举 JSON 传 `name`，中文文案走 DTO `statusLabel`。
- 幂等性标注：⭐ 天然幂等/已做幂等；❌ 非幂等，调用方不得自动重试。

### 7.1 product-service（:8081，变更）

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/products` | — | `ProductDTO[]`（新增 `rxFlag`） | 500 | ⭐ |
| GET | `/api/products/{id}` | — | `ProductDTO` | 400 商品不存在 | ⭐ |
| POST | `/api/products/{id}/stock/deduct` | query `quantity` | `ProductDTO` | 400 数量非法/库存不足 | ❌（调用方补偿） |
| POST | `/api/products/{id}/stock/restore` | query `quantity` | `ProductDTO` | 400 | ❌ |

`ProductDTO` 新增字段：
```json
{ "id": 1, "name": "阿莫西林胶囊", "category": "药品", "rxFlag": true,
  "price": 12.50, "stock": 200, "imageUrl": "💊", "...": "..." }
```

### 7.2 consult-service REST（:8085）

**A. 会话**

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions` | body `{userId, productId}` | `CreateSessionResponse{sessionNo,status,token,productId,userId,createdAt}` | 400 药品不存在/非 Rx 药品无需问诊；500 | ⭐ **复用幂等**：同用户同药品存在未结束会话 → 直接返回（复用）并**重发 token** |
| GET | `/api/sessions/{sessionNo}` | query `userId` | `SessionDTO` | 400 会话不存在/无权访问 | ⭐ |
| GET | `/api/sessions/user/{userId}` | — | `SessionDTO[]`（我的问诊，创建时间倒序） | 500 | ⭐ |
| GET | `/api/sessions/doctor/waiting?doctorId=` | — | `SessionDTO[]`（待接诊 WAITING 列表） | 500 | ⭐ |
| GET | `/api/sessions/{sessionNo}/messages` | query `userId`、`afterSeq`(可选) | `ChatMessageDTO[]`（seq 升序；`afterSeq` 为增量补拉起点） | 400 无权访问 | ⭐ |
| GET | `/api/sessions/{sessionNo}/ws-token` | query `userId`、`operatorType` | `{sessionNo, token}`（重新签发/复用 token） | 400 无权访问 | ⭐ |
| POST | `/api/sessions/{sessionNo}/doctor/join` | body `{doctorId}` | `SessionDTO`（WAITING→ONGOING，响应含医生 token） | 400 会话不存在/已结束/状态机非法；500 | ❌（重试由状态机拒绝，幂等安全） |
| POST | `/api/sessions/{sessionNo}/end` | body `{operatorType, operatorId}` | `SessionDTO`（ENDED） | 400 无权访问/已结束/状态机非法 | ⭐ **幂等**：已 ENDED 直接返回 |

`SessionDTO`：
```json
{ "sessionNo": "CS202608071012345678", "status": "ONGOING", "statusLabel": "进行中",
  "userId": "u-123", "doctorId": "demo-doctor",
  "productId": 5, "productName": "阿莫西林胶囊",
  "lastActiveAt": "2026-08-07T10:20:00", "createdAt": "2026-08-07T10:12:34",
  "joinedAt": "2026-08-07T10:15:00", "endedAt": null,
  "endReason": null, "lastSeq": 12 }
```

**B. 处方**

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/prescriptions` | body `{sessionNo, doctorId, quantity, dosage, frequency, usage, validDays}` | `PrescriptionDTO`（ACTIVE） | 400 会话不存在/非 ONGOING/非本会话医生/用法用量缺失/数量非法；500 | ❌（同会话重复开方被状态机拒绝，AC-P1-2） |
| POST | `/api/prescriptions/{no}/consume` | body `{userId, productId, quantity}` | `PrescriptionDTO`（USED） | 400 按 EX-IM-2~8 精确文案；500 | ❌（消费是一次性副作用；并发由 CAS 兜底，重试返回「已使用」） |
| POST | `/api/prescriptions/{no}/restore` | body（空） | `PrescriptionDTO`（ACTIVE） | 400 非 USED；500 | ❌（补偿操作，由 order 显式驱动） |
| GET | `/api/prescriptions/user/{userId}` | — | `PrescriptionDTO[]`（我的处方，实时判定过期） | 500 | ⭐ |
| GET | `/api/prescriptions/{no}` | query `userId` | `PrescriptionDTO` | 400 不存在/无权访问 | ⭐ |
| POST | `/api/prescriptions/{no}/revoke` | body `{operatorType, operatorId}` | `PrescriptionDTO`（REVOKED） | 400 无权/非 ACTIVE；500 | ⭐ **幂等**：已 REVOKED 直接返回 |

`PrescriptionDTO`：
```json
{ "no": "RX202608071015000042", "status": "ACTIVE", "statusLabel": "有效",
  "sessionNo": "CS...", "userId": "u-123", "doctorId": "demo-doctor",
  "productId": 5, "productName": "阿莫西林胶囊",
  "quantity": 1, "dosage": "0.5g", "frequency": "每日 3 次", "usage": "口服",
  "issuedAt": "2026-08-07T10:15:00", "validUntil": "2026-08-14T10:15:00",
  "consumedOrderNo": null, "usedAt": null }
```

### 7.3 WS 帧协议（Netty :8086，`/ws/chat`）

**连接**：`ws://localhost:8086/ws/chat`（query 携带 `token` 做握手级预校验；正式绑定以 `join` 帧为准）。

**帧为 UTF-8 文本 JSON，统一含 `type`。**

**客户端 → 服务端**：

| type | 字段 | 说明 |
| --- | --- | --- |
| `join` | `{type,sessionNo,token}` | 绑定 channel↔session；服务端校验 token 绑定与 sessionNo 一致、身份/角色、连接上限。成功后回 `joined`。 |
| `message` | `{type,sessionNo,clientMsgId,content}` | 发消息（USER/DOCTOR 均可）。`content` ≤500 字；`clientMsgId` 幂等键。成功回 `ack`，随后向会话内（含本人回显）广播 `message`。 |
| `prescription` | `{type,sessionNo,quantity,dosage,frequency,usage,validDays}` | **仅 DOCTOR**。开方成功后广播 `prescription_issued` + `system` + `session_ended`。 |
| `end` | `{type,sessionNo}` | 主动结束（USER/DOCTOR）。成功后广播 `system` + `session_ended`，服务端关闭连接。 |
| `ping` | `{type:"ping"}` | 心跳（客户端每 15s）；服务端回 `pong`。 |

**服务端 → 客户端**：

| type | 字段 | 说明 |
| --- | --- | --- |
| `joined` | `{type,sessionNo,role,status,lastSeq}` | join 成功；`lastSeq`=当前已持久化最大 seq，供增量补齐起点。 |
| `message` | `{type,sessionNo,seq,senderType,senderId,content,createdAt}` | 实时消息（含本人回显）。`seq` 会话内单调。 |
| `system` | `{type,sessionNo,seq,content,createdAt}` | 系统消息（医生接入/会话结束/开方等），同 `message` 一样落库。 |
| `prescription_issued` | `{type,sessionNo,prescriptionNo,productId,quantity,validUntil}` | 开方成功通知。 |
| `session_ended` | `{type,sessionNo,endReason,endedAt}` | 会话已结束（含自动结束）。 |
| `ack` | `{type,clientMsgId,seq,createdAt}` | 消息**落库成功**后的确认（重复 clientMsgId 返回同 seq，不重广播）。 |
| `pong` | `{type:"pong"}` | 心跳应答。 |
| `error` | `{type,code,message}` | 错误帧。code ∈ `TOKEN_INVALID / SESSION_NOT_FOUND / SESSION_CLOSED / OVER_CONNECTION_LIMIT / NOT_AUTHORIZED / MESSAGE_TOO_LONG / RATE_LIMITED / OVERLOADED`。 |

**帧错误码 → PRD 文案映射**：`SESSION_CLOSED` → 「会话已结束，无法发送消息」；`NOT_AUTHORIZED` → 「无权访问该会话」；`MESSAGE_TOO_LONG` → 「消息内容过长」。

### 7.4 order-service（:8082，新增页面 + 表单字段）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/consult/{sessionNo}` | 问诊聊天页（渲染会话 + 历史，注入 WS token，JS 连 ws://localhost:8086/ws/chat） |
| GET | `/consults` | 我的问诊（该用户全部会话与状态，BFF 代理 consult `/user/{userId}`） |
| GET | `/consults/doctor` | 医生控制台（待接诊 WAITING 列表 + 本人 ONGOING 会话；接诊/回复/开方/结束入口） |
| GET | `/prescriptions` | 我的处方（状态 + 「凭此处方购买 / 作废处方」） |
| POST | `/consult/{sessionNo}/doctor/join` | 医生接诊（→ ConsultClient） |
| POST | `/consult/{sessionNo}/end` | 主动结束（USER/DOCTOR，→ ConsultClient） |
| POST | `/consult/{sessionNo}/prescriptions` | 医生开方（→ ConsultClient） |
| POST | `/prescriptions/{no}/revoke` | 撤销处方（→ ConsultClient） |
| GET | `/order/confirm` | 支持 `prescriptionNo` 参数：Rx 且带处方时 `quantity` 锁定为处方数量、处方号预填（AC-P2-2） |
| POST | `/order` | `OrderForm` 新增可选 `prescriptionNo`（`@Size` 校验）；Rx 必填（服务端校验 EX-IM-1） |

---

## 8. 数据库设计

### 8.1 总览

- 新增 schema `shopdb_consult`（`init.sql` 建库授权，utf8mb4/utf8mb4_unicode_ci）；三张表由 consult-service `ddl-auto=update` 建（**生产建议收敛为迁移工具，见 R-02**）。
- product/order 表变更：`product` 增 `rx_flag` 列；`shop_order` 增 `prescription_no` 列。

### 8.2 `shopdb_consult.consultation_session`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 主键 |
| session_no | VARCHAR(32) | NOT NULL, **UK** | 会话号 `CS + yyyyMMddHHmmss + 4随机` |
| user_id | VARCHAR(64) | NOT NULL | 发起用户（演示身份） |
| doctor_id | VARCHAR(64) | — | 医生（join 时写入，固定 `demo-doctor`） |
| product_id | BIGINT | NOT NULL | 目标药品 |
| product_name | VARCHAR(100) | NOT NULL | 药品名快照 |
| status | VARCHAR(20) | NOT NULL | `WAITING/ONGOING/ENDED` |
| end_reason | VARCHAR(30) | — | `USER_ENDED/DOCTOR_ENDED/DISCONNECT_TIMEOUT/IDLE_TIMEOUT/PRESCRIPTION_ISSUED` |
| last_active_at | DATETIME | NOT NULL | 最近文本消息时间（空闲超时依据；心跳不更新） |
| last_seq | INT | NOT NULL DEFAULT 0 | 已持久化最大 seq（重连恢复/增量起点） |
| created_at | DATETIME | NOT NULL | 创建时间 |
| joined_at | DATETIME | — | 医生接入时间 |
| ended_at | DATETIME | — | 结束时间 |

- 索引：`UK(session_no)`；`IDX(user_id, product_id)`（复用判定：同用户同药品未结束会话）；`IDX(user_id)`（我的问诊）；`IDX(doctor_id, status)`（医生待接诊/处理中）；`IDX(status, last_active_at)`（空闲超时扫描、断线判定扫描）。
- **并发要点**：状态变更全部走 §4.3 的 CAS UPDATE，依赖行锁串行化；`last_seq` 以 `MAX(seq)` 恢复。

### 8.3 `shopdb_consult.chat_message`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 主键 |
| session_id | BIGINT | NOT NULL | 逻辑外键 → consultation_session.id |
| seq | INT | NOT NULL | 会话内单调序号 |
| sender_type | VARCHAR(10) | NOT NULL | `USER/DOCTOR/SYSTEM` |
| sender_id | VARCHAR(64) | — | 发送方 id（SYSTEM 为空） |
| content | VARCHAR(1000) | NOT NULL | 文本内容（业务上限 500 字） |
| client_msg_id | VARCHAR(64) | — | 客户端幂等键（可空） |
| created_at | DATETIME | NOT NULL | 时间 |

- 索引：`UK(session_id, seq)`（顺序唯一，防乱序重复）；`UK(session_id, client_msg_id)`（幂等去重，MySQL 唯一索引允许多个 NULL）；`IDX(session_id)`（历史查询按 seq 升序走覆盖索引）。
- 批量写：`BatchWriteQueue` 按 session_id 分组批量 INSERT（§4.4）。

### 8.4 `shopdb_consult.prescription`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 主键 |
| no | VARCHAR(32) | NOT NULL, **UK** | 处方号 `RX + yyyyMMddHHmmss + 4随机` |
| session_id | BIGINT | NOT NULL | 来源会话 |
| user_id | VARCHAR(64) | NOT NULL | 归属用户 |
| doctor_id | VARCHAR(64) | NOT NULL | 开方医生 |
| product_id | BIGINT | NOT NULL | 药品 |
| product_name | VARCHAR(100) | NOT NULL | 药品名快照 |
| quantity | INT | NOT NULL | 数量 |
| dosage | VARCHAR(50) | NOT NULL | 单次用量 |
| frequency | VARCHAR(50) | NOT NULL | 每日次数 |
| usage | VARCHAR(50) | NOT NULL | 用法 |
| status | VARCHAR(20) | NOT NULL | `ACTIVE/USED/EXPIRED/REVOKED` |
| valid_until | DATETIME | NOT NULL | 有效期截止 |
| issued_at | DATETIME | NOT NULL | 开方时间 |
| consumed_order_no | VARCHAR(32) | — | 消费订单号（USED 时回写） |
| used_at | DATETIME | — | 消费时间 |
| revoked_at | DATETIME | — | 撤销时间 |
| revoke_reason | VARCHAR(100) | — | 撤销原因 |

- 索引：`UK(no)`；`IDX(user_id, status)`（我的处方）；`IDX(status, valid_until)`（过期扫描）；`IDX(session_id)`（按会话查处方）。
- **并发要点**：消费/恢复/撤销/过期全走 §4.3 单条 CAS UPDATE，行锁串行化，防复用唯一边界（EX-IM-26）。

### 8.5 现有表变更

| 服务 | 表 | 变更 | 说明 |
| --- | --- | --- | --- |
| product | `product` | + `rx_flag`（BOOLEAN NOT NULL DEFAULT FALSE） | Rx 标记，种子回填默认 false |
| order | `shop_order` | + `prescription_no`（VARCHAR(32) NULL） | Rx 订单处方号快照 |

### 8.6 `init.sql` 变更

```sql
CREATE DATABASE IF NOT EXISTS `shopdb_consult` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON `shopdb_consult`.* TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_consult`.* TO 'shop'@'127.0.0.1';
```

### 8.7 金额/命名约定

- 新增无金额字段；沿用 DECIMAL(12,2) 约定（不涉及）。表命名沿用 M1「业务英文名」风格（`consultation_session/chat_message/prescription`），R-04 统一前缀建议继续适用。

---

## 9. 与现有服务集成 / 页面 / 容错降级

### 9.1 product-service（药品品类 + Rx 标记）

- `Product` 增 `rxFlag`（`@Column(name="rx_flag") boolean`，默认 false）；`ProductDTO` 同步新增（mall-common）；`ProductService` 实体→DTO 映射带上。
- `DataInitializer`：`SeedProduct` record 增 `rxFlag`；新增「药品」品类种子：
  - **OTC**（`rxFlag=false`）：布洛芬缓释胶囊、维生素C 泡腾片、健胃消食片、氯雷他定片、板蓝根颗粒。
  - **Rx**（`rxFlag=true`）：阿莫西林胶囊、硝苯地平缓释片（降压药）、复方氨酚烷胺片（复方感冒药）、头孢克肟分散片。
  - upsert 逻辑：新名插入（含 rxFlag）；老库已存在商品若 `rx_flag` 为空 → 回填默认 false（**不覆盖价格/库存**，AC-D1-5）；药品条目按名 upsert 幂等。

### 9.2 order-service（Rx 校验 + 处方消费 + 页面）

- `OrderForm` 增 `prescriptionNo`（可选，`@Size`）；`Order` 增 `prescriptionNo`（快照）。
- `createOrder` 增加 `prescriptionNo` 参数并按 §6.5 编排（Rx：消费处方 → 扣库存 → 落单 → 失败双补偿）。
- `ShopController.confirmOrder` 在 `quantity>stock` 校验前增加 Rx 拦截（Rx 且无处方 → 抛「处方药需先在线问诊开具处方」）；`createOrder` 透传 `prescriptionNo`；确认订单页 `prescriptionNo` 传入时 quantity 锁定。
- 新增 `ConsultClient`（与 `ProductClient` 同模式：`RestClient` + `ClientSupport.toBusinessException`，超时 connect 3s / read 5s，`@Value("${mall.consult-service.url}")`）。所有 consult 调用失败 → 转为 BusinessException（Rx 下单 fail-closed）。
- 页面路由：§7.4 新增问诊聊天/我的问诊/医生控制台/我的处方。

### 9.3 页面与 JS WS 客户端

- **问诊聊天页 `/consult/{sessionNo}`**：
  - BFF 渲染会话信息与历史（`GET /api/sessions/{sessionNo}/messages`）；页面持有 `{sessionNo, token}`（token 经 `ws-token` 接口签发，放 JS 变量/sessionStorage）。
  - JS 建立 `ws://localhost:8086/ws/chat` → 发 `join` → 收 `joined` → 拉历史（首屏全量，重连 `afterSeq=lastSeq` 增量）→ 收发 `message` / `system` / `prescription_issued` / `session_ended`。
  - **心跳**：每 15s `ping`；断线自动重连（指数退避，1s→2s→4s…上限 30s），重连后 `afterSeq` 增量补齐（AC-C2-5/AC-IM-3）。
  - 交互：用户可 `end` 主动结束；`ENDED` 会话只读；收到 `prescription_issued` 显示处方号与有效期，提供「凭处方购买」。
- **医生控制台页 `/consults/doctor`**：待接诊（WAITING）列表 → 点「接诊」（`doctor/join`，返回医生 token）→ 会话转 ONGOING；同一页面可回复消息、点「开具处方」填表（quantity/dosage/frequency/usage/validDays）→ `prescription` 帧或 REST；可 `end` 结束、`revoke` 撤销未用处方。
- **我的问诊 `/consults`** / **我的处方 `/prescriptions`**：列表 + 状态；ACTIVE 处方「凭此处方购买 / 作废处方」，其余仅展示（AC-P3-5）。
- **demo 身份**：`userId` localStorage/Cookie 首次生成；页面所有 consult 请求携带。

### 9.4 容错与降级

| 场景 | 处理 | 效果 |
| --- | --- | --- |
| consult-service 停机 | `ConsultClient` 抛「问诊服务不可用」；Rx 下单被拒且不产生脏数据（fail-closed）；问诊页/我的问诊显示降级提示 + 重试；OTC 下单不受影响 | AC-IM-6，页面不白屏 |
| WS 断线 | JS 自动重连（退避）+ `afterSeq` 增量补齐；服务端 60s 缓冲期内重连会话继续 | 消息不丢不乱序 |
| 批量写队列失败 | 批次重试 N 次；仍失败 → 完成 `done` exceptionally → 对发送方回 error + 记 ERROR；会话标记 degraded，后续消息背压提示 | 不丢已 ack 消息；可观测 |
| 消息补传 | 客户端断线期间消息已落库（先落库后 ack/转发），重连按 seq 补拉 | M10 不丢 = 0 |
| 会话自动结束（断线/空闲/开方） | 状态机 + 调度器 CAS 兜底；向在线端推 `session_ended`；历史仍可查 | 判定确定、可测 |
| 处方消费后本地落单失败 | 回补库存 + 恢复处方 ACTIVE（双补偿）；失败留日志人工对账 | EX-IM-22 无悬挂处方 |
| DeepSeek 推荐 Rx 药品 | `rxFlag=true` 硬过滤出候选池 | 推荐不出 Rx |
| Netty 服务异常 | 优雅停机（拒绝新连接→drain→shutdown）；Spring 侧 REST 仍可用 | 降级而不崩 |

---

## 10. 测试策略（研发视角）

按「单元 → 接口 → Netty 集成 → 功能」金字塔；**每条 PRD 验收/异常场景必须有对应用例**（矩阵见 §11）。

### 10.1 consult-service 单元测试（JUnit5 + Mockito，纯内存/H2）

| 被测类 | 覆盖重点 |
| --- | --- |
| `SessionStateMachine` | 转移矩阵全表（§5.1）：合法流转成功、非法流转（ENDED 发消息/开方/接诊、WAITING 开方、ONGOING 重复开方）全部拒绝；WAITING 不因医生未接入结束；空闲计时仅 ONGOING。 |
| `SessionService`（建会话/复用/接诊/结束） | 复用幂等（同用户同药品未结束返回同一会话）；非 Rx 药品建会话拒绝（EX-IM-23）；医生非本会话拒绝；接诊/结束 CAS 影响行数分支。 |
| `PrescriptionService` | 校验链每条文案（EX-IM-1~8）：不存在/伪造、USED、EXPIRED（含懒判定落库）、REVOKED、归属不符、药品不符、数量不符；consume 单条 CAS 成功/并发输家；restore（USED→ACTIVE）；revoke 仅 ACTIVE 且归属合法；开方前置（ONGOING + 本会话医生 + 用法用量非空）。 |
| `SessionRegistry` | join 绑定/解绑；channel 未绑定不发消息（fail-closed）；入站 payload 与绑定会话不符丢弃；每会话 2 连接上限、同身份重复在线拒绝；token 失效拒绝；channelInactive 槽位释放。 |
| `BatchWriteQueue` | drain 合并批量；flush 后查询可见（读后写一致）；`done` 在提交后完成（先落库后 ack）；积压超阈值 → 背压/拒绝；失败重试与 exceptionally。 |
| `SessionEndScheduler` | 空闲超时扫描（仅 ONGOING）；断线缓冲兜底；处方过期扫描；CAS 幂等（重复触发不重复结束）。 |

### 10.2 consult-service 接口测试（MockMvc / REST）

- `POST /api/sessions`（成功、复用、非 Rx 拒绝 400）；`GET /api/sessions/{no}`（无权访问 400）；`GET .../messages`（seq 升序、afterSeq 增量、越权 400）；`doctor/join`（成功、WAITING 重复接诊被拒、ENDED 接诊 400）；`end`（USER/DOCTOR 成功、越权 400、已 ENDED 幂等）。
- 处方接口：`POST /api/prescriptions`（成功 ACTIVE + 会话自动 ENDED(PRESCRIPTION_ISSUED)；WAITING 开方 400；非本会话医生 400；用法用量缺失 400）；`consume`（成功 USED、各异常文案精确断言）；`restore`（USED→ACTIVE）；`revoke`；`GET /user/{userId}` 实时过期判定。
- 断言重点：HTTP 状态码、`{"message"}` 精确文案、幂等性（重复请求副作用为零）。

### 10.3 **Netty 集成测试（真实启动 Netty 随机端口 + JDK HttpClient WebSocket）**

用 `java.net.http.HttpClient.newWebSocketBuilder()` 模拟浏览器与医生端，**真实起 Netty**（测试随机端口，`consult.ws.port=0` 取实际绑定端口），覆盖：

| 用例 | 断言 |
| --- | --- |
| 会话隔离 | 用户 A 与用户 B 各建会话，A 发消息不达 B 的 channel；A 篡改 payload sessionNo 为 B 的 → 帧被丢弃；越权访问他人会话 REST → 400。 |
| 并发不乱序 | 用户 + 医生各并发发 10 条 → 双端各自收到的 20 条 seq 严格单调（AC-IM-2）；落库后 `getMessages` 顺序一致。 |
| 先落库后转发 | 发送方收 `ack` 时消息已可被 `getMessages` 查到（flush 语义，AC-C2-1/M10）。 |
| 断线缓冲与补齐 | 用户关闭连接 30s 内重连（测试用短 grace，如 2s），期间医生发 2 条 → 重连后按 seq 补齐、会话未结束（AC-C4-2）；超 grace 未重连 → 会话 ENDED(DISCONNECT_TIMEOUT)（AC-C4-3）。 |
| 空闲超时 | 测试 profile 将空闲阈值调小（如 2s）→ ONGOING 会话无消息 → 自动 ENDED(IDLE_TIMEOUT)，推 `session_ended`（AC-C4-4）；有消息活动则不结束（AC-C4-5）。 |
| 开方结束会话 | 医生 `prescription` 帧 → 收 `prescription_issued` + `session_ended(PRESCRIPTION_ISSUED)`；随后消息/再开方被拒（AC-P1-2/3）。 |
| 处方一次性 | 同一处方两线程并发 consume → 仅一个成功，另一个「处方已使用」；复用率 = 0（AC-P2-10/EX-IM-26）。 |
| 结束 vs 消息竞态 | 主动结束与发消息并发 → 结果确定、无半状态（AC-C4-9/EX-IM-14）。 |
| 连接上限 | 第三连接（如第二个医生）join → `error{OVER_CONNECTION_LIMIT}`。 |

- 测试基建：`@SpringBootTest(webEnvironment = NONE)` 单测 Netty + Service（H2）；或 `RANDOM_PORT` 同时验证 REST。短时序参数（grace/idle/drain 周期）走测试 profile，生产值不污染。

### 10.4 order-service Rx 校验测试

- `OrderServiceTest`（Mock `ProductClient/ConsultClient`）：Rx 无处方拒绝（EX-IM-1）；Rx 带处方成功（consume 被调、订单记录 prescriptionNo、扣库存被调）；consume 失败透传精确文案（EX-IM-2~8）；**本地落单失败 → 回补库存 + restorePrescription 均被调**（AC-P2-11）；consult 不可用 → Rx 被拒且不扣库存不落单（AC-IM-6）；OTC 不带处方走原流程（AC-P2-12）。
- `ShopControllerTest`：`/order/confirm` Rx 无处方 → 拦截提示；带 `prescriptionNo` → quantity 锁定 + 处方号预填（AC-P2-2）；问诊/我的问诊/医生控制台/我的处方路由可渲染。

### 10.5 product / recommend 测试

- product：`ProductDTO.rxFlag` 序列化；`DataInitializer` 药品种子 OTC/Rx 标记正确；老库回填默认 false 不覆盖价格库存（AC-D1-5）。
- recommend：候选池/AI 结果/规则选品均**排除 `rxFlag=true`**（Rx 不进推荐）。

### 10.6 并发与边界用例汇总

quantity≤0、消息 >500 字、帧超 4KB、处方数量不一致、伪造处方号、双端并发消息（AC-IM-2）、并发消费同处方（AC-P2-10）、并发结束 vs 消息（AC-C4-9）、空闲 vs 消息竞态（EX-IM-27）、多用户并发建会话去重（EX-IM-28）、WAITING 不误结束（AC-C4-6）。

### 10.7 功能/集成（端到端）

方式 B（Mock 下游）覆盖 order 全部新页面；方式 A（五服务 + MySQL）走 AC-IM-7 全路径：浏览 Rx → 问诊 → 聊天 → 开方 → 凭处方下单 → 支付 → 处方 USED、订单 PAID。

---

## 11. 需求覆盖矩阵（AC / EX-IM）

### 11.1 异常场景 EX-IM 覆盖（28 条）

| 编号 | 设计机制 | 测试位置 |
| --- | --- | --- |
| EX-IM-1 | §6.5 ② Rx 无处方拦截（fail-closed） | order OrderServiceTest |
| EX-IM-2 | §6.4 第 0 步 USED 拒绝 | consult PrescriptionServiceTest |
| EX-IM-3 | §6.4/§6.5 懒判定置 EXPIRED + 拒绝 | consult 单元 + order 透传 |
| EX-IM-4 | §6.4 药品匹配 | consult PrescriptionServiceTest |
| EX-IM-5 | §6.4 存在性 | consult PrescriptionServiceTest |
| EX-IM-6 | §6.4 数量一致 | consult PrescriptionServiceTest |
| EX-IM-7 | §6.4 REVOKED 拒绝 | consult PrescriptionServiceTest |
| EX-IM-8 | §6.4 归属校验 | consult PrescriptionServiceTest |
| EX-IM-9 | §5.3 断线缓冲（60s 重连/超时） | Netty 集成（短 grace） |
| EX-IM-10 | §5.1 转移矩阵 WAITING 不自动结束 | consult 单元状态机 + AC-C4-6 |
| EX-IM-11 | §5.3 afterSeq 增量补齐 | Netty 集成 |
| EX-IM-12 | §5.3/§5.4 DISCONNECT_TIMEOUT | Netty 集成 |
| EX-IM-13 | §5.4 空闲超时扫描 | Netty 集成（短阈值） |
| EX-IM-14 | §4.3 会话锁 + CAS 确定性 | Netty 集成竞态 + 单元 |
| EX-IM-15 | §5.1 终态拒绝开方 | consult 单元 + 接口 |
| EX-IM-16 | §5.1 WAITING 不因医生离线结束 | consult 单元状态机 |
| EX-IM-17 | §5.1 终态拒绝发消息 | Netty 集成 + 单元 |
| EX-IM-18 | §5.1 终态拒绝开方（AC-P1-3） | consult 单元 + 接口 |
| EX-IM-19 | §3 归属校验 / 路由隔离 | Netty 集成隔离 + REST 越权 |
| EX-IM-20 | §5.1 状态机前置校验 | consult 单元转移矩阵 |
| EX-IM-22 | §6.5 双补偿（恢复处方 + 回补库存） | order OrderServiceTest + consult restore |
| EX-IM-23 | §3/FR-C1 非 Rx 拒绝建会话 | consult SessionServiceTest |
| EX-IM-24 | §6.2 用法用量必填 | consult PrescriptionServiceTest |
| EX-IM-25 | §4.4/§4.5 seq 串行分配 + 转发 | Netty 集成（双端并发） |
| EX-IM-26 | §6.3 单条 CAS 一次性 | Netty 集成并发 consume |
| EX-IM-27 | §4.3 会话锁确定性 | Netty 集成竞态 |
| EX-IM-28 | §3 隔离 + 复用去重（同用户同药品只建一个） | consult SessionServiceTest + 集成 |

> 注：PRD 编号从 1 到 28 未出现 21（§11.1 直接 8→22）；本矩阵覆盖 PRD 实际枚举的全部 27 条（1~20、22~28）。

### 11.2 验收标准 AC 覆盖（分组 → 测试）

| 组 | 条数 | 测试位置 |
| --- | --- | --- |
| AC-D1-1~5 | 5 | product 单元 + 功能（rxFlag 种子/回填/Rx 拦截） |
| AC-C1-1~4 | 4 | consult 接口（建会话/复用/新建/非 Rx 拒） |
| AC-C2-1~7 | 7 | Netty 集成 + consult 接口（实时/历史 seq/回显/顺序/补齐/超长拒/系统消息） |
| AC-C3-1~6 | 6 | consult 接口 + 单元（接诊/结束/越权/非法流转） |
| AC-C4-1~10 | 10 | Netty 集成 + 单元（主动/断线缓冲/超时/空闲/防误判/开方归属/终态拒/竞态/医生离线） |
| AC-P1-1~6 | 6 | consult 接口 + 单元（开方成功/重复拒/终态拒/WAITING 拒/必填/越权） |
| AC-P2-1~12 | 12 | order OrderServiceTest + 接口（消费成功/锁定数量/各异常/并发一次性/补偿/OTC 不受影响） |
| AC-P3-1~5 | 5 | consult 接口 + 单元（ACTIVE 展示/已用/撤销/过期懒判定/终态仅展示） |
| AC-IM-1~7 | 7 | Netty 集成 + order 集成（隔离/顺序/不丢/防误判/状态机遍历/停机降级/全路径回归） |

---

## 12. 待评审风险点

| 编号 | 风险/决策点 | 级别 | 说明与建议 |
| --- | --- | --- | --- |
| R-10 | **WS 与 REST 分端口（:8086 / :8085）** | 中 | 需求写明「consult-service 端口 8085」「WS 连 8085」，但 Spring MVC(Tomcat) 无法与 Netty 共享端口、WebFlux 被排除。本期取「REST :8085 + WS `consult.ws.port=8086`」；JS 连 ws://localhost:8086。若坚持同端口，唯一方案是单一 Netty 同时承载 HTTP+WS（放弃 Spring MVC，超本期）。**请评审确认端口分配**。 |
| R-11 | 处方为「单药品」模型 | 低 | PRD 处方 = 会话目标单一药品；「items(药品+用法用量)」语义落为单药品字段。多药品组合处方需扩展为明细表（R 后续迭代）。 |
| R-12 | 内存 token/注册表重启失效 | 低 | 进程重启在线连接全断、token 失效；客户端重连需先 REST 复用会话重新取 token。演示级可接受；生产应持久化令牌或改为 HMAC 无状态签名。 |
| R-13 | 演示身份（userId 明文） | 中 | `userId` 由浏览器生成并携带，无登录鉴权（NG-10）。会话隔离靠服务端归属校验，但用户可伪造**他人** userId 访问其会话（演示环境无账号体系，PRD 接受）。生产需真实登录 + 鉴权令牌。 |
| R-14 | 写队列异步引入的时序 | 中 | 「先落库后 ack/转发」引入 ≤1 个 drain 周期（≤50ms）延迟；需在 Netty 集成测试固化时序断言，避免异步改坏顺序性。 |
| R-15 | `ddl-auto=update` + `prescription_no` 存量 | 中 | 新列/新表由 ddl-auto 建；上线前收敛 Flyway/Liquibase（沿用 R-02）。Rx 老订单无处方号，展示层需容错。 |
| R-16 | Netty 连接/速率上限为代码级 | 低 | 演示单机可接受；生产需网关层（限流/白名单）+ 令牌刷新与吊销。 |
| R-17 | `restore` 处方接口无调用方鉴权 | 中 | `restore` 仅由 order-service 补偿调用，本期无服务间鉴权；建议后续加内部调用密钥或限定来源 IP（演示可接受，R-13 同源）。 |
| R-18 | 空闲/断线常量集中可配 | 低 | grace=60s、idle=30min、心跳 15s/30s、处方 7 天均为配置项（`consult.*`），测试用短值覆盖；需防止生产误配为 0 导致误结束。 |

---

## 附：方案核心决策与待评审风险点（≤200 字）

**核心决策**：新增 consult-service（REST :8085 + Netty WS :8086，schema shopdb_consult），会话/消息/处方三表；**会话隔离**用 token 绑定 + 每会话 2 连接上限 + 路由只查本会话 channel 集，fail-closed；**并发**靠事件循环内不做阻塞 IO + 每会话锁 + CAS `UPDATE...WHERE status` 影响行数裁决 + seq/clientMsgId 双幂等，消息经批量写队列异步落库（先落库后 ack/转发）；**会话结束**由主动/断线缓冲 60s/空闲 30min/开方完成四判据 + WAITING 防误判 + 调度器兜底，统一 ENDED+endReason 状态机；**处方**开方（仅本人 ONGOING 会话）→ ACTIVE→USED/EXPIRED/REVOKED，消费用单条 CAS 一次性、下单失败双补偿回补。**待评审**：WS/REST 分端口（R-10）、单药品处方模型（R-11）、token 重启失效（R-12）、restore 无鉴权（R-17）。
