# 静态知识图谱（GitNexus）

> 多 Agent 大厂工作流 · 静态分析 Agent 产出
> 产物：GitNexus 代码知识图谱索引 + 全量静态调用链分析（Java / Spring Boot 微服务商城）
> 对应动态采集：`docs/trace/02-instrumentation-design.md`、`docs/trace/03-…`（综合流程图）

## 1. GitNexus 简介

[GitNexus](https://github.com/abhigyanpatwari/GitNexus)（npm 包 `gitnexus`，v1.6.6）是一个**浏览器端代码知识图谱引擎**：把仓库索引成一张可查询的知识图谱（节点 = 类/方法/属性/文件，边 = 调用/继承/引用/HTTP 契约），并提供本地 Web UI（`gitnexus serve`）与 MCP/CLI 查询接口。**Java 是核心支持语言**：基于 Tree-sitter 全量解析 + 类型解析 + 调用链 + 继承/MRO，能还原跨服务的 REST 调用链（http-pattern 抽取）。

## 2. 安装与命令

| 项 | 值 |
| --- | --- |
| 版本 | gitnexus@1.6.6 |
| Node / npm | v24.15.0 / 11.12.1（**必须全局安装**，npm 11.x 的 `npx` reify 会崩溃） |
| 安装命令 | `GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 npm install -g gitnexus@latest` |
| 验证 | `gitnexus --version` → `1.6.6` |
| 建索引 | `GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 GITNEXUS_LBUG_EXTENSION_INSTALL=load-only gitnexus analyze --index-only --skip-agents-md` |
| Web UI | `gitnexus serve --port 4747` → http://localhost:4747（测通后已停止） |

**环境规避**（本机 win32 x64 无 Visual Studio C++ 工具链，可选语法原生编译失败）：
- `GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1`：跳过可选语法（Kotlin/Swift/Dart）的安装期编译；
- 由于 gitnexus 1.6.6 的 `analyze` 会**静态 import** `swift/dart/kotlin` 的 query 模块，跳过安装后运行期仍会因缺包崩溃。解法（环境层，不改项目）：
  - Swift：执行 `node scripts/materialize-vendor-grammars.cjs`，将 `vendor/tree-sitter-swift`（自带 win32-x64 prebuild）复制进 `node_modules`，加载成功；
  - Dart / Kotlin：无 win32 prebuild，无法编译。本项目为纯 Java 仓库（0 个 `.dart`/`.kt` 文件），故对 `node_modules/tree-sitter-dart` 与新建的 `node_modules/tree-sitter-kotlin` 提供**桩模块**（复用 `tree-sitter-java` 的 Language 类，静态 import 可解析、惰性解析接口不会被触发），并给 `http-patterns/kotlin.js` 的插件构建加 try/catch 使其按设计降级为 `null`；
- `GITNEXUS_LBUG_EXTENSION_INSTALL=load-only`：离线策略，LadybugDB **FTS 扩展不可用**（BM25 全文搜索关闭），但知识图谱完整可用；
- **不用 `--embeddings`**：规避 onnxruntime 原生依赖，图谱不受影响（embeddings=0）。

## 3. 图谱统计

### 3.1 analyze 输出（核心统计）

```
Repository indexed successfully (23.2s)
2,958 nodes | 8,197 edges | 131 clusters | 253 flows
```

来自 `meta.json`：`files=176, nodes=2958, edges=8197, communities=131, processes=253, embeddings=0`。

### 3.2 节点类型分布（全仓库）

| 节点类型 | 数量 | 说明 |
| --- | --- | --- |
| Method | 1224 | 方法 |
| Property | 502 | 属性/字段 |
| Section | 322 | 文档/Markdown 章节 |
| Process | 253 | 抽取出的执行流程（含跨服务调用流） |
| File | 176 | 文件 |
| Folder | 154 | 目录 |
| Class | 125 | 类 |
| Community | 110 | 社区/聚类（模块边界） |
| Constructor | 55 | 构造器 |
| Function | 19 | 函数（order-service 内 Thymeleaf 工具函数等） |
| Variable | 7 | 变量 |
| Interface | 6 | 接口 |
| Enum | 5 | 枚举 |

### 3.3 文件/语言分布

| 扩展名 | 数量 | 说明 |
| --- | --- | --- |
| .java | 127 | Java（核心，Tree-sitter 全量解析） |
| .md | 13 | 文档（Section 节点） |
| .html | 11 | Thymeleaf 模板 |
| .properties | 9 | 配置 |
| .xml | 7 | Maven/MyBatis |
| .cmd | 3 | 启动脚本 |
| (无扩展) | 2 | mvnw / .gitignore 等 |
| .sql | 1 | init.sql |
| .js / .css / .mockmaker / .imports | 各 1 | 辅助 |

语言结论：**Java 占绝对主导**——125 个 Class、1224 个 Method、55 个 Constructor 全部来自 `.java`；其余为配置/模板/脚本的 File/Section 节点。

## 4. 每服务 symbol / 包结构

Java 文件数（`src/main/java`）：mall-common 28 · order-service 18 · product-service 7 · payment-service 9 · recommend-service 10 · consult-service 29。

| 模块 | Class | Method | Constructor | Property | Interface | Enum | Function | Variable | File 节点 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mall-common | 26 | 241 | 12 | 115 | 0 | 4 | 0 | 0 | 32 |
| order-service | 23 | 273 | 9 | 82 | 1 | 0 | 19 | 2 | 40 |
| product-service | 9 | 86 | 5 | 19 | 1 | 0 | 0 | 0 | 13 |
| payment-service | 10 | 82 | 4 | 33 | 1 | 1 | 0 | 3 | 15 |
| recommend-service | 19 | 134 | 8 | 56 | 0 | 0 | 0 | 2 | 16 |
| consult-service | 38 | 408 | 17 | 197 | 3 | 0 | 0 | 0 | 41 |
| **合计** | **125** | **1224** | **55** | **502** | **6** | **5** | **19** | **7** | **157*** |

\* 另有 19 个 File 节点在根/顶级目录（README、pom.xml、run.cmd、docs 等），故全局 File=176。

包结构（`com.example.mall.<module>`）：

- **mall-common**（共享库）：`common.exception`（BusinessException）、`common.model`（全部 DTO/枚举）、`common.trace`（TraceAspect/TraceContext/TraceFilter/TraceAutoConfiguration，动态埋点注入点）。
- **order-service**（BFF，端口 8082）：`web`（ShopController/ConsultController 页面）、`api`（OrderApiController REST）、`client`（ProductClient/PaymentClient/RecommendClient/ConsultClient + RestClientConfig）、`service`（OrderService）、`repository`、`form`、`model`、`consult`（ConsultSessionIndex BFF 内存索引）。
- **product-service**（端口 8081）：`controller`（ProductController）、`service`（ProductService）、`repository`（ProductRepository）、`model`（Product）、`config`（DataInitializer）。
- **payment-service**（端口 8083）：`controller`（PaymentController）、`service`（PaymentService）、`client`（OrderClient）、`repository`（PaymentRepository）、`model`（Payment/PaymentStatus）。
- **recommend-service**（端口 8084，无数据库）：`service`（RecommendationService 编排）、`ai`（AiRecommendationService + DeepSeekClient/DeepSeekChatRequest/Response）、`rule`（RuleRecommendationService）、`config`（DeepSeekProperties）、`controller`。
- **consult-service**（REST 8085 / WS 8086）：`controller`（ConsultationController）、`service`（ConsultationSessionService/MessageService/PrescriptionService/SessionLifecycle/SessionState…）、`netty`（WsServer/WsFrameHandler/WsFrameCodec/WsNotifier/WsSessionRegistry/WsPipelineInitializer/OriginCheckHandler）、`batch`（MessageWriteQueue 写队列）、`repository`、`model`、`config`。

## 5. 服务依赖矩阵（REST / WS）

基于源码调用点 + GitNexus http 契约边核实：

| 上游 \ 下游 | product | order | payment | recommend | consult | DeepSeek(外部) |
| --- | --- | --- | --- | --- | --- | --- |
| **浏览器** | — | ✅ HTTP 页面（唯一入口 :8082） | — | — | ✅ WS 直连 ws://localhost:8086 | — |
| **order-service** | ✅ `ProductClient` | — | ✅ `PaymentClient` | ✅ `RecommendClient` | ✅ `ConsultClient` | — |
| **product-service** | — | — | — | — | — | — |
| **payment-service** | — | ✅ `OrderClient`（confirm-payment 回调） | — | — | — | — |
| **recommend-service** | — | — | — | — | — | ✅ `DeepSeekClient` |
| **consult-service** | — | — | — | — | — | — |

要点：
- order-service 是 **BFF 唯一入口**，聚合商品/支付/推荐/问诊四个下游。
- payment-service → order-service 是**支付确认回调**（`/api/orders/{orderNo}/confirm-payment`）。
- consult-service **无任何出站服务调用**（自包含：MySQL + Netty WS + REST）；说明文档中提到的「consult→product」在代码中**不存在**。
- recommend-service 仅出站到外部 DeepSeek API，不依赖本仓库其他服务。
- browser→consult 为 **WebSocket 直连**（:8086），但 token 需经 order-service BFF（`ConsultClient.createSession`/`refreshUserToken`）签发。

## 6. 黄金链路静态调用链（4 条）

> 路径相对仓库根，`文件:行号`。跨服务跳转 = RestClient `POST/GET` 内部 HTTP。

### 6.1 下单（含 Rx 处方药 + Saga 补偿）

```
ShopController.createOrder(POST /order)                     order-service/web/ShopController.java:89
  └─ OrderService.createOrder(productId,quantity,…,prescriptionNo)   order-service/service/OrderService.java:95
       ├─ productClient.getProduct(productId)                order-service/client/ProductClient.java:43
       ├─ ① 原子扣库存 productClient.deductStock(id,qty)     OrderService.java:114 → ProductClient.java:57
       │     └─ POST /api/products/{id}/stock/deduct         product-service/controller/ProductController.java:42
       │          └─ ProductService.deductStock              product-service/service/ProductService.java:38
       │               └─ productRepository.deductStock(原子 UPDATE WHERE stock>=qty)  repository/ProductRepository.java:20
       ├─ ② [Rx] ConsultClient.consumePrescription(no,orderNo,items)  OrderService.java:128 → ConsultClient.java:92
       │     └─ POST /api/consult/prescriptions/consume      consult-service/controller/ConsultationController.java:100
       │          └─ PrescriptionService.consume（CAS USED，一次性）   consult-service/service/PrescriptionService.java:143
       ├─ ③ 本地落单 orderRepository.save(order)             OrderService.java:166
       └─ 失败补偿（P1-01）：
            ├─ 扣库存成功但处方消费失败 → restoreStockQuietly → ProductClient.restoreStock  OrderService.java:132/187 → ProductClient.java:71
            ├─ 落单失败且已消费处方 → ConsultClient.restorePrescription（USED→ACTIVE）OrderService.java:173 → ConsultClient.java:102
            │     └─ POST /api/consult/prescriptions/{no}/restore → ConsultationController.restore:106 → PrescriptionService.restore:189
            └─ 随后 restoreStockQuietly 回补库存              OrderService.java:181
```

### 6.2 支付（PROCESSING → 回调 → SUCCESS）

```
ShopController.pay(POST /order/{orderNo}/pay)                order-service/web/ShopController.java:148
  └─ PaymentClient.executePayment(orderNo,payMethod,…)       order-service/client/PaymentClient.java:27
       └─ POST /api/payments                                 payment-service/controller/PaymentController.java:27
            └─ PaymentService.executePayment                 payment-service/service/PaymentService.java:40
                 ├─ orderClient.getOrder(orderNo)（校验订单）    PaymentService.java:43 → OrderClient.java:41
                 │     └─ GET /api/orders/{orderNo} → OrderApiController.getOrder:44 → OrderService.getOrderDTO:290
                 ├─ 落支付流水 saveAndFlush（置 PROCESSING）    PaymentService.java:82
                 ├─ 回调 orderClient.confirmPayment(orderNo,confirm)  PaymentService.java:99 → OrderClient.java:55
                 │     └─ POST /api/orders/{orderNo}/confirm-payment  order-service/api/OrderApiController.java:50
                 │          └─ OrderService.confirmPayment（幂等：CREATED→PAID）  order-service/service/OrderService.java:268
                 ├─ 回调失败 → 流水置 FAILED + 上抛           PaymentService.java:100-105
                 └─ 成功 → 流水置 SUCCESS                    PaymentService.java:107
```

### 6.3 推荐（AI → 规则 → 降级）

```
ShopController.viewOrder / paySuccess                       order-service/web/ShopController.java:116 / :162
  ├─ RecommendClient.recommendSimilar(purchased,catalog)      ShopController.java:122 / :176 → client/RecommendClient.java:44
  └─ RecommendClient.recommend(purchased,catalog)             ShopController.java:180 → client/RecommendClient.java:39
       （paySuccess 中两者并行 CompletableFuture，orTimeout(2s) 降级空列表，RecommendClient.java:175-182）
       └─ POST /api/recommendations（type=similar|complement）  RecommendClient.java:54
            └─ RecommendationController.recommend             recommend-service/controller/RecommendationController.java:26
                 └─ RecommendationService.recommend           recommend-service/service/RecommendationService.java:43
                      ├─ ① AI 优先 aiService.recommendSimilar/recommend   RecommendationService.java:62
                      │     └─ AiRecommendationService.recommendInternal   recommend-service/ai/AiRecommendationService.java:55
                      │          └─ DeepSeekClient.chatCompletion（POST /chat/completions）  ai/DeepSeekClient.java:51
                      ├─ ② 不足 topN 用规则补足 ruleService.recommendSimilar/recommend   RecommendationService.java:75-77
                      │     └─ RuleRecommendationService.recommend/recommendSimilar      recommend-service/rule/RuleRecommendationService.java:150 / :198
                      └─ ③ 任一异常 → 吞掉并回退/返回已收集结果（绝不 500）
```

### 6.4 问诊 + 处方购药（会话/WS/接诊/开方/消费）

```
页面入口
  ConsultController.consultPage(role=user|doctor)            order-service/web/ConsultController.java:43
  OrderApiController.createConsultSession(POST /api/orders/consult/sessions)  order-service/api/OrderApiController.java:59
    └─ ConsultClient.createSession(productId,productName)     order-service/client/ConsultClient.java:44
         └─ POST /api/consult/sessions                       consult-service/controller/ConsultationController.java:53
              └─ ConsultationSessionService.createOrReuse（同药品 WAITING/ONGOING 复用，签发 user WS token）  service/ConsultationSessionService.java:52

WebSocket 实时聊天（ws://localhost:8086，browser 直连）
  WsFrameHandler.channelRead0（join/message/prescription/end 分派）  consult-service/netty/WsFrameHandler.java:78
    ├─ handleJoin：token 校验 + 会话锁内「关旧绑新」           WsFrameHandler.java:111
    ├─ handleMessage → MessageService.sendTextMessage（事件循环内 校验→分配 seq→写队列）  WsFrameHandler.java:145 → service/MessageService.java:54
    └─ handlePrescription → PrescriptionService.prescribe（卸载到独立执行器）  WsFrameHandler.java:163 → service/PrescriptionService.java:76

接诊（医生）
  ConsultationController.accept(POST /api/consult/sessions/{id}/accept)  controller/ConsultationController.java:64
    └─ ConsultationSessionService.accept（CAS WAITING→ONGOING + 签发医生 token + SYSTEM 广播）  service/ConsultationSessionService.java:88

开方（医生）
  OrderApiController.writePrescription / ConsultationController.prescribe   order-service/api/OrderApiController.java:67 → consult-service/controller/ConsultationController.java:94
    └─ PrescriptionService.prescribe → doPrescribe（同事务 处方 INSERT + 会话→PRESCRIPTION_ISSUED，提交后广播）  service/PrescriptionService.java:76 / :106

处方消费（Rx 下单，链入 6.1 ②）
  ConsultationController.consume(POST /api/consult/prescriptions/consume)  controller/ConsultationController.java:100
    └─ PrescriptionService.consume（CAS USED+回写 orderNo；成功后会话 PRESCRIPTION_ISSUED→ENDED(COMPLETED)）  service/PrescriptionService.java:143
```

## 7. 如何重新 analyze / serve

```bash
# 重新建索引（--index-only 只写 .gitnexus/，不碰 AGENTS.md/CLAUDE.md/skills）
cd D:\work\0802\0802-4
GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 GITNEXUS_LBUG_EXTENSION_INSTALL=load-only gitnexus analyze --index-only --skip-agents-md

# 起 Web UI（默认 4747，测完即停，勿长期占用端口）
gitnexus serve --port 4747   # 浏览器打开 http://localhost:4747
# Ctrl+C 停止

# 查询知识图谱（CLI）
gitnexus status                                  # 索引状态
gitnexus query "下单"                             # 按概念搜执行流
gitnexus context OrderService.createOrder         # 符号 360° 视图（调用者/被调）
gitnexus cypher "MATCH (n:Method) RETURN n.name AS m LIMIT 10"   # 原始 Cypher
```

索引产物位于 `.gitnexus/`（已加入 `.gitignore`，本地可重建，不入库）；`.gitnexusrc` 记录 analyze 选项（`indexOnly/skipAgentsMd/embeddings=false/defaultBranch=main`）。

## 8. 限制与说明

- **FTS/BM25 全文搜索关闭**：因 `load-only` 离线策略，LadybugDB FTS 扩展未安装；如需全文检索，联网后执行 `GITNEXUS_LBUG_EXTENSION_INSTALL=auto gitnexus analyze --repair-fts`。
- **可选语法（Kotlin/Swift/Dart）不可用**：win32 无 C++ 工具链，且本项目纯 Java，无 `.kt/.swift/.dart` 文件，不影响 Java 图谱完整性。
- **embeddings=0**：未启用语义向量（规避 onnxruntime），图谱/调用链不受影响。
- 跨服务调用链基于静态解析的 RestClient URI 契约，动态时序（线程切换、异步回写、WS 帧）以动态 trace 报告为准（`docs/trace/03-…`）。
