# 动态 trace 采集报告（4 条黄金链路）

> 波 2-C：重建 jar → 启动 5 个服务 → 驱动 4 条黄金链路 → 抓取 `[TRACE]` 日志按 traceId 串联成跨服务调用序列。
> 采集时间：2026-08-12（14:27 ~ 17:10）；关联文档：`02-instrumentation-design.md`（埋点设计）。
> 埋点前缀 `[TRACE]`、traceId 进 MDC、跨服务经 HTTP 头 `X-Trace-Id` 传播——设计细节见 02 文档，本文档只报告实测。

---

## 1. 埋点与注入方式

- **埋点**：mall-common 提供 `TraceFilter`（入站解析 `X-Trace-Id` → MDC）与 `TraceAspect`（AOP 切 `@RestController` / `@Service` 方法，记 ENTER / EXIT / ERROR，独立 logger 名 `TRACE`）；三个 RestClient 构建点挂 `TraceContext.interceptor()` 把 traceId 带出站。
- **注入**：驱动每条链路时给 curl/python 入口显式加 `-H "X-Trace-Id: <id>"`，TraceFilter 续用该值，于是同一 traceId 贯穿 order→product / payment / recommend / consult。逐条链路所用 traceId：

| 链路 | traceId | 业务产物 |
|---|---|---|
| GP1 下单 | `gp1-order` | 订单 `SO202608121427329174`（iPhone 15 Pro ×1） |
| GP2 支付 | `gp2-pay` | 支付流水 SUCCESS（同上订单） |
| GP3 推荐 | `gp3-recommend` | 订单详情页同类推荐（规则推荐 top4） |
| GP4 问诊+处方购药 | `gp4-consult` | 会话 4 + 处方 `RX202608121710129086` + 订单 `SO202608121710198180` |

> 端口：product 8081 / order 8082（BFF 页面入口）/ payment 8083 / recommend 8084 / consult 8085 REST + 8086 Netty WS。
> 健康检查：8082 `GET /`=200；8081 `GET /api/products`=200；8083 `GET /api/payments/xxx`=400（业务异常，服务存活）；8084 `POST /api/recommendations {}`=400；8085 `POST /api/consult/sessions {}`=400；8086 WS 握手成功（Node 客户端连接实测）。
> **冒烟验证埋点**：`curl -H "X-Trace-Id: smoke-1" http://localhost:8082/products` 后，`order.log` 与 `product.log` 均出现 `traceId=smoke-1`，且 order 调 product 时 product 侧同 traceId —— 跨服务传播链路打通。

---

## 2. GP1 下单（traceId=gp1-order）

**入口 curl（BFF 页面流）**：
```bash
curl -H "X-Trace-Id: gp1-order" http://localhost:8082/                                  # 1) 列商品
curl -H "X-Trace-Id: gp1-order" http://localhost:8082/product/1                         # 2) 商品详情（iPhone 15 Pro）
curl -H "X-Trace-Id: gp1-order" "http://localhost:8082/order/confirm?productId=1&quantity=1"  # 3) 确认页
curl -H "X-Trace-Id: gp1-order" -X POST http://localhost:8082/order \
  --data-urlencode "productId=1" --data-urlencode "quantity=1" \
  --data-urlencode "receiverName=ZhangSan" --data-urlencode "receiverPhone=13800138000" \
  --data-urlencode "receiverAddress=Beijing Chaoyang Dist"                                # 4) 提交订单
```
返回 302 → `Location: /order/SO202608121427329174`（下单成功）。

**跨服务调用序列**（order → product）：

| 次序 | 服务 | 类.方法 | 方向 | 耗时(ms) | 结果/备注 |
|---|---|---|---|---|---|
| 1 | order | OrderService.listProducts() | 入/出 | 17 | 代理到商品服务 |
| 1a | product | ProductController.list() → ProductService.listProducts() | 出 | 5 | 商品列表 |
| 2 | order | OrderService.findProduct(Long:1) | 入/出 | 37 | 商品详情页 |
| 2a | product | ProductController.detail() → ProductService.getProduct() | 出 | 10~11 | 按 id 取商品 |
| 3 | order | OrderService.findProduct(Long:1) | 入/出 | 10 | 确认页回填 |
| 3a | product | ProductController.detail() → ProductService.getProduct() | 出 | 3 | |
| 4 | order | OrderService.createOrder() | 入 | — | 下单事务 |
| 4a | order | OrderService.findProduct(Long:1) | — | 9 | 库存/金额预检 |
| 4a′ | product | ProductController.detail() → ProductService.getProduct() | 出 | 3 | |
| 4b | product | ProductController.deduct() → ProductService.deductStock() | 出 | **68** | **原子扣库存（本轮最慢段，事务写）** |
| 4 | order | OrderService.createOrder() | 出 | 219 | 本地落单 + 明细快照 |

**关键 traceId 样例日志行**：
```
[TRACE] traceId=gp1-order OrderService.createOrder() ENTER args=[6 args]
[TRACE] traceId=gp1-order ProductService.deductStock() ENTER args=[Long:1, Integer:1]
[TRACE] traceId=gp1-order ProductService.deductStock() EXIT elapsed=68ms result=ProductDTO@...
[TRACE] traceId=gp1-order OrderService.createOrder() EXIT elapsed=219ms result=Order@...
```

> 说明：`ShopController`（`@Controller`，BFF 页面入口）方法不在 AOP 切点内（设计只切 `@RestController`/`@Service`），故序列从 `OrderService.*` 开始，此为预期。

---

## 3. GP2 支付（traceId=gp2-pay）

**入口 curl**：
```bash
curl -H "X-Trace-Id: gp2-pay" -X POST http://localhost:8082/order/SO202608121427329174/pay \
  --data-urlencode "payMethod=支付宝" --data-urlencode "payBank=招商银行" --data-urlencode "payCardNo=6225 8820 1234 5678"
```
返回 302 → `/order/SO.../pay/success`；支付流水状态 SUCCESS。

**跨服务调用序列**（order → payment → order）：

| 次序 | 服务 | 类.方法 | 方向 | 耗时(ms) | 结果/备注 |
|---|---|---|---|---|---|
| 1 | order | ShopController.pay() | 入 | — | `@Controller` 未埋点（预期） |
| 1 | payment | PaymentController.pay() → PaymentService.executePayment() | 入 | 591 | 模拟支付入口 |
| 1a | order | OrderApiController.getOrder() → OrderService.getOrderDTO() | 出（回） | **227** | **支付前拉订单校验（本轮最慢段，读订单+明细）** |
| 1b | order | OrderApiController.confirmPayment() → OrderService.confirmPayment() | 出（回） | 29 | 订单置 PAID 回调 |
| 1 | payment | PaymentService.executePayment() | 出 | 591 | 流水 SUCCESS |
| 2 | payment | PaymentController.byOrderNo() | 验证 | 8 | 复核流水（非黄金链路步骤） |

**关键 traceId 样例日志行**：
```
[TRACE] traceId=gp2-pay PaymentService.executePayment() ENTER args=[PaymentRequest:...]
[TRACE] traceId=gp2-pay OrderApiController.getOrder() EXIT elapsed=227ms result=OrderDTO@...
[TRACE] traceId=gp2-pay OrderApiController.confirmPayment() EXIT elapsed=29ms result=OrderDTO@...
[TRACE] traceId=gp2-pay PaymentService.executePayment() EXIT elapsed=591ms result=PaymentDTO@...
```

---

## 4. GP3 推荐（traceId=gp3-recommend）

**入口 curl（订单详情页 viewOrder 同步触发同类推荐）**：
```bash
curl -H "X-Trace-Id: gp3-recommend" http://localhost:8082/order/SO202608121427329174
```
页面 200，耗时 ~413ms。

**跨服务调用序列**（order → product + order → recommend）：

| 次序 | 服务 | 类.方法 | 方向 | 耗时(ms) | 结果/备注 |
|---|---|---|---|---|---|
| 1 | order | OrderService.findOrderByOrderNo() | 入/出 | 52 | 查订单 |
| 2 | order | OrderService.listOrderItems() | 入/出 | 4 | 已购明细 |
| 3 | order | OrderService.listProducts() | 出 | 106 | 全量目录 |
| 3a | product | ProductController.list() → ProductService.listProducts() | 出 | 38~40 | |
| 4 | recommend | RecommendationController.recommend() → RecommendationService.recommend() | 出 | 11~14 | similar 模式 |
| 4a | recommend | AiRecommendationService.recommendSimilar() | 出 | 1 | **AI 未配 Key → 返回空（不外呼）** |
| 4b | recommend | RuleRecommendationService.recommendSimilar() | 出 | 6 | 规则补足 topN=4 |

**关键 traceId 样例日志行**：
```
[TRACE] traceId=gp3-recommend RecommendationService.recommend() ENTER args=[RecommendRequest:...]
[TRACE] traceId=gp3-recommend AiRecommendationService.recommendSimilar() EXIT elapsed=1ms result=[]
[TRACE] traceId=gp3-recommend RuleRecommendationService.recommendSimilar() EXIT elapsed=6ms result=[RecommendationItemDTO@...]
[TRACE] traceId=gp3-recommend RecommendationService.recommend() EXIT elapsed=11ms result=RecommendationResponse@...
```

---

## 5. GP4 问诊+处方购药（traceId=gp4-consult）

Rx 商品实测为 **id=32「阿莫西林胶囊」**（任务描述预估 id=9 与库内实际不符，以实际数据为准）。会话 **id=4**，处方 **RX202608121710129086**，凭方下单订单 **SO202608121710198180**。

### 5.1 创建会话（order → consult）
```bash
# 注：body 中文字段用 JSON \u 转义 / python ensure_ascii，避免 git bash GBK 字节破坏 UTF-8 解析
POST http://localhost:8082/api/orders/consult/sessions   {"productId":32,"productName":"阿莫西林胶囊"}
```
返回 `sessionId=4, token=9cb4e179..., status=WAITING`。

| 服务 | 类.方法 | 方向 | 耗时(ms) | 结果/备注 |
|---|---|---|---|---|
| order | OrderApiController.createConsultSession() | 入 | 843 | BFF 转发 + 索引登记 |
| consult | ConsultationController.create() → ConsultationSessionService.createOrReuse() | 出 | **570** | **建会话 + 签 token + 预加载内存态（冷启动首查 DB，本轮最慢单段）** |

### 5.2 WS 聊天（Netty :8086，Node 客户端）
临时脚本（系统临时目录，未落仓库）：`ws://localhost:8086/ws` → `join` → `joined` → 2 条 `message`（`clientMsgId` 用 UUID）→ 均收到 `ack`（seq=1、seq=2）→ 关闭。

```
[recv] {"type":"joined","sessionId":4,"role":"USER","status":"WAITING","lastSeq":0}
[recv] {"type":"ack","clientMsgId":"88194a4b-...","seq":1,"createdAt":"...17:10:03.478"}
[recv] {"type":"ack","clientMsgId":"923d61f5-...","seq":2,"createdAt":"...17:10:03.858"}
```
WS 帧在 **Netty 事件循环线程 `ntLoopGroup-3-2`** 处理，未走 Servlet TraceFilter，**无 MDC → traceId 记为 `-`**（预期）：
```
[TRACE] traceId=- MessageService.sendTextMessage() ENTER args=[Long:4, MessageSenderType:USER, String:88194a4b-..., String:您好，我咳嗽三天了, NioSocketChannel:...]
[TRACE] traceId=- MessageService.sendTextMessage() EXIT elapsed=11ms result=null   # 入批量写队列，事件循环零阻塞
[TRACE] traceId=- SessionLifecycle.onDisconnect() EXIT elapsed=3ms result=null
```

### 5.3 医生接单（consult）
```bash
POST http://localhost:8085/api/consult/sessions/4/accept   {"doctorId":"demo-doctor","doctorName":"演示医生"}
```

| 服务 | 类.方法 | 耗时(ms) | 结果/备注 |
|---|---|---|---|
| consult | ConsultationController.accept() | 83 | WAITING→ONGOING |
| consult | ConsultationSessionService.accept() | 80 | CAS 接单 + 签发医生 token |
| consult | MessageService.enqueueSystemMessage() | 2 | 「医生 演示医生 已接入本次问诊」 |

### 5.4 医生开方（consult）
```bash
POST http://localhost:8085/api/consult/prescriptions
{"sessionId":4,"items":[{"productId":32,"productName":"阿莫西林胶囊","dosage":"0.5g","frequency":"每日3次","quantity":1}],"note":"按时服药"}
```
返回 `no=RX202608121710129086, status=ACTIVE`。

| 服务 | 类.方法 | 耗时(ms) | 结果/备注 |
|---|---|---|---|
| consult | ConsultationController.prescribe() | 101 | ONGOING→PRESCRIPTION_ISSUED |
| consult | PrescriptionService.prescribe() | 97 | 开方 + 置终态（同事务） |
| consult | MessageService.enqueueSystemMessage() | 0 | 「医生已开具处方，处方号：…」 |
| consult | SessionLifecycle.finalizeTerminal() | 18 | 会话进 PRESCRIPTION_ISSUED |

### 5.5 凭处方下单（order → product + consult）
```bash
GET  /order/confirm?productId=32&quantity=1&prescriptionNo=RX202608121710129086
POST /order   productId=32&quantity=1&receiverName=WangWu&receiverPhone=13900139000&receiverAddress=Shanghai Pudong&prescriptionNo=RX202608121710129086
```
返回 302 → `/order/SO202608121710198180`。验证：处方 status=USED（consumedOrderNo=SO…）、会话 status=ENDED（endReason=COMPLETED）。

| 次序 | 服务 | 类.方法 | 方向 | 耗时(ms) | 结果/备注 |
|---|---|---|---|---|---|
| 1 | order | OrderService.createOrder() | 入 | 217 | Rx 下单事务（Saga 补偿路径） |
| 1a | order | OrderService.findProduct(Long:32) | — | 10 | 价格/库存预检 |
| 1a′ | product | ProductController.detail() → ProductService.getProduct() | 出 | 2~4 | |
| 1b | product | ProductController.deduct() → ProductService.deductStock() | 出 | **49** | 原子扣库存 |
| 1c | consult | ConsultationController.consume() → PrescriptionService.consume() | 出 | **51** | **处方一次性消费 USED（CAS 防复用）** |
| 1c′ | consult | SessionLifecycle.finalizeTerminal() | — | 0 | 会话 ENDED / COMPLETED |
| 1 | order | OrderService.createOrder() | 出 | 217 | 本地落单 + 明细快照 |

**关键 traceId 样例日志行**：
```
[TRACE] traceId=gp4-consult OrderService.createOrder() ENTER args=[6 args]
[TRACE] traceId=gp4-consult ProductService.deductStock() EXIT elapsed=49ms result=ProductDTO@...
[TRACE] traceId=gp4-consult PrescriptionService.consume() EXIT elapsed=51ms result=PrescriptionDTO@...
[TRACE] traceId=gp4-consult SessionLifecycle.finalizeTerminal() EXIT elapsed=0ms result=null
[TRACE] traceId=gp4-consult OrderService.createOrder() EXIT elapsed=217ms result=Order@...
```

---

## 6. 4 条链路 traceId 跨服务连续性核对

用 `grep '<traceId>' logs/{product,order,payment,recommend,consult}.log | grep '\[TRACE\]'` 全量复核，四个 traceId 均贯穿到相应下游服务，无断链：

| traceId | 贯穿服务 | 连续性 |
|---|---|---|
| gp1-order | order → product | 完整（list/detail/deduct 均为同 id） |
| gp2-pay | order → payment → order（getOrder + confirm-payment 两段回调同 id） | 完整 |
| gp3-recommend | order → product、order → recommend | 完整 |
| gp4-consult | order → product、order → consult（create/consume）；WS 帧 traceId=- | REST 段完整 |

> 只采集了本次 4 条链路的日志；`logs/*.log` 中残留的上一波 Agent smoke 请求（如 `smoke-1`、`2865fd46`、`15967362`）均未计入。

---

## 7. WS 线程 traceId=- 的说明

- WS 帧由 Netty 事件循环线程（`ntLoopGroup-*`）处理，**不经过 Servlet 容器的 TraceFilter**，MDC 为空 → `TraceContext.getTraceId()` 返回空 → AOP 日志记为 `-`。
- 这是设计预期的表现（`02-instrumentation-design.md` §7「traceId 为空记 `-`」），不影响功能：join/message/ack/prescription_issued 等帧照常收发。
- 若需对 WS 帧做链路追踪，需在 `WsFrameHandler.channelRead0` 手工从帧内带 traceId 写 MDC（本轮未实现，属已知限制）。

---

## 8. 观测到的耗时特征

1. **最慢单段：GP4 创建会话 `ConsultationSessionService.createOrReuse()` = 570ms**。冷启动后首次执行（建会话 + 签 token + 预加载内存态 `SessionState` + `MAX(seq)` 查询），含 JPA/Hibernate 首查编译与多步 DB 交互；后续同类操作均回落到 ~10ms 级。
2. **GP2 支付前拉单 `OrderApiController.getOrder()` = 227ms**：order→order 自身 `getOrderDTO()`（订单 + 明细懒加载）往返，为支付链路主要耗时；整体 `executePayment` 591ms 中两次对 order 的回调合计约 256ms。
3. **数据库批量/写路径稳定在 50~70ms**：`deductStock()`（GP1 68ms / GP4 49ms）、`consume()`（51ms）均为事务写，量级一致。
4. **DeepSeek 外呼本轮未发生**：`deepseek.api-key` 为空 → `DeepSeekClient.isEnabled()=false` → `AiRecommendationService` 直接返回空，推荐完全走规则（6~24ms）。故「AI 外呼慢」这一预期观测点未在本轮出现；如需观测，配置 Key 后重跑 GP3 即可。
5. **事件循环零阻塞得到印证**：WS 消息 `sendTextMessage()` 在 Netty 线程上仅 0~11ms（入批量写队列即返回），符合 ADR-IM-04 设计。

---

## 9. 遇到的问题与解决

| 问题 | 解决 |
|---|---|
| 重建后健康检查 REST-only 服务 `GET /` 返回 500 | 属正常：product/payment/recommend/consult 无根映射，`NoResourceFoundException` 被异常处理器置 500；改用真实 API 端点做健康检查（见 §1）。 |
| 中文 JSON body 经 git bash curl 提交被拒（500「订单服务内部错误」） | git bash 以 GBK 字节发中文导致 JSON 非 UTF-8，Jackson 反序列化失败、方法未进 AOP → 无 TRACE 日志。改用 python `ensure_ascii=True`（`\uXXXX` 转义）发送，成功。 |
| 日志文件 mtime 滞后、偶现"看不到最新 TRACE" | Java stdout 重定向到文件为块缓冲（约 8KB），少量日志未即时落盘；多打几条请求或稍候即 flush，不影响采集。 |
| 任务预估 Rx 药品 id=9 | 库内阿莫西林实际为 **id=32**，以运行时商品 API 实测为准。 |
