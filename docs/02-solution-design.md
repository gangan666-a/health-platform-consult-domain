# 《AI 智能导购微服务商城》研发方案设计（Solution Design）

| 项目 | 内容 |
| --- | --- |
| 文档名称 | simple-mall（AI 智能导购）微服务商城——研发方案设计 |
| 版本 | v1.0 |
| 文档作者 | 研发架构师（R&D Architect / Tech Lead） |
| 评审角色 | 架构组、后端研发、QA、DBA |
| 评审状态 | 待评审 |
| 技术栈基线 | Spring Boot 3.5.16 · Spring MVC + Thymeleaf · Spring Data JPA · MySQL 8.4 · Java 25 · DeepSeek API |
| 关联文档 | README.md（现状说明）、Phase 1 PRD、Phase 4 代码实现 |

> 本方案为**纯设计文档**，不含任何代码改动；文中所有结构、接口、表结构均基于当前代码现状（git HEAD `a3c1ef7`）提炼，并给出可评审的规范化建议。

---

## 目录

- [0. 术语与约定](#0-术语与约定)
- [1. 架构总览](#1-架构总览)
- [2. 关键设计决策（ADR 风格）](#2-关键设计决策adr-风格)
- [3. API 契约](#3-api-契约)
- [4. 数据库设计](#4-数据库设计)
- [5. 关键流程时序](#5-关键流程时序)
- [6. 状态机](#6-状态机)
- [7. 容错与降级](#7-容错与降级)
- [8. 并发与一致性](#8-并发与一致性)
- [9. 错误处理规范](#9-错误处理规范)
- [10. 测试策略（研发视角）](#10-测试策略研发视角)
- [11. 待评审风险点](#11-待评审风险点)

---

## 0. 术语与约定

| 术语 | 含义 |
| --- | --- |
| BFF | Backend For Frontend，面向浏览器的后端聚合层。本项目 `order-service` 承担全部页面渲染与路由聚合。 |
| 原子扣减 | 单条 `UPDATE ... WHERE stock >= ?` 的并发安全库存扣减，靠影响行数判定是否充足。 |
| 回调（Callback） | `payment-service` 完成模拟扣款后，主动调用 `order-service` 的 `confirm-payment` 接口确认支付结果。 |
| 幂等（Idempotency） | 同一业务动作重复提交（重试/并发）不会造成二次副作用。 |
| 补偿（Compensation） | 跨服务事务失败时，反向调用已成功服务撤销副作用的尽力而为（best-effort）操作。 |
| 幻觉（Hallucination） | 大模型生成了目录之外 / 用户已购 / 重复的商品 id。 |
| 状态枚举 | `OrderStatus`（CREATED/PAID/CANCELLED）、`PaymentStatus`（PROCESSING/SUCCESS/FAILED）。 |
| source | 推荐结果来源标记：`ai`（DeepSeek 大模型） / `rule`（规则兜底）。 |

**全文档约定**：
1. REST 错误体统一为 `{"message":"..."}`（详见第 9 章）。
2. 金额字段一律 `BigDecimal`，DB 精度 `DECIMAL(12,2)`。
3. 状态字段 DB 存枚举 `name`（字符串），JSON 亦传枚举 `name`，中文文案走 DTO 的 `*Label` 字段。
4. 服务间通信仅 `Spring RestClient`，均配置超时与异常转换。

---

## 1. 架构总览

### 1.1 系统架构图

```
                        ┌────────────────────────────────────────────┐
                        │            浏览器（唯一入口）                  │
                        │               http://localhost:8082          │
                        └───────────────────┬────────────────────────┘
                                            │ Thymeleaf 页面
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          order-service  (BFF / 编排层)                        │
│  端口 :8082  ·  schema: shopdb_order                                          │
│  ┌───────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ Web 页面渲染   │  │ REST API     │  │ RestClient   │  │ AI 推荐容错边界 │  │
│  │ ShopController│  │ OrderApi     │  │ Client ×3    │  │ RecommendClient│  │
│  └───────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───────┬────────┘  │
│          │                 │                 │  (同步编排)        │ (容错)     │
└──────────┼─────────────────┼─────────────────┼───────────────────┼──────────┘
           │                 │                 │                   │
           │                 │  支付确认回调    │                   │
           │                 │  (payment→order)│                   │
           │                 ▼                 │                   │
┌──────────────────┐  ┌────────────────────┐  │  ┌─────────────────────────────┐
│ product-service  │  │  payment-service   │  │  │    recommend-service         │
│  纯 REST · :8081  │  │  纯 REST · :8083   │  │  │    纯 REST · :8084（无数据库）│
│  shopdb_product   │  │  shopdb_payment    │  │  │   ┌──────────┐ ┌──────────┐ │
│  product          │  │  payment           │  │  │   │ AI 推荐  │ │ 规则兜底  │ │
│  库存原子扣减/回补 │  │  支付流水+幂等      │◄─┼──┼───┼──────────┤ │（确定性） │ │
│  种子数据/品类     │  │                   │  │  │   │ 3 层校验  │ └──────────┘ │
└──────────────────┘  └────────────────────┘  │  │   └─────┬────┘              │
           ▲   ▲         ▲                    │  │         │ REST (OpenAI 兼容) │
           │   │         │   GET 订单/确认     │  │         ▼                    │
           │   │         │                    │  │  ┌──────────────────────────┐│
           │   └─────────┴────────────────────┼──┼──┤ DeepSeek API             ││
           │           支付回调                │  │  │ deepseek-v4-flash        ││
           │                                 │  │  └──────────────────────────┘│
           └─────────────────────────────────┘  └─────────────────────────────┘
             依赖方向：order → product / payment / recommend（见 1.3）
```

### 1.2 模块边界与职责

| 模块 | 类型 | 端口 | 数据库 | 职责边界 |
| --- | --- | --- | --- | --- |
| `mall-common` | 共享库（纯 Java，无 Spring Boot 应用） | — | — | DTO、业务异常 `BusinessException`、统一错误体 `ApiError`、状态枚举 `OrderStatus`。**只放契约，不放业务逻辑**。 |
| `product-service` | 微服务（纯 REST） | 8081 | `shopdb_product` | 商品 CRUD 查询、**库存原子扣减/回补**（并发正确性唯一边界）、种子数据按名 upsert、品类回填。 |
| `order-service` | 微服务（BFF） | 8082 | `shopdb_order` | 全部 Web 页面（Thymeleaf）+ 订单 REST API；编排商品/支付/推荐三个下游；订单与明细快照；支付确认回调（幂等）；AI 推荐容错边界（吞异常返回空）。 |
| `payment-service` | 微服务（纯 REST） | 8083 | `shopdb_payment` | 模拟支付：校验订单 → 记支付流水（PROCESSING）→ 回调订单服务确认 → SUCCESS/FAILED；防重复支付；仅存卡号尾号 4 位。 |
| `recommend-service` | 微服务（纯 REST，无数据库） | 8084 | 无 | AI 智能导购：同类（similar）/ 互补（complement）推荐；AI 优先 + 规则兜底补位；三层幻觉校验；`source` 标记来源。 |

**关键原则**：
- **数据所有权**：每服务只能写自己 schema 的表，跨服务数据一律通过 REST 读取，杜绝跨库 JOIN。
- **前端聚合**：浏览器唯一入口是 `order-service`，其余服务不暴露给浏览器，避免 CORS 与鉴权扩散。
- **无数据库服务**：`recommend-service` 是无状态推荐器，天然可水平扩容。

### 1.3 服务依赖方向（依赖矩阵）

**运行期 REST 调用方向**（`A → B` 表示 A 调用 B）：

| 调用方 \ 被调用方 | product | order | payment | recommend | DeepSeek |
| --- | --- | --- | --- | --- | --- |
| order-service | ✓ 商品/库存 | — | ✓ 发起支付 | ✓ AI 推荐 | — |
| payment-service | — | ✓ 校验订单/确认回调 | — | — | — |
| recommend-service | — | — | — | — | ✓ chat/completions |
| product-service | — | — | — | — | — |
| mall-common | 被所有服务编译期依赖（纯 DTO，无运行期调用） | | | | |

**编译期依赖**（Maven）：`product/order/payment/recommend` → `mall-common`。四个服务之间**互不依赖对方代码**，杜绝编译期循环依赖。

```
依赖方向图（有向无环，DAG）：

   mall-common (DTO 契约)
        ▲      ▲      ▲      ▲
        │      │      │      │  编译期
   product  order  payment  recommend

   order ──▶ product ──▶ (无)
   order ──▶ payment ──▶ order   ※ 唯一"业务对向依赖"：支付回调（事件式通知，见下）
   order ──▶ recommend ──▶ DeepSeek (外部)

   recommend/payment/product 均不反向调用 order 的业务编排。
```

**关于 `order ⇄ payment` 双向运行期调用（需评审的关注点）**：
- 方向一：`order-service → payment-service` 发起支付（编排语义，命令）；
- 方向二：`payment-service → order-service` 支付确认回调（`POST /api/orders/{orderNo}/confirm-payment`）。
- 二者并非"业务反向依赖/循环依赖"：回调属于**单向事件通知**（payment 完成后通知 order 改状态），订单状态数据仍归 order 所有；且两服务**无数据库交叉、无编译期依赖**。属于可接受的运行期有向边，风险见第 11 章 R-01。
- **规避规则**：不得出现 `payment-service` 反过来查询/修改 order 的业务编排逻辑；order 只暴露最小回调面（`getOrder` / `confirm-payment`）。

---

## 2. 关键设计决策（ADR 风格）

### ADR-01 下单：先扣库存，再落订单

- **决策**：`OrderService.createOrder` 先校验数量 → 调商品服务原子扣减库存（`deductStock`）→ 扣减成功后再本地生成订单 + 明细快照。
- **理由**：库存是共享稀缺资源，必须**先占先得**。先落单后扣库存会形成"僵尸订单占用名额"，且扣库存失败时需回滚本地事务，跨服务回滚代价更高。先扣库存把"能否成交"的裁决前移到唯一并发边界，落单只做快照持久化。
- **结果**：页面展示的库存只是提示，最终边界在商品服务的原子扣减（`WHERE stock >= ?`），见 ADR-09。
- **权衡**：扣减成功后本地落单前若进程崩溃，库存已被扣但无订单 → 需要 ADR-02 的补偿与对账兜底。

### ADR-02 失败补偿：本地保存失败时回补库存

- **决策**：`createOrder` 中 `orderRepository.save` 抛 `RuntimeException` 时，catch 内尽力调用 `restoreStock` 回补库存；回补失败则记录忽略，交人工对账。
- **理由**：跨服务无法本地两阶段提交，用**尽力补偿（best-effort）+ 日志**把"已扣未补"窗口最小化，成本最低、语义清晰。
- **局限**：进程在"扣减成功"与"落单成功"之间崩溃时补偿不会执行，形成悬挂库存。演示级接受；生产需 Saga/Outbox（见 ADR-08 与第 8 章）。

### ADR-03 支付回调幂等

- **决策**：`confirmPayment` 对订单状态做分支：已是 `PAID` → **直接返回成功**（幂等命中）；已是 `CANCELLED` → 抛"订单已取消，无法支付"；`CREATED` → 置为 `PAID` 并回写支付信息。
- **理由**：支付服务重试、用户重复点击、回调乱序都可能导致同一订单被多次确认。**幂等是回调接口的第一原则**：重复确认不得报错、不得产生第二次副作用。状态字段天然充当幂等锁（`CREATED→PAID` 单向，成功即终态）。
- **配套**：`PaymentService` 侧在发起支付时拦截重复流水（非 FAILED 流水存在即拒绝），双端共同防重（见第 8 章）。

### ADR-04 推荐降级（AI 挂了，页面不挂）

- **决策**：推荐链路分三层降级——① `order-service.RecommendClient` 吞异常返回空列表（**页面级兜底**，区块 `th:if` 隐藏）；② `recommend-service` AI 失败/未配置 Key 回退规则推荐（**服务级兜底**）；③ 规则推荐不足 topN 时用热销/互补补足。
- **理由**：AI 推荐是**增强体验**而非交易主链路，绝不允许外部 LLM 的不可用/超时/返回异常拖垮支付成功页与订单详情页。容错边界前置到 BFF 的 client，保证请求必达页面。
- **结果**：推荐服务整体不可用时页面正常渲染、推荐区隐藏；`source` 字段区分 `ai`/`rule` 用于前端展示角标（🤖 AI 推荐 / 同类优选 / ✨ 搭配推荐）。

### ADR-05 每服务独立 schema（微服务数据自治）

- **决策**：`shopdb_product` / `shopdb_order` / `shopdb_payment` 三个独立库，`init.sql` 建库并授权，应用 `ddl-auto=update` 各自建表；`recommend-service` 无库。
- **理由**：数据自治是微服务边界的基础——库存/订单/支付具备独立生命周期与扩展诉求；独立 schema 天然禁止跨库 JOIN，强制走 REST，避免团队间耦合与单库故障扩散。
- **约束**：`ddl-auto=update` 适合原型/演示，**上线前应由 DBA 收敛为 Flyway/Liquibase 管理**（见第 11 章 R-02）。

### ADR-06 金额全程 BigDecimal

- **决策**：所有金额字段（商品价格、单价、小计、订单总额、支付金额）一律 `BigDecimal`，DB `DECIMAL(12,2)`。
- **理由**：货币运算严禁浮点（`double` 0.1+0.2 精度丢失）；`DECIMAL(12,2)` 足够演示级金额范围且语义明确；`BigDecimal` 计算（`unitPrice.multiply(BigDecimal.valueOf(quantity))`）保证精确到分。
- **注意**：金额运算须显式指定 `scale`/舍入策略，避免 `ArithmeticException`；项目当前均为 2 位小数乘法，无除法，风险可控。

### ADR-07 状态枚举与流转

- **决策**：`OrderStatus`（CREATED/PAID/CANCELLED）与 `PaymentStatus`（PROCESSING/SUCCESS/FAILED）用 Java 枚举，DB 以 `EnumType.STRING` 存 name，JSON 传枚举 name，中文文案走 `statusLabel`。
- **理由**：`@Enumerated(STRING)` 存储可读、可索引、比 ORDINAL 抗重排；跨服务只依赖稳定的 `name` 判断状态，展示文案由 DTO 承载，实现"状态机与展示解耦"。合法流转见第 6 章。

### ADR-08 BFF 聚合与分布式事务边界（演示级）

- **决策**：`order-service` 作为 BFF 聚合所有页面；跨服务一致性不做强事务，采用**"先扣后补 + 流水状态 + 幂等回调"**的最轻量兜底；明确标注 Saga/Outbox/MQ 为生产演进方向（本期不引入）。
- **理由**：演示级目标下引入消息队列与编排引擎会显著增加复杂度；轻量兜底已能满足"库存不超卖、支付不重复、失败有补偿"三项核心正确性，同时文档显式声明事务边界，避免误用。

### ADR-09 展示库存只是提示，扣减才是边界

- **决策**：下单前的 `product.getStock() < quantity` 校验仅用于页面友好提示；真正裁决并发正确性的是商品服务 `deductStock` 的原子 `UPDATE ... WHERE stock >= ?`，影响行数为 0 即抛"库存不足"。
- **理由**：页面读到的是**快照**，并发下必然过期；所有"能否扣减"的判断必须收敛到数据库原子操作这一**唯一边界**，否则超卖无法根除。

### ADR-10 AI 模型选型：`deepseek-v4-flash` + 关闭思考模式 + JSON 输出

- **决策**：推荐任务选 `deepseek-v4-flash`（费用约 pro 的 1/3，推荐类任务无需深度推理）；显式 `thinking={"type":"disabled"}` 使 `temperature` 生效且理由直接出现在 `content`；`response_format={"type":"json_object"}` 强制结构化输出。
- **理由**：推荐 = 从目录挑商品 + 一句话理由，flash 够用且快；关闭思考保证响应稳定可解析；JSON 模式 + 服务端三层校验（第 5.5 / 7 章）是防幻觉的关键闭环。
- **迁移（2026-07）**：DeepSeek 于 2026-07-24 硬性停用 `deepseek-chat` / `deepseek-reasoner`（官方映射到 V4 Flash/Pro）。全仓模型名已迁至 `deepseek-v4-flash` / `deepseek-v4-pro`；主模型与升级模型统一由 `.claude/llm-switch.json` 经 `sync-llm-model` hook 渲染为 `llm-models.properties`（T-8），改一处即切模型。

---

## 3. API 契约

### 3.0 通用约定

- **错误响应体（全服务统一）**：`HTTP 400`（业务） / `HTTP 500`（内部） + `{"message":"原因"}`。
- **状态码语义**：
  - `200`：成功（含幂等命中）。
  - `400`：业务校验失败（库存不足、订单已支付、商品不存在等）。
  - `500`：服务内部错误（含下游不可用的兜底提示）。
- **JSON 字段**：`camelCase`；金额 `BigDecimal` 序列化为数字；时间 `LocalDateTime` 走 ISO 字符串。
- **幂等性标注**：下表以 ⭐ 表示"天然幂等/已做幂等"，❌ 表示非幂等（调用方不得重试）。

### 3.1 product-service（:8081）

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/products` | — | `ProductDTO[]` | 500 | ⭐ 读操作 |
| GET | `/api/products/{id}` | — | `ProductDTO` | 400 商品不存在；500 | ⭐ 读操作 |
| POST | `/api/products/{id}/stock/deduct` | query `quantity`(int, 默认1) | `ProductDTO`（库存为扣减后展示值） | 400 数量非法 / 库存不足；500 | ❌ 不可重试（重试会二次扣减），调用方须先扣后落单并自行补偿 |
| POST | `/api/products/{id}/stock/restore` | query `quantity`(int, 默认1) | `ProductDTO` | 400 数量非法 / 商品不存在；500 | ❌ 同上有多次回补风险，回补操作由业务显式驱动（取消/补偿） |

`ProductDTO`：
```json
{ "id": 1, "name": "iPhone 15 Pro", "description": "...", "price": 7999.00,
  "stock": 49, "imageUrl": "📱", "category": "手机", "createdAt": "2026-08-06T10:00:00" }
```

> 备注：`deductStock`/`restoreStock` 返回的 `stock` 是事务起始读取值经本地增减得到的**展示值**，并发下可能短暂过期；权威库存以 DB 为准。见 1.2/ADR-09 与第 8 章。

### 3.2 order-service（:8082）

**A. REST API（供 payment-service 调用，JSON）**

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/orders/{orderNo}` | — | `OrderDTO` | 400 订单不存在；500 | ⭐ 读操作 |
| POST | `/api/orders/{orderNo}/confirm-payment` | body `ConfirmPaymentRequest` | `OrderDTO`（已确认后状态） | 400 订单不存在 / 订单已取消；500 | ⭐ **幂等**：已 PAID 直接返回成功，不重复写 |

`ConfirmPaymentRequest`：
```json
{ "payMethod": "支付宝", "payBank": "", "payCardTail": "1234", "paidAt": "2026-08-06T10:01:00" }
```

`OrderDTO`（节选关键字段）：
```json
{ "orderNo": "SO202608061000011234", "receiverName": "张三", "receiverPhone": "13800000000",
  "receiverAddress": "北京市...", "totalAmount": 15998.00,
  "status": "PAID", "statusLabel": "已支付",
  "payMethod": "支付宝", "payBank": "", "payCardTail": "",
  "createdAt": "...", "paidAt": "...", "cancelledAt": null,
  "items": [ { "productId": 1, "productName": "iPhone 15 Pro", "unitPrice": 7999.00,
               "quantity": 2, "subtotal": 15998.00 } ] }
```

**B. Web 页面路由（BFF，浏览器入口）**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` 或 `/products` | 商品列表（数据来自 product-service） |
| GET | `/product/{id}` | 商品详情（选数量→立即购买） |
| GET | `/order/confirm?productId=&quantity=` | 确认订单（填收货信息） |
| POST | `/order` | 提交订单（body: OrderForm；含 Bean Validation：quantity 1~999、收货人/手机/地址必填） |
| GET | `/order/{orderNo}` | 订单详情 + 🔄 同类推荐（`similarRecommendations`，空则隐藏） |
| GET | `/order/{orderNo}/pay` | 模拟收银台 |
| POST | `/order/{orderNo}/pay` | 发起支付（转发 payment-service，成功跳成功页） |
| GET | `/order/{orderNo}/pay/success` | 支付成功页 + 同类 + ✨ AI 互补推荐（两次推荐并行） |
| POST | `/order/{orderNo}/cancel` | 取消订单（仅 CREATED 可取消，逐明细回补库存） |
| GET | `/orders` | 我的订单列表 |

### 3.3 payment-service（:8083）

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/payments` | body `PaymentRequest` | `PaymentDTO` | 400 订单不存在 / 订单已支付 / 订单已取消 / 已发起过支付 / 回调订单失败（此时流水置 FAILED）；500 | ❌ 发起动作本身非幂等，但服务端做**防重复流水**校验（非 FAILED 流水存在即拒绝），等价于请求级幂等 |
| GET | `/api/payments/{orderNo}` | — | `PaymentDTO` | 400 支付记录不存在；500 | ⭐ 读操作 |

`PaymentRequest`：
```json
{ "orderNo": "SO...", "payMethod": "银行卡", "payBank": "招商银行", "payCardNo": "6225...8888" }
```
`PaymentDTO`：
```json
{ "id": 1, "orderNo": "SO...", "amount": 15998.00, "payMethod": "银行卡",
  "payBank": "招商银行", "payCardTail": "8888", "status": "SUCCESS",
  "createdAt": "...", "paidAt": "..." }
```

> **数据安全**：`payCardNo`（完整卡号）仅作请求入参，**服务端只计算并持久化尾号 4 位** `payCardTail`，不落库完整卡号（PCI 最小化原则，见第 11 章 R-03）。

### 3.4 recommend-service（:8084）

| 方法 | 路径 | 请求 | 响应 | 错误 | 幂等 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/recommendations` | body `RecommendRequest` | `RecommendationResponse` | 400 参数非法；500 | ⭐ 无副作用，可任意重试 |

`RecommendRequest`：
```json
{
  "type": "complement",   // "similar" | "complement"（缺省 complement）
  "purchasedItems": [ { "productId": 1, "productName": "iPhone 15 Pro", "quantity": 1 } ],
  "catalog": [ { "id": 1, "name": "iPhone 15 Pro", "price": 7999.00, "category": "手机", "imageUrl": "📱" } ]
}
```
`RecommendationResponse`：
```json
{
  "items": [
    { "productId": 9, "productName": "苹果 AirPods Pro 2", "price": 1899.00,
      "imageUrl": "🎧", "reason": "买了「iPhone 15 Pro」，再配一副 AirPods，通勤观影更沉浸", "source": "ai" }
  ]
}
```
- 约定：`catalog` 为空 → 返回空 `items`；`type` 非法按 `complement` 处理；单条返回**自包含**商品展示字段，`order-service` 无需回查商品服务即可渲染。
- **容错约定**：本接口**永不因 DeepSeek 故障而 5xx**——AI 失败/未配置 Key 一律回退规则，正常返回（仅 `items` 来源变为 `rule`）。

### 3.5 错误码速查表

| 场景 | 产生位置 | HTTP | message 示例 |
| --- | --- | --- | --- |
| 业务校验失败（通用） | 各服务 | 400 | 库存不足，当前库存仅 X 件 / 订单已支付，无法取消 / 该订单已发起过支付… |
| 商品不存在 | product | 400 | 商品不存在 |
| 数量非法 | product | 400 | 扣减数量必须大于 0 |
| 订单不存在 | order | 400 | 订单不存在 |
| 订单已取消/已支付冲突 | order/payment | 400 | 订单已取消，无法支付 |
| 下游服务不可用 | order/payment 的 Client | 400→页面/400 | 商品服务不可用，请稍后重试 |
| 内部异常兜底 | 各服务 | 500 | XX服务内部错误：… |
| 推荐服务不可用 | order 的 RecommendClient | —（吞异常，返回空列表） | 仅记 WARN 日志 |

---

## 4. 数据库设计

### 4.1 总览

- 三个 schema：`shopdb_product` / `shopdb_order` / `shopdb_payment`，字符集 `utf8mb4`、`utf8mb4_unicode_ci`，`init.sql` 建库授权。
- 各 schema 由所属服务 `ddl-auto=update` 自动建表（**生产建议收敛为迁移工具**，见 R-02）。
- 表命名现状（与 README 的示意命名略有出入，见 R-04 建议统一）：

| Schema | 表 | 说明 |
| --- | --- | --- |
| shopdb_product | `product` | 商品主档 + 库存 + 品类 |
| shopdb_order | `shop_order` | 订单主表 |
| shopdb_order | `order_item` | 订单明细（下单快照） |
| shopdb_payment | `payment` | 支付流水 |

### 4.2 shopdb_product.product

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| name | VARCHAR(100) | NOT NULL | 商品名（种子 upsert 键） |
| description | VARCHAR(500) | — | 描述 |
| price | DECIMAL(12,2) | NOT NULL | 单价（BigDecimal 精确到分） |
| stock | INT | NOT NULL | 库存 |
| image_url | VARCHAR(300) | — | 图片：emoji 或 http 链接 |
| category | VARCHAR(50) | — | 品类（同类推荐依据；老数据可为 NULL） |
| created_at | DATETIME | NOT NULL | 创建时间 |

- 索引建议：
  - `UK(name)`：支撑种子数据按名 upsert 的幂等查询。
  - `IDX(category)`：推荐服务/后台按品类筛选（当前全表 31 行可不建，数据量大后加）。
- **并发要点**：库存扣减走 `UPDATE product SET stock=stock-? WHERE id=? AND stock>=?`，依赖行锁串行化同商品扣减，无需悲观锁/乐观锁版本号。

### 4.3 shopdb_order.shop_order

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| order_no | VARCHAR(32) | NOT NULL, **UK** | 业务单号（幂等键，见 8.2） |
| receiver_name | VARCHAR(50) | — | 收货人 |
| receiver_phone | VARCHAR(20) | — | 手机号 |
| receiver_address | VARCHAR(200) | — | 地址 |
| total_amount | DECIMAL(12,2) | NOT NULL | 订单总额 |
| status | VARCHAR(20) | NOT NULL | OrderStatus 枚举 name |
| pay_method | VARCHAR(20) | — | 支付宝/微信支付/银行卡 |
| pay_bank | VARCHAR(30) | — | 发卡银行（仅银行卡） |
| pay_card_tail | VARCHAR(4) | — | 卡号尾号 4 位 |
| created_at | DATETIME | NOT NULL | 下单时间 |
| paid_at | DATETIME | — | 支付时间 |
| cancelled_at | DATETIME | — | 取消时间 |

- 索引建议：`UK(order_no)`；`IDX(status)`（列表页/对账按状态扫）；`IDX(created_at)`（按时间分页/对账）。

### 4.4 shopdb_order.order_item（订单明细快照）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| order_id | BIGINT | NOT NULL, FK→shop_order.id | 订单外键（JPA `@ManyToOne` 懒加载） |
| product_id | BIGINT | NOT NULL | 商品 id（不建物理 FK，商品在另一个库） |
| product_name | VARCHAR(100) | NOT NULL | **名称快照**（商品改名不回溯历史订单） |
| unit_price | DECIMAL(12,2) | NOT NULL | 单价快照 |
| quantity | INT | NOT NULL | 数量 |
| subtotal | DECIMAL(12,2) | NOT NULL | 小计 = unit_price × quantity |

- 索引建议：`IDX(order_id)`（明细按订单反查）。
- **快照语义**：`product_name/unit_price` 在**下单时刻**固化，后续商品改价/改名不影响已下订单与支付金额——这是订单明细设计的行业惯例。

### 4.5 shopdb_payment.payment（支付流水）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| order_no | VARCHAR(32) | NOT NULL, **UK** | 关联订单号（一单至多一条"非失败"流水，防重复支付的 DB 依据） |
| amount | DECIMAL(12,2) | NOT NULL | 支付金额 |
| pay_method | VARCHAR(20) | — | 支付宝/微信支付/银行卡 |
| pay_bank | VARCHAR(30) | — | 发卡银行 |
| pay_card_tail | VARCHAR(4) | — | 卡号尾号 |
| status | VARCHAR(20) | NOT NULL | PaymentStatus 枚举 name |
| fail_reason | VARCHAR(200) | — | 失败原因（仅 FAILED） |
| created_at | DATETIME | NOT NULL | 创建时间 |
| paid_at | DATETIME | — | 支付成功时间 |

- 索引建议：`UK(order_no)`（防重 + 查询）；`IDX(status)`（对账/重试扫描）。
- **唯一约束与重试**：`UK(order_no)` 当前由代码语义配合（允许 FAILED 后重建流水？）——**现状是 `findByOrderNo` 只返回一条，FAILED 后可再次发起**。注意：物理唯一键与"失败可重试"存在张力，建议后续引入 `biz_no`（幂等键）+ `payment_no`（流水号）分离，见 8.2 与 R-05。

### 4.6 索引与约束汇总建议

| 表 | 建议索引 | 唯一约束 | 外键 |
| --- | --- | --- | --- |
| product | UK(name) | name | 无 |
| shop_order | UK(order_no), IDX(status), IDX(created_at) | order_no | 无 |
| order_item | IDX(order_id) | — | order_id（库内逻辑 FK，JPA 管理） |
| payment | UK(order_no), IDX(status) | order_no | 无 |

### 4.7 金额字段精度约定

- 全链路 `DECIMAL(12,2)` / `BigDecimal`；整数位 10 位 + 小数位 2 位，可表示上限 99.9 亿，演示与常规电商均足够。
- 约定：
  1. 金额计算只用 `BigDecimal`，禁止 `double/float`；
  2. 涉及除法/舍入必须显式指定 `RoundingMode`；
  3. DTO 序列化保持数值（不转字符串），前端展示由 Thymeleaf `#numbers.formatDecimal` 统一到两位小数；
  4. 单位一律"元"，不混用"分"。

---

## 5. 关键流程时序

### 5.1 下单流程

```
浏览器                order-service             product-service
  │  POST /order          │                          │
  │──────────────────────▶│  ① 校验 quantity(1~999)    │
  │                       │  ② GET /api/products/{id} │
  │                       │ ─────────────────────────▶│ 商品+库存快照
  │                       │ ◀─────────────────────────│
  │                       │  ③ 预校验 stock<quantity → 400 提前拒绝（友好提示）
  │                       │  ④ POST /stock/deduct     │
  │                       │ ─────────────────────────▶│ 原子 UPDATE stock=stock-? WHERE stock>=?
  │                       │ ◀──── 200 / 400库存不足 ──│（并发正确性唯一边界）
  │                       │  ⑤ 本地 save(Order+OrderItem) [DB: shopdb_order]
  │                       │     └─ 失败 → ⑥ 补偿 POST /stock/restore（best-effort）
  │                       │  ⑦ 生成 orderNo → redirect
  │ ◀──── 302 /order/SO… ─┤
```

### 5.2 支付流程（三种方式 + 银行卡）

模拟收银台 `pay.html` 提供三种支付方式，前端校验后统一 `POST /order/{orderNo}/pay` → `payment-service`：

| 方式 | 前端交互 | 提交参数 |
| --- | --- | --- |
| 支付宝 | 生成确定性伪二维码（按 orderNo+方式 播种），点"我已扫码完成支付" | `payMethod=支付宝` |
| 微信支付 | 同上（二维码文案切换） | `payMethod=微信支付` |
| 银行卡 | 先选银行 + 绑卡（16~19 位校验）→ 输入 6 位支付密码 → 提交 | `payMethod=银行卡, payBank=招商银行, payCardNo=6225…8888` |

```
浏览器          order-service(BFF)         payment-service          order-service(API)
  │ POST /pay      │                            │                        │
  │───────────────▶│  PaymentClient.execute     │                        │
  │                │───────────────────────────▶│ ① GET /api/orders/{no} │
  │                │                            │───────────────────────▶│ 订单详情
  │                │                            │◀───────────────────────│
  │                │                            │ ② 状态校验(非PAID非CANCELLED)
  │                │                            │ ③ 防重复(非FAILED流水存在→400)
  │                │                            │ ④ save Payment=PROCESSING [shopdb_payment]
  │                │                            │ ⑤ POST /confirm-payment (幂等)
  │                │                            │───────────────────────▶│ status CREATED→PAID
  │                │                            │◀──── OrderDTO(PAID) ───│
  │                │                            │ ⑥ save Payment=SUCCESS
  │                │◀──── PaymentDTO(SUCCESS) ──│    [回调失败→Payment=FAILED+上抛400]
  │◀──── 302 /pay/success ─┤                   │                        │
```

### 5.3 支付回调（幂等细节）

- 入口：`payment-service` 支付成功后回调 `POST /api/orders/{orderNo}/confirm-payment`，body 携带 `payMethod/payBank/payCardTail/paidAt`。
- `order-service.confirmPayment` 分支：
  - 已 `PAID` → **直接返回现状 OrderDTO**（幂等命中，不重复写）；
  - 已 `CANCELLED` → 抛 `400 订单已取消，无法支付`（payment 侧将流水置 FAILED）；
  - `CREATED` → `status=PAID`，回写支付信息与 `paidAt`，落库后返回。
- 重试场景：支付服务网络抖动重发回调 → 命中幂等分支，不报错、无二次副作用。

### 5.4 取消订单

```
浏览器          order-service             product-service
  │ POST /cancel   │                          │
  │───────────────▶│ ① 查询订单 status        │
  │                │ ② CREATED 才允许；否则抛：已支付无法取消 / 已取消
  │                │ ③ 逐明细 POST /stock/restore?quantity=…
  │                │─────────────────────────▶│ UPDATE stock=stock+?
  │                │◀────────── 200 ──────────│
  │                │ ④ status→CANCELLED, cancelledAt=now [shopdb_order]
  │◀──── 302 /orders ─┤
```

### 5.5 推荐流程（similar / complement，AI vs 规则，三层幻觉校验）

```
order-service                         recommend-service
  │ POST /api/recommendations            │
  │  {type, purchasedItems, catalog} ──▶ │  ① 空目录 → 直接空结果
  │                                      │  ② AI 优先：AiRecommendationService
  │                                      │     ├─ 未配 Key / 调用异常 → 返回空
  │                                      │     └─ 成功 → parseAndValidate 三层校验
  │                                      │  ③ 规则兜底补位：RuleRecommendationService
  │                                      │     └─ 用互补权重/同类品类排序，去重补足 topN
  │ ◀──── {items:[{productId,…reason,source}]}
```

**AI 结果三层幻觉校验（`AiRecommendationService.parseAndValidate`）**：
1. **目录内**：`productId` 必须命中 `catalog` 索引，否则丢弃（杜绝模型"发明"商品）；
2. **非已购**：`productId` 不得出现在 `purchasedItems`，否则丢弃（不得再推用户刚买的东西）；
3. **去重**：`LinkedHashSet` 保证同一商品只出现一次（含跨 AI/规则合并时的 `picked` 去重）。
4. （同类模式的加强过滤）品类一致：解析已购品类集合，候选 `category` 必须与已购品类相同，不满足则丢弃；解析不到品类时放宽，交由规则兜底。
   - 代码中这套防线形成 **"目录内 → 非已购 → 去重 →（同类）品类一致"** 的过滤链，README 将其概括为"三层"，文档按实现如实描述为"三层主校验 + 同类品类加强层"。

**AI vs 规则合并（`RecommendationService.recommend`）**：
- 先取 AI 结果（`aiService.recommendSimilar/recommend`，失败返回空）；
- 不足 `topN` 时用规则推荐补足名额，按 `picked` 去重；
- 最终每条带 `source=ai|rule`；`similar`/`complement` 由请求 `type` 分派。

**页面双通道（`pay-success`）**：同类与互补两次推荐用 `CompletableFuture` 并行发起，避免两个 DeepSeek 请求串行拖慢渲染；任一失败由 `RecommendClient` 吞异常返回空，区块 `th:if` 隐藏。

---

## 6. 状态机

### 6.1 订单状态（OrderStatus）

```
                ┌──────────────────────────────┐
                ▼                              │
      ┌────────────────┐   cancelOrder   ┌─────┴───────┐
      │    CREATED     │ ───────────────▶ │  CANCELLED  │（终态）
      │    待支付       │                  │    已取消    │
      └───────┬────────┘                  └─────────────┘
              │ confirmPayment 回调（幂等）
              ▼
      ┌────────────────┐
      │     PAID       │（终态）
      │    已支付       │
      └────────────────┘
```

| 当前状态 | 动作 | 目标状态 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| CREATED | 支付确认 | PAID | ✓ | 幂等：重试不改变结果 |
| CREATED | 取消 | CANCELLED | ✓ | 逐明细回补库存 |
| PAID | 支付确认 | PAID | ✓（幂等） | 直接返回现状，不二次写 |
| PAID | 取消 | — | ✗ | 抛"订单已支付，无法取消" |
| CANCELLED | 支付确认 | — | ✗ | 抛"订单已取消，无法支付" |
| CANCELLED | 取消 | — | ✗ | 抛"订单已取消" |
| PAID/CANCELLED | 任意再流转 | — | ✗ | 终态不可逆 |

**非法流转拦截**：集中在 `OrderService` 方法入口的状态断言（`cancelOrder` 检查 `!= CREATED`，`confirmPayment` 检查 `== PAID` 幂等 / `== CANCELLED` 拒绝），业务层拦截优先，数据库无额外约束兜底（终态幂等足够，见 R-06）。

### 6.2 支付流水状态（PaymentStatus）

```
                ┌──────────────────────────────┐
                ▼                              │
   ┌─────────────────────┐  回调订单失败    ┌────┴─────────┐
   │     PROCESSING      │ ──────────────▶ │    FAILED    │（可重试发起）
   │      处理中          │                 │    失败       │
   └─────────┬───────────┘                 └──────────────┘
             │ 回调成功
             ▼
   ┌─────────────────────┐
   │      SUCCESS        │（终态，一单仅此一条）
   └─────────────────────┘
```

| 当前状态 | 动作 | 目标状态 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| PROCESSING | 回调订单服务成功 | SUCCESS | ✓ | 回写 `paidAt` |
| PROCESSING | 回调失败/订单状态冲突 | FAILED | ✓ | 记录 `failReason` 后上抛，避免永久卡 PROCESSING |
| FAILED | 再次发起支付 | PROCESSING | ✓（现状） | 允许重试；见 R-05 的幂等键演进 |
| SUCCESS | 再次发起支付 | — | ✗ | 防重复支付拦截（非 FAILED 流水存在即拒绝） |

### 6.3 状态机约束要点

1. 状态以**数据库字段为唯一权威**，内存态不缓存（`@Transactional` 内读取最新）。
2. 跨服务状态一致性：订单 `PAID` 由 `confirm-payment` 回调驱动，支付流水 `SUCCESS` 以回调成功为前提——两者间存在**短暂不一致窗口**（回调中），由流水状态兜底并最终收敛。
3. 枚举序列化用 `name`，DB `EnumType.STRING`；重命名枚举会破坏存量数据，属于破坏性变更（R-06）。

---

## 7. 容错与降级

### 7.1 推荐链路容错（三层防线）

| 层级 | 故障场景 | 处理 | 页面效果 |
| --- | --- | --- | --- |
| ① BFF 客户端 | recommend-service 整体不可用 / 连接拒绝 / 超时 | `RecommendClient` catch 全部异常，`log.warn` + 返回空列表 | 推荐区块隐藏，页面正常 |
| ② 推荐服务 | DeepSeek 无 Key / 超时 / 返回异常 / JSON 解析失败 | `AiRecommendationService` 返回空 → `RecommendationService` 回退规则推荐 | 推荐区显示"同类优选/✨ 搭配推荐" |
| ③ 规则层 | AI 结果不足 topN | 规则/热销补足名额 | 列表完整 |
| ④ 页面 | 推荐列表为空 | `th:if="#{!lists.isEmpty(...)}"` 隐藏区块 | 区块不渲染 |

- 关键点：**推荐失败永不上抛到支付成功页/订单详情页**；两条推荐请求并行（`CompletableFuture`）降低整体延迟。

### 7.2 DeepSeek 异常细分与降级路径

| 异常类型 | 表现 | 处置 |
| --- | --- | --- |
| 未配置 Key | `isEnabled()=false` | 直接规则推荐（info 日志） |
| 网络/连接超时 | `RestClientException` | catch → 空 → 规则兜底（warn 日志） |
| HTTP 非 2xx（401/429/5xx） | `RestClientResponseException` | 同上 |
| 返回结构异常/空 content | `IllegalStateException` | 同上 |
| JSON 解析失败 / 校验不通过 | `parseAndValidate` 返回空 | 同上 |
| 模型幻觉（目录外/已购/重复） | 校验层逐条丢弃 | 过滤后仅保留合法项；过滤后为空则规则兜底 |

> 超时配置：`deepseek.connect-timeout=3s`、`deepseek.timeout=12s`（read），避免外部 LLM 长超时拖垮 BFF 请求。

### 7.3 跨服务调用失败的补偿（交易主链路）

| 场景 | 失败点 | 处理 | 兜底 |
| --- | --- | --- | --- |
| 下单：扣库存成功、落单失败 | `orderRepository.save` 异常 | catch 内 `restoreStock` 补偿 | 补偿再失败 → 日志，人工对账（R-07） |
| 下单：扣库存失败 | `deductStock` 抛库存不足 | 直接上抛，未落单 | 无副作用，无需补偿 |
| 取消：回补库存 | 任一明细 `restoreStock` 抛异常 | 事务回滚（取消不生效） | 提示用户稍后重试 |
| 支付：回调订单服务失败 | `confirmPayment` 异常 | 流水置 `FAILED` + `failReason` + 上抛 | 允许重试发起；不永久卡 PROCESSING |
| 支付：order-service 不可用（网络） | `OrderClient.getOrder/confirmPayment` 连接失败 | 抛"订单服务不可用"，流水 FAILED | 重试 |

---

## 8. 并发与一致性

### 8.1 库存原子扣减（防超卖）

- **实现**：`ProductRepository.deductStock` = 单条 `UPDATE product SET stock = stock - :qty WHERE id = :id AND stock >= :qty`，返回影响行数：
  - `1` → 扣减成功；`0` → 库存不足，抛 400。
- **正确性论证**：
  - `UPDATE` 对目标行加排他锁，同商品并发扣减被 DB 串行化；
  - `stock >= :qty` 条件使"读-改-写"成为**单条原子语句**，杜绝超卖；
  - 影响行数判断比"先查后改"更可靠，天然免疫"读到旧值"。
- **回补**：`restoreStock` 为 `UPDATE ... SET stock = stock + :qty WHERE id = :id`，无条件追加，补偿/取消安全。
- **页面库存仅提示**：BFF 读取的是快照，最终裁决在商品服务原子扣减（ADR-09）。
- **边界用例**（测试关注）：quantity≤0、quantity>stock、并发 N 个请求抢 1 件库存只成功 1 个、cancel 后库存精确回补。

### 8.2 幂等键设计

| 层 | 幂等键 | 机制 | 覆盖场景 |
| --- | --- | --- | --- |
| 订单号 | `order_no` | `UK(order_no)` + 生成规则 `SO+yyyyMMddHHmmss+4位随机` | 订单唯一（随机后缀防同秒冲突，冲突时抛唯一键异常→需重试） |
| 支付流水 | `order_no`（现状）+ 代码防重 | `findByOrderNo` 过滤非 FAILED 存在即拒 | 同一订单重复发起支付 |
| 支付回调 | 订单状态字段 | `confirmPayment`：PAID 直接返回 | 回调重试/乱序 |
| 库存 | 业务动作驱动 | 扣/补均为显式业务动作，不自动重试 | 网络重试需上层判定 |

**演进建议（R-05）**：生产级应将"订单号"与"流水号/幂等键"分离——引入 `payment_no`（流水唯一）+ `biz_no`（幂等键），并以唯一键兜底代码防重，避免"一单多流水可重试"的现状语义依赖代码而非 DB。

### 8.3 支付回调幂等

- 双端协作：`payment-service` 发起前防重（非 FAILED 流水存在即拒）+ `order-service` 回调时幂等（PAID 直接返回）。
- 失败场景闭环：回调抛错 → 流水 FAILED → 用户可重试发起；重试成功则流水重建并回调成功，订单最终 PAID。

### 8.4 分布式事务限制（演示级边界声明）

- **现状**：下单的"扣库存（shopdb_product）"与"落订单（shopdb_order）"、支付的"记流水（shopdb_payment）"与"确认订单（shopdb_order）"均跨库，**无本地事务可覆盖**，存在进程崩溃窗口内的不一致：
  1. 扣减成功但未落单 → 悬挂库存（靠补偿缩小窗口）；
  2. 流水 PROCESSING 但回调未完成 → 订单与流水不一致（靠 FAILED 兜底 + 重试收敛）。
- **本期策略**：失败补偿 + 流水状态 + 幂等回调的最小兜底，明确为**演示级**，不引入 MQ/Saga。
- **生产演进（不实现，仅设计方向）**：
  - **Outbox**：订单侧落库同时写 Outbox 事件表，独立投递到 MQ，消费方回补库存/触发支付；
  - **Saga**：下单 Saga（扣库存 → 落订单 → 失败补偿回补）与支付 Saga（扣款 → 确认 → 失败补偿退款）；
  - 需要：消息队列（RocketMQ/Kafka）、事务表、补偿幂等。
- **决策记录**：本期刻意选择"轻量兜底"而非"引入重型基础设施"，在演示目标下性价比最优（ADR-08）。

---

## 9. 错误处理规范

### 9.1 REST 错误体统一格式

- 所有 REST 接口异常统一返回 `{"message":"..."}`（`ApiError`），HTTP 状态码 400（业务）/500（内部）。
- 实现：各服务 `@RestControllerAdvice`（product/payment/recommend 全包；order 仅 `api` 包）：
  - `BusinessException` → 400 + `message` 透传；
  - 其余 `Exception` → 500 + `"XX服务内部错误：" + message`。
- **下游错误透传**：客户端 `ClientSupport.toBusinessException` 解析对方 `{"message":...}` 转为本地 `BusinessException`，保持"用户能读懂的错误文案"跨服务不丢。

### 9.2 Web 页面 vs REST 的异常隔离（order-service 内）

| 通道 | 处理类 | 行为 |
| --- | --- | --- |
| Web 页面（`web` 包） | `GlobalExceptionHandler`（`@ControllerAdvice(basePackages="...web")`） | 业务异常 → `error.html` 展示 message；其他 → `error.html` 通用提示 + detail |
| REST API（`api` 包） | `OrderApiExceptionHandler`（`@RestControllerAdvice(basePackages="...api")`） | JSON `{"message":...}` |

- 隔离手段：按**包名**限定 `@ControllerAdvice` 作用域，互不干扰——同一个 `BusinessException` 在页面通道渲染 HTML、在 API 通道返回 JSON。
- 页面表单校验：Bean Validation（`OrderForm` @NotNull/@Min/@Max/@NotBlank）+ `BindingResult` 回显错误，不抛 500。

### 9.3 日志规范

| 场景 | 级别 | 关键信息 |
| --- | --- | --- |
| 推荐降级（服务不可用/AI 失败） | WARN | type、异常摘要（`ex.toString()`，避免整栈刷屏） |
| AI 未配置 Key | INFO | 提示走规则推荐 |
| 补偿失败（下单回补库存失败） | WARN/ERROR | orderNo、productId、quantity，标记"人工对账" |
| 支付回调失败置 FAILED | WARN | orderNo、failReason |
| 内部异常 | ERROR | 完整堆栈 + 上下文（orderNo/productId） |
| 请求成功 | DEBUG | 不做业务级 info 打印（当前无统一 traceId，见 R-08） |

**建议（R-08）**：引入 MDC traceId 串联跨服务调用链（orderNo 作为业务 trace），便于定位分布式问题；日志落盘策略按服务 `logback-spring.xml` 配置。

---

## 10. 测试策略（研发视角）

按"单元 → 接口 → 功能/集成"三层金字塔，重点覆盖边界、异常、并发用例。

### 10.1 单元测试（service 层，Mock 下游）

| 服务 | 被测类 | 覆盖重点 |
| --- | --- | --- |
| order-service | `OrderService` | 数量≤0 拒绝；库存不足（Mock `ProductClient.getProduct` 返回低库存）；扣减成功后落单快照金额=单价×数量；**落单失败触发回补**（Mock `save` 抛异常 → 断言 `restoreStock` 被调）；取消订单仅 CREATED 可取消、PAID/CANCELLED 抛错；`confirmPayment` 三态分支（CREATED→PAID、PAID 幂等直接返回不二次写、CANCELLED 抛错）；`listOrderItems` 快照转 DTO |
| payment-service | `PaymentService` | 订单不存在抛错；PAID/CANCELLED 拒绝；**防重复**（存在非 FAILED 流水拒绝，FAILED 允许重试）；卡号尾号计算（16~19 位截尾、非数字剥离、<4 位返回 null）；回调成功置 SUCCESS；**回调失败置 FAILED + failReason + 上抛** |
| product-service | `ProductService` | 扣减成功/库存不足（Mock repository 影响行数 0/1）；数量非法；`restoreStock` 追加 |
| recommend-service | `AiRecommendationService` / `RuleRecommendationService` / `RecommendationService` | **三层校验**：目录外 id 丢弃、已购 id 丢弃、重复 id 去重、（同类）品类不一致丢弃；`stripCodeFence` 剥离 ```json 围栏；未配 Key 返回空；AI 空 → 规则补足 topN；规则排序确定性（分数→价格→id）；互补权重表命中/未命中 |
| 共享 | `ClientSupport` | 对方返回合法/非法/空 `{"message"}` 的转换 |

**Mock 工具**：Mockito（`when/mock`）+ JUnit 5；repository 用 Mockito 或内存 H2（可选）。

### 10.2 接口测试（MockMvc / REST）

- **product-service**：`GET /api/products`、`GET /{id}`、`POST /{id}/stock/deduct`（成功/400 库存不足/数量非法）、`POST /{id}/stock/restore`。
- **payment-service**：`POST /api/payments`（成功 SUCCESS、重复支付 400、订单不存在 400、回调失败 → 流水 FAILED + 400）、`GET /api/payments/{orderNo}`。
- **recommend-service**：`POST /api/recommendations`（complement/similar、空 catalog 返回空、AI mock 返回含幻觉条目被过滤、source 标记）。
- **order-service API**：`GET /api/orders/{orderNo}`、`POST /confirm-payment`（幂等两次调用结果一致）。
- 断言重点：HTTP 状态码、`{"message"}` 错误体、幂等性（重复请求副作用为零）。

### 10.3 功能/集成测试（跨服务 或 Mock client）

- **方式 A（真实跨服务）**：本地起四个服务 + MySQL，用脚本/工具驱动全链路：下单 → 支付（三种方式）→ 支付成功页出现同类 + AI 推荐 → 取消订单库存回补。
- **方式 B（Mock 下游 client）**：以 `order-service` 为主，`@MockBean` 注入 `ProductClient/PaymentClient/RecommendClient`，用 WebFlux `WebTestClient` 或 `TestRestTemplate` 覆盖页面路由（`/products`、`/order/confirm`、`/order/{no}`、`/pay`、`/pay/success`、`/orders`、`/cancel`），断言 Model 中推荐列表为空时页面仍 200 且推荐区块隐藏。
- **重点覆盖用例**：
  - 边界：quantity=1/999/0/负数/超库存；卡号 15/16/19/20 位；金额 × 数量的精度；
  - 异常：推荐服务 500/超时 → 页面正常；支付服务不可用 → 友好提示；订单服务不可用 → 支付流水 FAILED；
  - 并发：**多线程并发扣同一商品库存**（断言不超卖、影响行数语义正确）；并发重复支付同一订单（仅一个成功）；并发重复回调 confirm-payment（幂等）。

### 10.4 测试分层与覆盖重点总结

| 层次 | 工具 | 核心验证目标 |
| --- | --- | --- |
| 单元（service+client+校验） | JUnit 5 + Mockito | 状态机分支、三层幻觉校验、金额计算、尾号脱敏、降级分支 |
| 接口（controller） | Spring MockMvc | 契约（路径/请求/响应/错误体/幂等） |
| 功能/集成 | 真实四服务 / MockBean | 端到端业务流程、故障注入、并发正确性 |

**验收标准**：主链路（下单→支付→推荐）全部用例通过；并发超卖与重复支付用例通过；推荐服务/DeepSeek 故障注入时页面可用性不受损。

---

## 11. 待评审风险点

| 编号 | 风险/决策点 | 级别 | 说明与建议 |
| --- | --- | --- | --- |
| R-01 | `order ⇄ payment` 运行期双向调用 | 中 | 回调语义正确但需评审边界：确保 order 仅暴露最小回调面，杜绝 payment 反向编排业务。若后续引入 MQ，可将回调改为异步事件。 |
| R-02 | `ddl-auto=update` 生产不可控 | 高 | 演示可接受；上线前收敛为 Flyway/Liquibase，由 DBA 评审 DDL 与索引。 |
| R-03 | 卡号处理 | 中 | 当前仅存尾号 4 位（已最小化）；建议补充日志脱敏审计、明确完整卡号的内存生命周期与不落日志约束。 |
| R-04 | 表命名不一致 | 低 | 现状 `product`/`shop_order`/`order_item`/`payment` 未统一前缀；建议规范为统一 `shop_*` 前缀并评估改名成本。 |
| R-05 | 支付"一单多流水"依赖代码防重 | 中 | 现状 `UK(order_no)` 与"FAILED 可重试"存在张力；建议引入 `payment_no`(流水)+`biz_no`(幂等键) 分离，以 DB 唯一键兜底。 |
| R-06 | 状态枚举硬编码判断 | 低 | 状态判断散落在 service 内联分支；建议引入统一状态机守卫（如 `OrderStateMachine.transit(...)`）集中合法流转表，便于测试与维护。 |
| R-07 | 补偿失败仅靠日志对账 | 中 | 下单回补失败无落库记录；建议增加 `stock_compensation_log` 或在失败时生成对账任务，避免静默悬挂库存。 |
| R-08 | 无跨服务 traceId | 低 | 建议引入 MDC + orderNo 透传，串联四服务日志，支撑故障定位。 |
| R-09 | 推荐 `topN`/模型参数无运行时观测 | 低 | 建议对 DeepSeek 调用打点（耗时/成功率/幻觉过滤命中数），评估 flash 效果后再决定是否升 pro。 |

---

## 附：方案核心决策与待评审风险点（≤200 字）

**核心决策**：order-service 作为 BFF 聚合全部页面，订单先原子扣库存再落单、失败尽力回补；支付采用"流水状态 + 幂等回调 + 防重复校验"闭环；推荐三层降级（BFF 吞异常 → AI 失败回退规则 → 规则补足）并做"目录内/非已购/去重（同类加品类一致）"防幻觉；每服务独立 schema 与 BigDecimal 金额保证数据自治与精度；分布式事务明确为演示级，仅以补偿+幂等兜底。**待评审**：order⇄payment 双向回调边界（R-01）、ddl-auto 生产收敛（R-02）、支付幂等键 DB 兜底（R-05）、补偿失败对账落库（R-07）。
