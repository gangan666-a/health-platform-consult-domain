# simple-mall 新人上手指南

> 适用：刚接手 `D:\work\0802\0802-4` 的开发者。目标是让新人在 **30 分钟内**知道：代码在哪、每个模块干嘛、一条业务请求怎么穿过 5 个服务、怎么跑起来、怎么排障。
> 技术栈：Java 25 · Spring Boot 3.5.16 · Spring MVC + Thymeleaf · Spring Data JPA · MySQL 8.4 · Netty WebSocket · DeepSeek API。
> 关联文档：`docs/13-…` 下 PRD（01/07）、设计（02/08）、评审（03/09）、测试（04~05/10）、发布（06/12）；`docs/trace/` 下静态图谱（01）、埋点设计（02）、动态 trace（03）、**综合流程图（04，强烈建议先看）**。

---

## 1. 目录 / 模块结构（6 个 Maven 模块）

| 模块 | 类型 | 端口 | 数据库 | 一句话职责 |
| --- | --- | --- | --- | --- |
| `mall-common` | 共享库 | — | — | 全部 DTO / 枚举 / `BusinessException` / **动态埋点（TraceFilter/TraceAspect）**，被 5 个服务 compile 依赖 |
| `product-service` | Spring Boot 纯 REST | 8081 | `shopdb_product` | 商品列表/详情/原子扣减库存/回补库存 |
| `order-service` | Spring Boot **BFF** | 8082 | `shopdb_order` | **浏览器唯一入口**：全部 Thymeleaf 页面 + 订单 REST API + 转发四个下游 |
| `payment-service` | Spring Boot 纯 REST | 8083 | `shopdb_payment` | 模拟支付：校验订单→落流水→回调订单服务确认 |
| `recommend-service` | Spring Boot 纯 REST | 8084 | **无** | AI 智能导购：DeepSeek 优先 + 规则补足，无 Key 自动降级 |
| `consult-service` | Spring Boot REST + Netty WS | **8085 REST / 8086 WS** | `shopdb_consult` | 在线问诊：会话/消息/医生接诊/开方/处方一次性消费 |

目录骨架（路径相对仓库根）：

```
0802-4/
├── pom.xml                      # 父聚合 pom（6 个模块）
├── init.sql                     # 建 4 个库（shopdb_product/order/payment/consult）+ 授权 shop 用户
├── run.cmd                      # 一键启动：MySQL → 建库检查 → 打包 → 起 5 服务 → 打开 :8082
├── start-services.cmd           # 复用已打包 jar 直接起 5 服务
├── mall-common/src/main/java/com/example/mall/common/{model,exception,trace}
├── product-service/.../com/example/mall/productservice/{controller,service,repository,model,config}
├── order-service/.../com/example/mall/orderservice/{web,api,client,service,repository,form,model,consult}
├── payment-service/.../com/example/mall/paymentservice/{controller,service,client,repository,model}
├── recommend-service/.../com/example/mall/recommendservice/{controller,service,ai,rule,config}
├── consult-service/.../com/example/mall/consultservice/{controller,service,netty,batch,repository,model,config}
└── docs/                         # 需求/设计/评审/测试/发布 + docs/trace/（静态+动态+流程图）
```

**关键点**：
- 各服务数据库**相互独立**（`init.sql:9-12`），跨服务一致性靠「支付确认回调」+「下单 Saga 补偿」，**没有分布式事务**。
- `mall-common` 的根包 `com.example.mall.common` 是各服务扫描根包的**兄弟包**，默认不会被组件扫描拾取——动态埋点因此用 **AutoConfiguration.imports** 注册（`docs/trace/02` §4）。新人在 mall-common 里加 `@Component` 对下游**不生效**，要用 `@AutoConfiguration`。

---

## 2. 核心业务流（一句话版）

- **普通药购物流**：浏览器 → order-service(:8082) 页面 → **下单**（order 调 product 原子扣库存 + 本地落单）→ **支付**（order 调 payment，payment 回调 order 置 PAID）→ **推荐**（order 调 recommend，AI/规则返回同类+互补）。
- **处方药购物流（Rx）**：浏览器先 → **在线问诊**（order 转发建会话 → 浏览器 WS 直连 :8086 聊天 → 医生接单 → 开方）→ 拿处方号后 **凭方下单**（order 扣库存 + consult **一次性消费**处方 → 会话自动结束），**无处方号一律拒绝下单**（fail-closed）。

---

## 3. 关键代码地图（业务 → Controller → Service → Client/Repository）

> 路径相对仓库根，`文件:行号` 为入口方法。跨服务跳转 = RestClient 内部 HTTP。

| 业务 | 入口 Controller | Service | Client / 下游 | 备注 |
| --- | --- | --- | --- | --- |
| 商品列表/详情/确认页 | `order-service/web/ShopController.java:47/54/60` | `OrderService.findProduct` `service/OrderService.java:62` | `ProductClient.getProduct` `client/ProductClient.java:43` → `product-service/controller/ProductController.java:33` → `service/ProductService.java:30` | BFF 代理，页面不直连下游 |
| **下单（含 Rx）** | `ShopController.createOrder` `web/ShopController.java:89` | `OrderService.createOrder` `service/OrderService.java:95` | ①`ProductClient.deductStock` `client/ProductClient.java:57` → `product-service/controller/ProductController.java:42` → `ProductService.deductStock:38` → `ProductRepository.deductStock:20`；②Rx：`ConsultClient.consumePrescription` `client/ConsultClient.java:92` → `consult-service/controller/ConsultationController.java:100` → `PrescriptionService.consume:143`；③本地 `orderRepository.save` `OrderService.java:166` | 先扣库存→消费处方→落单；失败补偿（restoreStock / restorePrescription）`OrderService.java:132/173/181` |
| **支付** | `ShopController.pay` `web/ShopController.java:148` | （BFF 直连） | `PaymentClient.executePayment` `client/PaymentClient.java:27` → `payment-service/controller/PaymentController.java:27` → `PaymentService.executePayment` `service/PaymentService.java:40` → 回调 `OrderClient.confirmPayment` `payment-service/client/OrderClient.java:55` → `order-service/api/OrderApiController.java:50` → `OrderService.confirmPayment` `service/OrderService.java:268` | 幂等：CREATED→PAID；回调失败流水置 FAILED |
| **推荐** | `ShopController.viewOrder/paySuccess` `web/ShopController.java:116/162` | （BFF 直连，CompletableFuture 并行 + `orTimeout(2s)` 降级 `:175-182`） | `RecommendClient.recommend/recommendSimilar` `client/RecommendClient.java:39/44` → `recommend-service/controller/RecommendationController.java:26` → `RecommendationService.recommend` `service/RecommendationService.java:43` → AI：`AiRecommendationService.java:46/51` → `DeepSeekClient.chatCompletion` `ai/DeepSeekClient.java:51`；规则补足：`rule/RuleRecommendationService.java:150/198` | 容错边界在 `RecommendClient:62-65`：异常吞掉返回空列表 |
| 问诊建会话/聊天页 | `ConsultController.consultPage` `web/ConsultController.java:43`；`OrderApiController.createConsultSession` `api/OrderApiController.java:59` | `ConsultClient.createSession` `client/ConsultClient.java:44` → `consult-service/controller/ConsultationController.java:53` → `ConsultationSessionService.createOrReuse` `service/ConsultationSessionService.java:52` | 页面 ws-url 由 `mall.consult-service.ws-url` 注入；WS 直连 :8086 | BFF 内存索引 `order-service/consult/ConsultSessionIndex.java` |
| **WS 实时聊天** | `consult-service/netty/WsFrameHandler.java:78`（join/message/prescription/end 分派） | `MessageService.sendTextMessage` `service/MessageService.java:54`；`SessionLifecycle` `service/SessionLifecycle.java` | 批量写队列 `batch/MessageWriteQueue.java`；广播 `netty/WsNotifier.java` | 事件循环内零阻塞；traceId=- |
| 医生接诊 | `consult-service/controller/ConsultationController.java:64` | `ConsultationSessionService.accept` `service/ConsultationSessionService.java:88` | 签发医生 WS token | CAS WAITING→ONGOING |
| **开方** | `OrderApiController.writePrescription` `api/OrderApiController.java:67` → `ConsultationController.prescribe` `controller/ConsultationController.java:94` | `PrescriptionService.prescribe/doPrescribe` `service/PrescriptionService.java:76/106` | 同事务：处方 INSERT + 会话→PRESCRIPTION_ISSUED | 终态收尾 `finalizeTerminal` |
| 处方一次性消费/补偿 | `ConsultationController.consume` `controller/ConsultationController.java:100`；restore `:106` | `PrescriptionService.consume` `service/PrescriptionService.java:143`；`restore` `:189` | 单条 CAS UPDATE；restore 仅「被该订单消费」可回退 | 消费成功→会话 ENDED(COMPLETED) |
| **动态埋点（贯穿全部）** | — | — | `mall-common/trace/{TraceContext,TraceFilter,TraceAspect,TraceAutoConfiguration}.java` | AOP 切 `@RestController`/`@Service`，`[TRACE]` 日志 + `X-Trace-Id` 跨服务传播 |

---

## 4. 如何本地运行

### 4.1 一键启动（推荐）

```bash
双击 run.cmd
```
`run.cmd` 做的事：钉死 JDK 25 → 启动 MySQL → 检查库是否就绪（以 `shopdb_product` 为探针）→ 首次 `mvnw.cmd -DskipTests clean package` → `start` 起 5 个服务（各自独立窗口）→ 10s 后打开浏览器 `http://localhost:8082`。

### 4.2 手动启动（首次需建库）

```bash
D:\MySQL\start-mysql.cmd                                  # 1) 先启 MySQL
mysql -uroot -p < init.sql                                # 2) 建 4 个库并授权（或用 DBeaver 执行）
set JAVA_HOME=D:\jdk25\jdk-25.0.2                         # 3) 钉死 JDK 25（本机默认是 1.8）
mvnw.cmd -DskipTests clean package                        # 4) 打包（mall-common → 5 服务）
start "product"   java -jar product-service\target\product-service-0.0.1-SNAPSHOT.jar
start "order"     java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
start "payment"   java -jar payment-service\target\payment-service-0.0.1-SNAPSHOT.jar
start "recommend" java -jar recommend-service\target\recommend-service-0.0.1-SNAPSHOT.jar
start "consult"   java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar
```

启动后访问 **http://localhost:8082**（所有页面入口）。

> 常用页面路由：`/` 商品列表 · `/order/confirm?productId=1&quantity=1` 确认下单 · `/order/{orderNo}` 订单详情（含推荐）· `/order/{orderNo}/pay` 模拟收银台 · `/order/{orderNo}/pay/success` 支付成功页（含 AI 推荐）· `/consult/{sessionId}?role=user|doctor` 问诊聊天页 · `/doctor` 医生工作台。

### 4.3 数据库

- 4 个库由 `init.sql` 创建：`shopdb_product / shopdb_order / shopdb_payment / shopdb_consult`；应用账号 `shop` / 密码 `shop123456`（连接 127.0.0.1:3306）。
- 表由各服务 JPA `ddl-auto=update` **自动建表**（`init.sql` 只建库不建表）；如需看表结构可直接连库查看。
- 商品种子数据由 `product-service/config/DataInitializer.java` 启动时 upsert（31 商品 · 15 品类；**处方药示例为 id=32「阿莫西林胶囊」**）。

### 4.4 测试

```bash
export JAVA_HOME="D:\jdk25\jdk-25.0.2" && ./mvnw.cmd clean test
```
全量回归：6 个模块共 **364 个用例全绿**（实测见 `docs/trace/02-instrumentation-design.md` §9）。`clean` 若报"无法删除 jar"，先结束仍在运行的 5 个 java 进程（jar 文件锁）。

---

## 5. 如何看流程图与动态 trace（建议按这个顺序读）

| 文档 | 内容 | 谁适合 |
| --- | --- | --- |
| `docs/trace/04-flowcharts.md` | **静态+动态综合流程图**（架构图 / 4 条黄金链路时序图 / 2 张状态机图，附耗时与代码位置） | **所有人，第一个看** |
| `docs/trace/01-static-knowledge-graph.md` | GitNexus 静态知识图谱（2958 节点 / 8197 边）+ 4 条黄金链路**静态调用链**（含 `文件:行号`） | 想按代码逐行追踪时 |
| `docs/trace/03-dynamic-traces.md` | **运行时实测 trace**：4 条链路跨服务调用序列 + 每段耗时 ms + traceId（GP1~GP4） | 排障 / 性能分析 |
| `docs/trace/02-instrumentation-design.md` | 动态埋点设计（TraceFilter/TraceAspect/X-Trace-Id） | 要改日志/加埋点时 |

**自己复现一条 trace**（以 GP1 下单为例）：

```bash
# 起服务后：
curl -H "X-Trace-Id: gp1-order" http://localhost:8082/product/1
curl -H "X-Trace-Id: gp1-order" -X POST http://localhost:8082/order \
  --data-urlencode "productId=1" --data-urlencode "quantity=1" \
  --data-urlencode "receiverName=ZhangSan" --data-urlencode "receiverPhone=13800138000" \
  --data-urlencode "receiverAddress=Beijing Chaoyang Dist"
# 按 traceId 串联 5 份日志：
grep '\[TRACE\]' logs/order.log logs/product.log | grep 'gp1-order'
```

**排障注意**：
- 健康检查不要对无根映射服务发 `GET /`（返回 500 属正常），用真实 API 探测（03 §1）。
- git bash 发中文 JSON body 会被 GBK 破坏导致 400/500，用 python `ensure_ascii=True`（`\uXXXX`）发送（03 §9）。
- WS 帧（join/message/ack）的 `traceId=-` 是**设计预期**（Netty 线程不经 Servlet Filter），不是 bug（03 §7）。

---

## 6.（可选）用 GitNexus 看交互式知识图谱

```bash
# 安装（npm 11.x 必须全局装，勿用 npx）
GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 npm install -g gitnexus@latest

# 建索引（只写 .gitnexus/，不碰 AGENTS.md/CLAUDE.md/skills）
cd D:\work\0802\0802-4
GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 GITNEXUS_LBUG_EXTENSION_INSTALL=load-only gitnexus analyze --index-only --skip-agents-md

# 起 Web UI（默认 http://localhost:4747，测完即停，勿长期占用端口）
gitnexus serve --port 4747

# CLI 查询
gitnexus context OrderService.createOrder     # 符号 360° 视图（调用者/被调）
gitnexus query 下单                            # 按概念搜执行流
```

索引产物 `.gitnexus/` 已加入 `.gitignore`，本地可重建。完整说明见 `docs/trace/01-static-knowledge-graph.md` §2/§7。

---

## 7. 新手常见坑速查

| 现象 | 原因 / 解法 |
| --- | --- |
| 服务起不来，报连不上数据库 | 4 个库没建：`mysql -uroot -p < init.sql`（`ddl-auto=update` 只建表不建库） |
| 编译报 JDK 版本错 | 本机默认 JAVA_HOME 是 1.8，须 `set JAVA_HOME=D:\jdk25\jdk-25.0.2` |
| `clean test` 报无法删 jar | 还有 5 个 java 进程锁住 `target/*.jar`，先 `taskkill` 结束 |
| 支付页 / 推荐区空白 | 推荐服务挂了是**预期容错**（空列表隐藏），查 `logs/recommend.log` 是否 DeepSeek Key 未配 |
| WS 连不上 :8086 | 确认走的是 `ws://localhost:8086/ws`，且先经 :8082 建会话拿 token（browser 直连仅持 token） |
| 找不到某个新类 | 新类要放对模块；mall-common 新增 Bean 用 `@AutoConfiguration` 而非 `@Component`（兄弟包不扫描） |
