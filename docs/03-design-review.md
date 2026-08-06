# 《AI 智能导购微服务商城》设计方案评审报告（Design Review）

| 项目 | 内容 |
| --- | --- |
| 评审对象 | `docs/02-solution-design.md`（研发方案设计 v1.0） |
| 评审基线 | git HEAD `a3c1ef7`（当前代码现状） |
| 评审人 | 架构评审专家（Architecture Review Board） |
| 评审日期 | 2026-08-06 |
| 关联文档 | `docs/01-PRD.md`（62 条验收标准）、`README.md` |
| 评审方式 | 纯文档评审 + 代码抽查交叉验证（不修改任何代码） |
| 结论 | **有条件通过（必须修改 P0-01 / P1-01 / P1-02 / P1-03 后放行实现）** |

---

## 一、评审范围与方法

### 1.1 评审文档
- `docs/01-PRD.md`：产品需求，含 62 条 AC（AC-FR1-1 ~ AC-X5）与 38 条异常场景（EX-1 ~ EX-38）。
- `docs/02-solution-design.md`：方案设计，含 ADR-01~10、API 契约、DB 设计、状态机、容错、并发一致性、测试策略，末尾 R-01~R-09 待评审风险。

### 1.2 代码抽查清单（用于验证设计论断）
| 文件 | 验证要点 |
| --- | --- |
| `order-service/.../service/OrderService.java` | 先扣后落、补偿回补、状态机三态、订单号生成、金额 |
| `order-service/.../client/RecommendClient.java` | 容错边界"吞异常返回空"、**是否配超时** |
| `order-service/.../client/ProductClient.java` / `PaymentClient.java` | 错误体转换、**是否配超时** |
| `order-service/.../api/OrderApiController.java` | 最小回调面（getOrder / confirm-payment） |
| `order-service/.../web/ShopController.java` | 推荐双通道并行（CompletableFuture）、**join 有无超时** |
| `payment-service/.../service/PaymentService.java` | 防重复支付、FAILED 重试、卡号尾号、回调失败置 FAILED |
| `payment-service/.../model/Payment.java` | **order_no 唯一约束（与 FAILED 重试是否冲突）** |
| `product-service/.../service/ProductService.java` + `ProductRepository.java` | 原子扣减（`WHERE stock >= ?`）、回补 |
| `recommend-service/.../ai/AiRecommendationService.java` | 三层校验、围栏剥离、异常兜底 |
| `recommend-service/.../rule/RuleRecommendationService.java` | 规则确定性、品类解析、互补权重 |
| `recommend-service/.../service/RecommendationService.java` | AI→规则补足编排、去重 |
| `recommend-service/.../ai/DeepSeekClient.java` + `DeepSeekProperties.java` | 超时配置、thinking/json_object、异常 |
| 各 `*ExceptionHandler` | 错误体统一、Web/REST 隔离 |
| 全部 `application.properties` / `init.sql` / `README.md` / `DataInitializer.java` | 端口、schema、种子数据（31 商品 / 15 品类） |
| 全部 `pom.xml` | **是否含 spring-boot-starter-test（测试依赖）** |

### 1.3 评审维度
需求覆盖、架构正确性、数据与一致性、状态机、容错、安全性、可测性、规范化（P0 阻断 / P1 必须改 / P2 建议 / P3 可选）。

---

## 二、总体评价

方案整体质量较高：ADR 决策记录完整、API 契约与 DB 设计自洽、状态机矩阵清晰、推荐三层降级与三层幻觉校验实现到位、金额全程 `BigDecimal`、错误体跨服务统一，且能如实标注"演示级分布式事务边界"，属于可实施的设计。

但抽查发现**一处设计论断与代码/数据库物理约束直接矛盾**（P0-01：`payment.order_no` 唯一约束阻断"FAILED 可重试"，违反 AC-FR4-8），以及**一处设计声明与实现不符**（P1-01：设计声称服务间 RestClient "均配置超时"，实际仅 DeepSeekClient 配置，BFF 四客户端均为默认无限超时，且 `CompletableFuture.join()` 无超时，违反 NFR-10 ≤3s）。此外存在补偿失败静默吞掉、价格异常未校验两个 P1 缺口。

按"评审必须发现、实现必须修复"原则，结论为**有条件通过**：架构方向正确、边界清晰，但 P0-01 必须先解决（属 DB 契约/状态机与验收标准的硬冲突），P1 三项需在 Phase 4 实现时一并落实。

---

## 三、需求覆盖核对（62 条 AC）

| FR | AC 数 | 覆盖情况 | 风险点 |
| --- | --- | --- | --- |
| FR-1 | 3 | 全部可覆盖 | 正常 |
| FR-2 | 9 | 8 条可覆盖；AC-FR2-8（落单失败回补）实现存在但**补偿失败静默** | 关联 P1-02 |
| FR-3 | 6 | 原子扣减/回补/并发不超卖全部实现 | 正常 |
| FR-4 | 12 | **AC-FR4-8（FAILED 可重试）当前必失败**；AC-FR4-9/10 前端拦截；AC-FR4-11 回调失败置 FAILED 已实现 | **关联 P0-01** |
| FR-5 | 5 | 三态取消、库存回补均实现；PAID/CANCELLED 拦截正确 | 正常（极端并发见 P2-01） |
| FR-6 | 3 | 倒序列表、空状态实现 | 正常 |
| FR-7 | 7 | 三层校验 + 同类品类加强层实现；目录外/已购/重复/异品类过滤均正确 | 正常 |
| FR-8 | 5 | 互补推荐 + 并行双通道实现 | 正常（时延见 P1-01） |
| FR-9 | 7 | 无 Key / 超时 / 非法 JSON / 停机四级降级实现完整 | 正常 |
| X | 5 | 幂等、并发、状态机、容错均实现 | 正常（卡号脱敏 AC-X1 实现到位） |

**合计 62 条 AC 中：60 条具备实现基础；2 条存在阻断风险（AC-FR4-8 被 DB 唯一约束阻断；AC-FR4-11 依赖 P0-01 修复后仍成立）。**

补充核对（PRD 第 11 节异常场景）：
- EX-1~EX-5、EX-7~EX-18、EX-20~EX-34、EX-36、EX-37：均有对应实现或设计说明，可测。
- **EX-6（商品价格 ≤0）：PRD 标注 ★ 并明确"需研发补强"，方案未纳入校验** → P1-03。
- EX-38（崩溃窗口）：方案 §8.4 如实声明为演示级限制 → 接受。

**结论**：需求覆盖度约 97%，但存在 1 条 AC 硬性不满足（AC-FR4-8）与 1 条 PRD 明确要求的校验缺失（EX-6），需在实现期修复。另建议补充"62 条 AC → 测试用例"追溯矩阵以满足 M10（100% 执行）。

---

## 四、问题清单（编号 / 级别 / 位置 / 问题 / 建议）

| 编号 | 级别 | 位置（文件/字段/方法） | 问题 | 修改建议 |
| --- | --- | --- | --- | --- |
| P0-01 | P0 | `payment-service/.../model/Payment.java:26`（`order_no unique=true`）；`PaymentService.java:45-64` | **支付流水唯一约束与"FAILED 可重试"直接冲突**：`Payment.orderNo` 带 DB 唯一约束，重试时 `save` 新行触发 `DataIntegrityViolationException`→500，AC-FR4-8（FAILED 允许重试）必失败；设计 §4.5/§6.2 声称"FAILED 后可再次发起"与物理约束矛盾。并发首付时 check-then-insert 非原子，败者同样 500 而非 400"该订单已发起过支付" | 二选一：①（最小改动）重试时**复用/更新既有 FAILED 行**（`findByOrderNo` 命中 FAILED 则回填字段置 PROCESSING，不 INSERT），保留 UK 作为 DB 兜底；②（生产演进）按设计 R-05 引入 `payment_no`（流水唯一）+ `biz_no`（幂等键，UK）。并发败者需返回 400 业务提示而非 500 |
| P1-01 | P1 | `order-service/.../client/{RecommendClient,ProductClient,PaymentClient}.java` 构造器（仅 `baseUrl`，无 timeout）；`payment-service/.../client/OrderClient.java`；`order-service/.../web/ShopController.java:154-159`（`join()` 无超时） | **BFF 客户端无任何超时配置**，与设计 §0"服务间通信均配置超时"声明不符。仅 DeepSeekClient 配置了 3s/12s 超时。推荐服务慢响应（非宕机）时，`join()` 无限阻塞支付成功页，违反 NFR-10（P95 ≤3s）与 M7 | 统一注入带 `SimpleClientHttpRequestFactory`（connect≈3s / read≈5s）的 `RestClient.Builder` Bean 供四个客户端使用；`paySuccess` 中 `CompletableFuture` 加 `orTimeout`，超时吞异常返回空列表（降级不阻断页面） |
| P1-02 | P1 | `order-service/.../service/OrderService.java:100-102`（`catch (RuntimeException ignored) {}`） | **下单补偿失败被完全静默吞掉**：无任何日志/落库，与设计 §9.3"补偿失败记 WARN/ERROR 标记人工对账"不符。扣库存成功、落单失败、回补再失败 → 悬挂库存无法对账（R-07 落地缺失） | 补偿失败至少记 ERROR 日志（orderNo/productId/quantity）；按 R-07 增 `stock_compensation_log` 表或失败记录，供对账 |
| P1-03 | P1 | `order-service/.../service/OrderService.java:73`（`totalAmount` 计算处）；`docs/02 §5.1/ADR-06` | **商品价格 ≤0 无校验**：PRD EX-6（★）明确"现状无显式校验，需研发补强"，方案未纳入，脏数据价格会生成 0/负金额订单 | `createOrder` 中校验 `unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0`，否则抛"商品价格异常"；补对应单测 |
| P2-01 | P2 | `order-service/.../service/OrderService.java:132-169`；`order-service/.../model/Order.java`（无 `@Version`） | **取消与支付回调存在并发竞态**：`cancelOrder` 读到 CREATED→先回补库存→后置 CANCELLED，窗口内 `confirmPayment` 可置 PAID，出现"已支付订单被取消 + 库存已回补"。状态机无乐观锁/条件更新兜底（设计 §6.1 亦仅依赖方法内断言） | `Order` 增加 `@Version`，或以条件更新 `UPDATE shop_order SET status=... WHERE order_no=? AND status='CREATED'` 使流转原子化；补并发用例 |
| P2-02 | P2 | `order-service/.../client/ClientSupport.java` 与 `payment-service/.../client/ClientSupport.java` | **`ClientSupport` 在 order/payment 两处复制粘贴**，同质化代码违背 DRY | 下沉至 `mall-common`，各服务复用；顺带统一 ObjectMapper 实例 |
| P2-03 | P2 | `docs/02 §10`；各服务 `pom.xml`（**均无 `spring-boot-starter-test`**） | 测试策略较完整但缺"62 条 AC → 用例"追溯矩阵，且当前零测试、零测试依赖，Phase 5 需先补依赖；并发防重复支付用例的断言口径未定（P0-01 修复后应断言败者 400 非 500） | Phase 4/5 加入 `spring-boot-starter-test` + Mockito；输出 AC 追溯矩阵；明确并发用例断言 |
| P2-04 | P2 | `order-service/.../web/GlobalExceptionHandler.java:27-29`；各服务 `*ExceptionHandler.java` 通用兜底 `ex.getMessage()` | **错误页/错误体可能泄露内部信息**：`error.html` 注入 `detail = ex.toString()`；REST 通用 500 回显 `ex.getMessage()`，JSON 反序列化错误原文可能回带请求体（含完整卡号风险） | `detail` 仅 debug profile 展示；通用 500 使用固定文案，不回显 `ex.getMessage()` |
| P2-05 | P2 | `docs/02 §5.5` 与 `AiRecommendationService.java:55-67` | **AI 异常兜底依赖"catch 全部 Exception"**：日志仅 `ex.toString()` 无耗时/成功率/幻觉过滤命中数观测（R-09 未落地），无法评估 flash 效果 | Phase 4 增加 DeepSeek 调用打点（耗时/成功率/过滤计数） |
| P2-06 | P2 | `docs/02 §4`/ADR-05；各 `application.properties`（`ddl-auto=update`） | R-02（ddl-auto 生产不可控）维持"高"，方案未给实现期收敛计划 | 演示可接受；建议 Phase 4 至少将 `shop_order/payment` 建表脚本化（Flyway），上线前由 DBA 收敛 |
| P3-01 | P3 | 各 schema 表名 `product/shop_order/order_item/payment` | 表命名前缀不统一（R-04） | 记录在案，择机统一 `shop_*` 前缀 |
| P3-02 | P3 | `OrderService.java:138-140` 取消逐明细回补 | 多明细取消时回补非原子（当前 NG-3 单明细，无实际触发面） | 文档注明；若未来多商品下单需改 |
| P3-03 | P3 | `ShopController.java:147-161`（`/pay/success`） | 成功页未校验订单 `PAID` 状态，直连可展示"支付成功" | 增加 `status==PAID` 校验，否则重定向订单详情 |
| P3-04 | P3 | `AiRecommendationService.java:170-178` | AI 推荐 `reason` 无长度/内容校验（prompt 约束 30 字，未强校验）；规则互补权重未覆盖全部品类时以热销补足 | 可选：reason 超长截断；补全互补权重矩阵 |
| P3-05 | P3 | 跨服务调用链 | 无 traceId（R-08），故障定位靠日志时间对齐 | 建议 MDC + orderNo 透传 |

---

## 五、分维度详细评审

### 5.1 需求覆盖
- 62 条 AC 覆盖度约 97%，见第三节。关键缺口：**AC-FR4-8（FAILED 可重试）被 DB 唯一约束阻断（P0-01）**、**EX-6 价格 ≤0 校验缺失（P1-03）**。
- 验收标准全部为 Given-When-Then 结构，可测；但无 AC→用例追溯矩阵，M10"100% 执行"缺乏可核对的载体（P2-03）。
- 降级类 AC（FR-9 全组）、幻觉过滤类 AC（FR-7/8 异常组）、幂等类 AC（AC-X2）、并发类 AC（AC-X3）设计均有对应实现路径，属实。

### 5.2 架构正确性
- 依赖方向正确：`order → product/payment/recommend`、`payment → order`（回调）、`recommend → DeepSeek`，编译期仅依赖 `mall-common`，无循环依赖（§1.3 依赖矩阵与代码一致）。
- **`order ⇄ payment` 运行期双向调用**（R-01）：判定为可接受——回调是单向事件通知，order 只暴露最小回调面（`getOrder` / `confirm-payment`，见 `OrderApiController.java`，面确实最小），两服务无 DB 交叉、无编译期依赖。维持"中"风险，建议后续引入 MQ 时改事件驱动。
- BFF 职责合理：order-service 聚合全部页面、承担推荐容错边界（`RecommendClient` 吞异常），符合 BFF 定位。唯一瑕疵是"BFF 同时承担 order API 供支付服务回调"，属演示可接受。
- 每服务独立 schema + 无数据库推荐服务（天然可水平扩展）符合微服务数据自治原则。

### 5.3 数据与一致性
- **库存原子扣减**：`ProductRepository.deductStock` = `UPDATE product SET stock=stock-? WHERE id=? AND stock>=?`，单条原子语句 + 影响行数判定，**并发安全成立**；回补 `stock=stock+?` 无副作用。✓
- **支付回调幂等**：`confirmPayment` 三态分支（PAID 直接返回 / CANCELLED 抛错 / CREATED→PAID），状态字段天然幂等锁，重复回调无二次副作用。✓
- **"一单多流水"防重**：⚠️ 当前**代码防重 + DB 唯一约束并存，但二者语义冲突**：代码允许 FAILED 重试，DB 唯一约束却阻断重试（P0-01）；并发首付败者返回 500 而非 400。**无 DB 兜底的正确模型**需按 P0-01 建议重构。
- **补偿回补失败**：⚠️ 回补失败被静默吞掉，无日志无对账（P1-02）。"已扣未补"悬挂库存窗口无法收敛，违反 PRD 对账兜底要求。
- **金额精度**：全程 `BigDecimal` + `DECIMAL(12,2)`，`unitPrice.multiply(BigDecimal.valueOf(quantity))` 精确；无除法运算，无 `ArithmeticException` 风险。✓（唯一缺口是价格 ≤0 校验，P1-03）
- 崩溃窗口（扣库存未落单 / 流水 PROCESSING 未回调）：方案 §8.4 如实声明为演示级限制，接受。

### 5.4 状态机
- 订单状态流转矩阵（§6.1）与代码一致：`CREATED→PAID/CANCELLED`，`PAID/CANCELLED` 为终态，非法流转（已付再取消/再支付、已取消再支付/再取消）全部被 service 方法前置断言拦截。✓
- 支付流水状态矩阵（§6.2）：`PROCESSING→SUCCESS/FAILED`、`FAILED→PROCESSING`（重试）——**矩阵本身正确，但"FAILED→PROCESSING（重试）"在物理 DB 上不可达**（P0-01）。这是状态机设计与数据约束不一致的典型。
- 取消已支付订单边界：`cancelOrder` 检查 `status != CREATED` → 抛"订单已支付，无法取消"，不回补库存，符合 EX-12。✓
- 缺口：**状态流转无 DB 级原子性兜底**（无 `@Version`、无条件更新），`cancelOrder` 与 `confirmPayment` 并发存在竞态窗口（P2-01）。

### 5.5 容错
- **推荐三层降级成立**：① `RecommendClient` catch 全部异常返回空（页面 `th:if` 隐藏）→ ② `AiRecommendationService` 失败/无 Key 返回空 → ③ 规则补足 topN。代码与设计一致，推荐失败绝不上抛到下单/支付主链路。✓
- **AI 幻觉校验完整**：`parseAndValidate` 实现"目录内 → 非已购 → 去重 →（同类）品类一致"过滤链；`stripCodeFence` 剥离 Markdown 围栏；`RecommendationService` 用 `picked` 集合跨 AI/规则去重。✓
- **DeepSeek 异常路径全兜底**：无 Key（`isEnabled()=false`）→ 空；网络/连接超时、非 2xx、结构异常、空 content → 抛异常被 `recommendInternal` catch → 空；JSON 解析失败/校验丢弃 → 空；结果不足 topN → 规则补足。全部路径不产生 5xx。✓
- ⚠️ **例外**：BFF 侧 `RecommendClient` 无超时 + `join()` 无超时（P1-01），"服务慢响应（非宕机）"场景会阻塞支付成功页超过 NFR-10 阈值——降级链路在"宕机/异常"场景成立，在"慢响应"场景不成立。

### 5.6 安全性
- **卡号脱敏**：全链路仅存尾号 4 位（`computeCardTail` 剥离非数字、不足 4 位返回 null）；`Payment`/`Order` 均无完整卡号字段；响应 DTO 无卡号；日志未见打印完整卡号。✓（AC-X1 可满足）
- **注入**：全部 JPA 参数化查询（`@Query` + `@Param`、Spring Data 方法名），无 SQL 拼接；Thymeleaf 模板自动转义，无 XSS 注入面。✓
- **敏感信息泄露**：⚠️ 通用 500 回显 `ex.getMessage()`、`error.html` 带 `detail=ex.toString()`，存在回带请求体/异常详情风险（P2-04）。完整卡号在请求传输阶段走明文 HTTP（演示级约束，PRD NG-9 接受），建议至少保证错误响应不回显请求体。

### 5.7 可测性
- 设计 §10 覆盖单测（service/client/校验）、接口测试（MockMvc）、集成测试（真实四服务 / MockBean），并明确边界、异常、并发重点用例——**测试策略本身完整且侧重 anti-happy-path**。
- 但现状：**全仓库零测试文件、各 pom 无 `spring-boot-starter-test`**（已 grep 确认）。方案未将"补测试依赖"列为 Phase 4 任务，未提供 62 AC 追溯矩阵，并发/幂等用例断言口径未定（P0-01 修复后应断言败者 400）。→ P2-03。
- 建议把 AC-X3（并发不超卖）、AC-X2（幂等回调）、EX-15（回调失败不卡 PROCESSING）、EX-23~25（无 Key/超时/非法 JSON）作为必测项纳入 Phase 5 首轮。

### 5.8 规范化
- 错误体统一 `{"message":"..."}`（四服务 `@RestControllerAdvice` + order 内 Web/REST 按包名隔离）实现一致。✓
- 枚举 JSON 保持 `name`、中文走 `statusLabel`（`OrderStatus`/`OrderDTO`）一致；`PaymentStatus` 在 payment 侧用 `name()` 转 String，跨服务口径一致。✓
- 反模式排查：`ClientSupport` 复制粘贴（P2-02）；状态判断散落 service 内联分支（R-06，P2 建议引入状态机守卫或条件更新）；补偿失败空 catch（P1-02）；无魔法值泛滥，常量均有命名；事务边界（`@Transactional` 方法内跨服务调用）清晰并如实声明分布式边界。✓

---

## 六、R-01~R-09 待评审风险逐条意见

| 编号 | 方案原评级 | 评审裁定 | 意见 |
| --- | --- | --- | --- |
| R-01 | 中 | 接受（维持中） | `order⇄payment` 双向为事件式回调，order 仅暴露最小回调面，无编译期循环；后续引 MQ 可改异步。不阻断。 |
| R-02 | 高 | P2 | 演示可接受；建议 Phase 4 将 `shop_order/payment` 建表脚本化（Flyway），上线前 DBA 收敛。 |
| R-03 | 中 | P2 | 脱敏已最小化（尾号 4 位）；补日志审计 + 错误体不回显请求体（合并 P2-04）。 |
| R-04 | 低 | P3 | 表命名统一，择机处理。 |
| **R-05** | **中** | **P0（升级）** | **方案低估了风险**：当前 `UK(order_no)` 不仅与"FAILED 可重试"存在张力，而是**物理阻断重试**（插入第二行即 500），AC-FR4-8 必失败。必须按 P0-01 在 Phase 4 修复（复用 FAILED 行 或 `payment_no+biz_no`）。 |
| R-06 | 低 | P2 | 状态机硬编码判断 + 无乐观锁：与 P2-01 合并，建议条件更新 / `@Version` / 统一状态机守卫。 |
| **R-07** | **中** | **P1（升级）** | 补偿失败当前为**空 catch，零日志零落库**，与设计 §9.3 声明不符；悬挂库存无法对账。必须落地日志/对账表（合并 P1-02）。 |
| R-08 | 低 | P3 | MDC + orderNo 透传，建议 Phase 4 顺手实现。 |
| R-09 | 低 | P3 | DeepSeek 打点建议纳入 Phase 4（合并 P2-05）。 |

---

## 七、评审结论

### 结论：有条件通过（须先修复以下 4 项后放行实现）

**必须修改项（阻塞实现）：**
- **P0-01（阻断）**：解决 `payment.order_no` 唯一约束与"FAILED 可重试"的硬冲突，保证 AC-FR4-8 通过；并发防重败者返回 400 业务提示而非 500。
- **P1-01**：为 BFF 四个 RestClient 统一配置连接/读取超时，并为支付成功页的 `CompletableFuture` 增加 `orTimeout`，满足 NFR-10（≤3s）。
- **P1-02**：下单补偿回补失败必须记录日志（或落对账表），消除静默悬挂库存。
- **P1-03**：下单时校验商品价格 >0（EX-6），拒绝脏数据订单。

**实现期应收敛（P2/P3）：** 取消/支付并发竞态兜底（P2-01）、`ClientSupport` 下沉 mall-common（P2-02）、测试依赖与 AC 追溯矩阵（P2-03）、错误信息脱敏（P2-04）、DeepSeek 打点（P2-05）、DDL 收敛（P2-06）。

**放行条件**：P0-01 + P1-01~03 完成并纳入测试后，进入 Phase 4 实现；其余 P2/P3 随实现分批收敛，不阻断主流程。

---

## 八、总结（≤200 字）

结论：**有条件通过**。共 **P0×1、P1×3**。P0：`payment.order_no` 唯一约束阻断 FAILED 重试，AC-FR4-8 必失败，需行复用或 `payment_no+biz_no` 重构；P1：BFF 客户端无超时违反 NFR-10、下单补偿失败被静默吞掉、价格≤0 未校验。修复上述 4 项后放行实现；其余 P2/P3 实现期收敛。架构方向、状态机、三层降级与幻觉校验、脱敏与 BigDecimal 均正确，风险可控。
