# Spec：下单（FR-2）

> 本文件是用 `harness/commands` 管线从 `docs/01-PRD.md` 的 **FR-2** 转化出的**示例 Spec**，用于验证整条管线（Parse→Validate→Transform→Emit→Verify）可用，并演示新版模板的 **§0 术语对齐 / §2.5 业务域与规则 / §3.5 改造定位 / §13 研发方案**。转化依据：PRD v1.0 + `docs/02-solution-design.md`（方案）+ 当前代码现状（order-service）+ 知识层（`harness/references/*`）+ 模块约束（`harness/agents/`）。每个 Spec 项带 `（ref: ...）` 可追溯引用。

| 项 | 内容 |
| --- | --- |
| Feature | FR-2 下单（确认订单 + 提交订单） |
| PRD 版本 | docs/01-PRD.md v1.0 |
| Spec 状态 | DRAFT |
| 负责人 | 研发（OrderService / ShopController） |
| 关联 Spec | FR-1 商品浏览（前置）、FR-3 库存扣减/回补、FR-7 同类推荐（后置）、FR-4 支付（后继状态） |
| 覆盖 PRD 元素 | FR-2, AC-FR2-1~9, EX-1/3/4/5/6/7/18/19/20/36, NFR-5/6/9/11 |
| 触及业务域 | M-3 order-service（BFF :8082）、M-8 medicine-service（药品 :8081，当前实现；PRD 原文为 M-2 product-service，差异见 §3.5） |

---

## 0. 术语对齐（概念层对齐）

> 本表将本 Spec 用到的关键概念映射到 `harness/references/glossary.md`；全部可在表内解析，无新增词。

| Spec 用词 | 统一术语（glossary） | PRD 术语 | 代码符号 | 模块 |
| --- | --- | --- | --- | --- |
| 下单 | 下单 | FR-2 下单 | `ShopController.createOrder` → `OrderService.createOrder` | M-3 |
| 原子扣减 | 原子扣减 | 原子扣减 | `MedicineService.deductStock`（PRD 原文 `ProductService.deductStock`） | M-8/M-2 |
| 库存回补 | 库存回补 | 回补库存 | `OrderService.restoreStockQuietly` | M-3 |
| 幂等键 | 幂等键 | 幂等 | `shop_order.order_no`(UK) | M-3 |
| 快照 | 快照 | 明细快照 | `OrderItem` | M-3 |
| 订单号 | 订单号 | 订单号 | `OrderService` 生成 `SO+…` | M-3 |
| Rx 处方购药 | Rx 药品 / 处方消费 | 处方购药 | `OrderService.createOrder`(Rx) / `ConsultClient.consumePrescription` | M-3/M-6 |
| 统一错误体 | 统一错误体 | 错误体 | `ApiError` / `ClientSupport.toBusinessException` | mall-common/M-3 |
| 埋点 | 埋点 | 动态埋点 | `TraceAspect` / `TraceContext` | mall-common |

## 1. 需求来源

- **PRD 需求描述**：用户确认商品与数量、填写收货人/手机号/地址后提交订单；系统生成订单号、写入订单与明细快照，并扣减库存。（`ref: FR-2 需求描述`）
- **非目标红线**（`ref: NG-2/3/4`）：
  - NG-2 本 Spec **不做**账号体系/登录鉴权，单匿名买家；
  - NG-3 **不做**购物车/多商品合并，仅单品选数量；
  - NG-4 **不做**真实分布式事务（Saga/Outbox/MQ），采用「失败补偿 + 流水状态」演示级兜底。
- **权限边界**（`ref: PRD §7`）：匿名买家为唯一角色；页面校验仅为体验，**业务正确性由服务端判定**——库存边界在商品服务原子扣减（`ref: NFR-6`）。

## 2. 功能规格

| 编号 | 类型 | 描述 | 追溯 |
| --- | --- | --- | --- |
| R-1 | 输入 | `OrderForm`：`productId`(Long, 必填)、`quantity`(Integer, 必填, ∈[1,999])、`receiverName/receiverPhone/receiverAddress`(非空)、`prescriptionNo`(≤32 位, 可选) | `ref: FR-2 主流程步骤 2` |
| R-2 | 输出 | 创建 `Order`(status=`CREATED`) + `OrderItem` 明细快照；成功后 `302 → /order/{orderNo}` | `ref: FR-2 主流程步骤 4 / 后置条件` |
| R-3 | 业务规则 | 执行顺序固定为：**先原子扣库存，再本地落单**；扣库存失败即抛，不落单 | `ref: FR-2 主流程步骤 3c` |
| R-4 | 业务规则 | `totalAmount = unitPrice × quantity`，全程 `BigDecimal`，精确到分 | `ref: FR-2 主流程步骤 3d / NFR-5` |
| R-5 | 业务规则 | 订单号 `SO + yyyyMMddHHmmss + 4位随机`，随机段防同秒冲突 | `ref: FR-2 主流程步骤 3e / NFR-9` |
| R-6 | 业务规则 | 明细快照**固化**商品名/单价/数量/小计，商品后续改价不影响历史订单 | `ref: FR-2 需求描述 / SD-02 §4.4` |
| R-7 | 前置条件 | 商品存在、`quantity ∈ [1,999]`、库存充足、收货三项非空、`medicine-service` 可用 | `ref: FR-2 前置条件` |
| R-8 | 后置条件 | 订单 `CREATED`、库存已扣减、订单详情页展示订单与明细快照、下方展示同类推荐（FR-7） | `ref: FR-2 后置条件` |
| R-9 | 权限边界 | 服务端最终校验：确认页库存提示仅是体验，并发边界在商品服务原子扣减 | `ref: PRD §7 / NFR-6` |

## 2.5 业务域与适用规则

**触及业务域**（`harness/references/module-contracts.md`）：
- M-3 order-service（BFF :8082）：下单编排、表单校验、Saga 补偿、幂等键落库；
- M-8 medicine-service（药品 :8081）/ PRD 原文 M-2 product-service：商品/库存原子扣减。

**适用技术架构规则**（`harness/references/tech-rules.md`）：
- `T-3` 服务间错误统一转换（`ClientSupport.toBusinessException`）——理由：下单跨服务调 medicine，下游错误必须转业务异常，不把 500 透传；
- `T-4` 数据边界（独立 DB、快照语义）——理由：订单/药品分属不同 schema，明细必须快照固化，禁跨库 JOIN；
- `T-5` 一致性（先扣后补 + 幂等）——理由：扣库存→落单跨服务，无分布式事务，靠补偿与 `order_no` UK 兜底。

**适用代码架构规则**（`harness/references/code-rules.md`）：
- `C-1` 分层（web 薄 / service 编排）——理由：下单流程在 `OrderService`，`ShopController` 只做表单绑定与跳转；
- `C-3` 校验 fail-closed（服务端最终校验）——理由：并发边界在原子扣减，页面校验仅体验；
- `C-4` 幂等与补偿模式——理由：`order_no` UK 幂等键 + `restoreStockQuietly` best-effort 补偿。

> 纪律：本 Feature 的中间件（MySQL 独立 schema / RestClient / JPA）、通信（统一错误体）、一致性（Saga 补偿）全部来自 T-x/C-x，无自造模式。

## 3. 流程规格

**主流程步骤**（`ref: FR-2 主流程步骤 1~4` + 代码 `ShopController.createOrder` / `OrderService.createOrder`）：

1. **P-1 确认页** `GET /order/confirm?productId=&quantity=`：`quantity<1` 重置为 1；`quantity>stock` 抛「购买数量超过库存」。（`ref: FR-2 步骤 1`）
2. **P-2 表单校验** `POST /order`：`@Valid` 校验 `OrderForm`（quantity∈[1,999]、收货三项非空），任一失败**回填确认页**并展示 `th:errors`，不生成订单。（`ref: FR-2 步骤 2`）
3. **P-3 服务端入口校验**：`quantity<=0` 抛「购买数量必须大于 0」；`product.getPrice()==null || <=0` 抛「商品价格异常，无法下单」。（`ref: FR-2 步骤 3a / EX-6`，价格校验为代码现状，见 OPEN-1）
4. **P-4 预校验库存**：`product.getStock() < quantity` 抛「库存不足，当前库存仅 X 件」。（`ref: FR-2 步骤 3b / EX-1`）
5. **P-5 原子扣减**：调 `POST /api/medicines/{id}/stock/deduct?quantity=`（PRD 原文 `/api/products/...`，见 §3.5 现状差异），失败（影响行数 0）抛「库存不足」（并发正确性唯一边界）。（`ref: FR-2 步骤 3c / NFR-6`）
6. **P-6 本地落单**：生成订单号 → 组装 `Order`(CREATED)+`OrderItem` 快照 → `orderRepository.save`。（`ref: FR-2 步骤 3e`）
7. **P-7 失败补偿**：落库异常 → `restoreStockQuietly` 尽力回补库存（best-effort，失败仅记 ERROR 日志供人工对账）→ 重新抛出。（`ref: FR-2 步骤 3f / EX-19 / SD-02 §7.3`）
8. **P-8 跳转** `302 → /order/{orderNo}`，订单详情页渲染订单 + 明细 + 同类推荐（FR-7）。（`ref: FR-2 步骤 4 / 后置条件`）

**时序**（`ref: SD-02 §5.1`）：
```
浏览器          order-service(BFF)          medicine-service
 │ POST /order       │                          │
 │──────────────────▶│ ① 校验 quantity∈[1,999]    │
 │                   │ ② GET /api/medicines/{id} │
 │                   │─────────────────────────▶│ 商品+库存
 │                   │◀─────────────────────────│
 │                   │ ③ 预校验 stock<qty→400    │
 │                   │ ④ POST /stock/deduct      │
 │                   │─────────────────────────▶│ 原子 UPDATE ... WHERE stock>=?
 │                   │◀─── 200 / 400 库存不足 ───│（并发正确性唯一边界）
 │                   │ ⑤ save(Order+OrderItem)   │
 │                   │   └─ 失败→⑥ restoreStock  │
 │                   │ ⑦ redirect               │
 │◀── 302 /order/SO ─┤
```

## 3.5 改造定位（Change Localization）

> 演示：本 Feature 已实现，故待改造清单为**假设新增需求**（给下单加「优惠码校验」）的定位模板，落点基于当前代码实测。真实转化时按此格式给出每个需求点的落点。

**证据来源**：
- 静态调用链：`docs/trace/01-static-knowledge-graph.md` §6.1（下单，file:line）
- 动态 trace：`docs/trace/03-dynamic-traces.md` §2（GP1，traceId=gp1-order，跨服务实测）
- 代码阅读：`grep` 实测当前行号（2026-08-24）；注意 trace 文档行号可能过期，以实测为准

**现状调用链（下单）**：
```
ShopController.createOrder(POST /order)                  web/ShopController.java:84
  └─ OrderService.createOrder(productId, quantity, …, [Rx])   service/OrderService.java:95（Rx 6 参，Saga 补偿核心）
       ├─ MedicineClient.getMedicine(id)                  client/MedicineClient.java:39 → GET /api/medicines/{id}
       ├─ ① 原子扣减 MedicineClient.deductStock           client/MedicineClient.java:53 → POST /api/medicines/{id}/stock/deduct → MedicineService.deductStock:33（medicine-service）
       ├─ ② [Rx] ConsultClient.consumePrescription        → consult PrescriptionService.consume:143（CAS USED）
       ├─ ③ 本地落单 orderRepository.save(order)          service/OrderService.java
       └─ 失败补偿：restoreStockQuietly / restorePrescription（best-effort 不掩盖主因）
```

> **现状差异标注**：PRD/方案原文的下游是 product-service（`ProductClient.deductStock` → `/api/products/...`）；当前代码 order 侧已改用 `MedicineClient` → **medicine-service**（见 `harness/references/module-contracts.md` M-3/M-8 与 `harness/agents/order-service.md`）。这是「Spec 以当前代码为准 + 标注差异」的示例。

**待改造清单（演示：新增「优惠码校验」）**：

| 动作 | 类.方法 | 位置（file:line / 插入点） | 变更前后对比 | 必须保持的不变量 |
| --- | --- | --- | --- | --- |
| 新增 | `OrderService.validateCoupon` | 在 `OrderService.createOrder`(Rx :95) 的价格校验段之后、扣减之前插入 | 前：无优惠码；后：先校验优惠码 + 算折扣，再扣库存落单 | 先扣后落顺序；金额 `BigDecimal`；`order_no` UK |
| 修改 | `OrderService.createOrder` | 传参增加 `couponCode` | 前：5/6 参；后：+1 可选参 | 既有非 Rx 调用点不破坏 |
| 新增 | `form/OrderForm.couponCode` | 表单校验层（`@Size(max=16)` 可选） | 前：无；后：≤16 位可选 | 收货三项必填逻辑不变 |
| 修改 | `web/ShopController.createOrder` | 表单绑定（:84） | 前：无；后：绑定 `couponCode` | 302 跳转语义不变 |
| 修改 | `templates/order-confirm.html` | 表单加优惠码输入框 | 前端体验 | 服务端最终校验（C-3） |

**新增方法签名草案**：`public void validateCoupon(String couponCode, BigDecimal amount)`（校验失败抛 `BusinessException`，fail-closed）。

## 4. 接口契约

| 方向 | 方法 | 路径 | 请求 | 响应 | 错误码 | 幂等 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 对外(Web) | GET | `/order/confirm` | query `productId,quantity(默认1),prescriptionNo(可选)` | order-confirm 页 | 400 商品不存在/超过库存 | ⭐ 读 | `ref: FR-2 / SD-02 §3.2B` |
| 对外(Web) | POST | `/order` | body `OrderForm`（Bean Validation） | 302 `/order/{orderNo}`；校验失败回填 | 表单错误回填 | ❌ 非幂等（成功即扣库存） | `ref: FR-2 / SD-02 §3.2B` |
| 内部(REST) | GET | `/api/medicines/{id}`（PRD 原文 `/api/products/{id}`） | — | `MedicineDTO` | 400 商品不存在；500 | ⭐ 读 | `ref: FR-2 / SD-02 §3.1` |
| 内部(REST) | POST | `/api/medicines/{id}/stock/deduct` | query `quantity` | `MedicineDTO` | 400 数量非法/库存不足；500 | ❌ 不可重试（重试二次扣减） | `ref: FR-2 / SD-02 §3.1` |
| 内部(REST) | POST | `/api/medicines/{id}/stock/restore` | query `quantity` | — | 400 数量非法/商品不存在；500 | ❌ 由业务显式驱动 | `ref: FR-2 / SD-02 §3.1` |

> 错误体统一 `{"message":"..."}`（`ref: SD-02 §9.1` / T-3）；下游不可用 → `MedicineClient` 映射为「商品服务不可用，请稍后重试」（`ref: EX-20`）。

## 5. 数据模型

| 表 | 列 | 类型 | 约束/索引 | 说明 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| `shop_order` | `order_no` | VARCHAR(32) | **UK** | 业务单号，幂等键（`ref: NFR-5/9`） | `ref: SD-02 §4.3` |
| `shop_order` | `receiver_name/phone/address` | VARCHAR | — | 收货快照 | `ref: SD-02 §4.3` |
| `shop_order` | `total_amount` | DECIMAL(12,2) | NOT NULL | BigDecimal 精确到分 | `ref: NFR-5 / SD-02 §4.7` |
| `shop_order` | `status` | VARCHAR(20) | NOT NULL | `OrderStatus` 枚举 name（本 Feature 落 `CREATED`） | `ref: SD-02 §4.3 / §6.1` |
| `shop_order` | `created_at` | DATETIME | NOT NULL | 下单时间 | `ref: SD-02 §4.3` |
| `order_item` | `product_id` | BIGINT | NOT NULL | 不建物理 FK（商品在另一库） | `ref: SD-02 §4.4` |
| `order_item` | `product_name` | VARCHAR(100) | NOT NULL | **名称快照** | `ref: FR-2 需求描述 / SD-02 §4.4` |
| `order_item` | `unit_price/subtotal` | DECIMAL(12,2) | NOT NULL | 单价/小计快照 | `ref: SD-02 §4.4` |
| `order_item` | `quantity` | INT | NOT NULL | 数量 | `ref: SD-02 §4.4` |

> 本 Feature 只写 `shop_order`/`order_item`；跨库数据（商品）一律 REST 只读，不跨库 JOIN（`ref: SD-02 §1.2` / T-4）。

## 6. 状态机

本 Feature 落单时只产生 **`CREATED`** 初态；`PAID`/`CANCELLED` 属 FR-4/FR-5，不在本 Spec 实现，仅声明出口（`ref: PRD §14.1 / SD-02 §6.1`）：

| 当前状态 | 动作 | 目标状态 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| （无） | 落单成功 | CREATED | ✓ | 本 Feature 唯一产出 |
| CREATED | 支付确认 | PAID | ✓（FR-4） | 幂等：重试不改结果 |
| CREATED | 取消 | CANCELLED | ✓（FR-5） | 逐明细回补库存 |

非法流转（`ref: NFR-11`）：本 Feature 落单后无任何在本 Spec 内的二次流转；状态变更全部由服务端前置校验拦截。

## 7. 异常规格

| 编号 | 异常场景（EX） | 触发条件 | 期望行为 | HTTP | message | 降级/兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E-1 | 库存不足 | 下单时 `stock < quantity`（预校验或原子扣减影响行数 0） | 下单失败，不扣库存、不生成订单 | 400 | 库存不足，当前库存仅 X 件 | 无副作用 | `ref: EX-1` |
| E-3 | 数量 0/负数 | `quantity<=0`（表单或服务端） | 校验失败，不生成订单、不扣库存 | 表单回填/400 | 购买数量必须大于 0 | 无副作用 | `ref: EX-3` |
| E-4 | 数量超上限 | `quantity>999` | 表单校验失败 | 表单回填 | 单次最多购买 999 件 | 无副作用 | `ref: EX-4` |
| E-5 | 商品不存在 | `productId` 不存在（`GET /api/medicines/{id}` 404） | 提示商品不存在，不扣库存、不落订单 | 400 | 商品不存在 | 无副作用 | `ref: EX-5 / EX-7` |
| E-6 | 商品价格异常 | `price==null || <=0`（脏数据） | 拒绝下单，避免零/负金额订单 | 400 | 商品价格异常，无法下单 | 无副作用 | `ref: EX-6`（代码已补强，见 OPEN-1） |
| E-18 | 重复下单 | 同一商品/用户多次提交 | 每次生成独立订单、各自正确扣库存（合法多次购买） | 200 | 正常流程 | 库存逐次扣减 | `ref: EX-18` |
| E-19 | 本地落库失败 | `orderRepository.save` 抛异常 | 触发库存回补；不产生无订单的扣减遗留 | 500 | 内部错误 | 回补失败→ERROR 日志人工对账 | `ref: EX-19 / SD-02 §7.3` |
| E-20 | 商品服务不可用 | `GET /api/medicines/{id}` 或 deduct 连接失败 | 下单失败并提示，不落订单 | 400 | 商品服务不可用，请稍后重试 | 无副作用 | `ref: EX-20 / MedicineClient` |
| E-36 | 订单号碰撞 | 同秒 ≥10000 单（随机段撞车） | 唯一索引兜底：保存失败→回补库存+提示重试，不产生脏订单 | 500 | 内部错误（可重试） | 回补 + 对账 | `ref: EX-36 / NFR-9` |

## 8. 边界与校验规则

| 编号 | 约束 | 值/语义 | fail-closed 说明 | 追溯 |
| --- | --- | --- | --- | --- |
| B-1 | 数量边界 | `quantity ∈ [1,999]`，`@Min(1)`/`@Max(999)` | 默认最严口径，越界即拒绝 | `ref: FR-2 / NFR-9` |
| B-2 | 并发边界 | **原子扣减为唯一边界**（`UPDATE ... WHERE stock>=?`），页面校验仅是提示 | 并发正确性不依赖前端/预校验快照 | `ref: NFR-6 / SD-02 §8.1` |
| B-3 | 确认页数量 | `quantity<1` 重置为 1；`quantity>stock` 抛「购买数量超过库存」 | 明确重置/拒绝分支 | `ref: FR-2 步骤 1`（文案差异见 OPEN-2） |
| B-4 | 金额精度 | 全程 `BigDecimal`，`DECIMAL(12,2)`，禁止 `double/float`；乘法显式 scale | 货币运算严禁浮点 | `ref: NFR-5 / SD-02 §4.7` |
| B-5 | 订单号唯一 | `SO+时间戳+4位随机`，`UK(order_no)` 兜底 | 极小概率冲突靠 DB 唯一键 + 补偿 | `ref: NFR-5/9 / SD-02 §8.2` |

## 9. 事务与补偿边界

| 编号 | 跨服务动作 | 主路径 | 失败补偿 | 兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| T-1 | 扣库存 → 落订单 | 先扣后落 | 落库失败→`restoreStock` 回补 | 回补失败→ERROR 日志（含 orderNo/productId/quantity）人工对账 | `ref: SD-02 §8.4 / EX-19 / OrderService` |
| T-2 | 崩溃窗口 | 扣减成功与落单成功之间进程崩溃 | 补偿不执行 | 悬挂库存，**演示级已知限制**（NG-4），人工对账兜底 | `ref: SD-02 §8.4 / EX-38` |

> 补偿语义：`restoreStockQuietly` 为 best-effort，**不抛出掩盖主因**，仅记 ERROR 日志留下可对账痕迹（`ref: OrderService.restoreStockQuietly` / T-5）。

## 10. 可测试验收

| 编号 | 验收（Given-When-Then） | 来源 AC | 状态 |
| --- | --- | --- | --- |
| T-FR2-1 | Given 商品库存充足；When 填妥收货信息提交订单；Then 生成订单（状态 CREATED）、扣减库存、跳转订单详情页，明细快照正确 | AC-FR2-1 | ☐ |
| T-FR2-2 | Given 商品价格 ¥100、数量 2；When 确认订单；Then 总价展示 ¥200.00（金额精确到分） | AC-FR2-2 | ☐ |
| T-FR2-3 | Given 提交数量 0 或负数；When 提交订单；Then 校验失败并提示「购买数量必须大于 0」，不生成订单、不扣库存 | AC-FR2-3（EX-3） | ☐ |
| T-FR2-4 | Given 提交数量 1000；When 提交订单；Then 校验失败并提示「单次最多购买 999 件」 | AC-FR2-4（EX-4） | ☐ |
| T-FR2-5 | Given 商品库存 2、提交数量 3；When 提交订单；Then 提示「库存不足，当前库存仅 2 件」，不生成订单、不扣库存 | AC-FR2-5（EX-1） | ☐ |
| T-FR2-6 | Given 提交的 productId 不存在；When 提交订单；Then 提示「商品不存在」，不生成订单、不扣库存 | AC-FR2-6（EX-5） | ☐ |
| T-FR2-7 | Given 收货人/手机号/地址任一为空；When 提交订单；Then 表单回填并提示对应错误，不生成订单 | AC-FR2-7 | ☐ |
| T-FR2-8 | Given 本地落库失败（Mock `save` 抛异常）；When 提交订单；Then 触发库存回补，不产生无库存的孤儿订单 | AC-FR2-8（EX-19） | ☐ |
| T-FR2-9 | Given medicine-service 停机；When 提交订单；Then 提示「商品服务不可用」，订单不生成 | AC-FR2-9（EX-20） | ☐ |
| T-FR2-10 | Given 商品价格异常（null/≤0，脏数据）；When 提交订单；Then 拒绝并提示「商品价格异常」，不生成零/负金额订单 | 新增（EX-6 补强验收） | ☐ |
| T-FR2-11 | Given 下单成功；When 检查订单号；Then 格式为 `SO+yyyyMMddHHmmss+4位随机`（长度 ≤ 32） | 新增（NFR-5/9 可测断言） | ☐ |
| T-FR2-12 | Given 并发 N 个请求抢库存 K（N>K）；When 同时下单；Then 成功订单数 ≤ K，不超卖 | 新增（NFR-6 可测断言，对齐 AC-X3） | ☐ |

## 11. 测试映射

| 测试层次 | 用例 | 覆盖 Spec 项 | 受影响既有用例 | 追溯 |
| --- | --- | --- | --- | --- |
| 单元（service） | `OrderServiceTest.quantityNonPositive→拒绝` | R-3 / E-3 | `OrderServiceTest.createOrder*`（若改签名需同步） | `ref: SD-02 §10.1` |
| 单元（service） | `OrderServiceTest.priceInvalid→拒绝` | E-6 / T-FR2-10 | — | `ref: SD-02 §10.1` |
| 单元（service） | `OrderServiceTest.stockInsufficient→拒绝` | E-1 / T-FR2-5 | — | `ref: SD-02 §10.1` |
| 单元（service） | `OrderServiceTest.saveFail→restoreStock补偿` | T-1 / T-FR2-8 | `OrderServiceTest.createOrder*` | `ref: SD-02 §10.1 / AC-FR2-8` |
| 单元（service） | `OrderServiceTest.totalAmount=unitPrice×quantity` | R-4 / T-FR2-2 | — | `ref: SD-02 §10.1` |
| 接口（MockMvc） | `POST /order` 表单校验失败→回填+错误 | R-1 / E-4 / T-FR2-7 | `ShopControllerTest`（如有） | `ref: SD-02 §10.2` |
| 接口（MockMvc） | `POST /order` 成功→302 `/order/{orderNo}` | R-2 / T-FR2-1 | — | `ref: SD-02 §10.2` |
| 集成（跨服务） | 下单→扣库存→订单 CREATED→详情页含同类推荐 | §3 / T-FR2-1 | — | `ref: SD-02 §10.3` |
| 集成（并发） | 并发下单同一商品→不超卖 | B-2 / T-FR2-12 | — | `ref: SD-02 §10.3 / AC-X3` |

> 「受影响既有用例」由 §3.5 改造定位推导：改到哪个类/方法，就把该类的既有测试列入回归（本 Feature 已实现，当前影响面为空；若按 §3.5 演示新增优惠码，则 `OrderServiceTest.createOrder*` 全部需回归）。

## 12. 待澄清项（OPEN）

| 编号 | 来源位置 | 问题 | 澄清需求 | 状态 |
| --- | --- | --- | --- | --- |
| OPEN-1 | docs/01-PRD.md §11 EX-6 | PRD 标注「现状无显式校验，需研发补强」，但代码 `OrderService.createOrder` 已实现价格异常拒绝（「商品价格异常，无法下单」） | 需产品确认：是否回填 PRD EX-6 状态为「已实现」，并纳入 AC | ☐ 待澄清 |
| OPEN-2 | docs/01-PRD.md FR-2 步骤 1 | 确认页库存文案 PRD 为「购买数量超过库存」，代码为「购买数量超过库存（当前库存 X 件）」 | 需产品确认统一文案口径（建议保留带库存数版本） | ☐ 待澄清 |

> **延伸标注（非 OPEN）**：当前代码在 FR-2 基础上新增了 Rx 处方药购药流程（处方单号必填、Saga 风格补偿），属后续迭代 `docs/07-PRD-IM.md`（FR-D1）范围，**本 Spec 不做**；如需覆盖请走 `/sdd` 对 IM PRD 单独产出 Spec。
> **现状差异（非 OPEN）**：下单下游已由 product-service 改为 medicine-service（`MedicineClient`），本 Spec §3/§4 按 PRD 原文保留 product 表述并在此标注（见 §3.5）。

## 13. 研发方案（Implementation Plan）

> 本 Feature 已实现，以下用「新增优惠码校验」这一**假设需求**演示研发方案写法（真实转化时列出本次 PRD 变更的落地清单）。

**分文件改造清单**：

| # | 文件 | 改动（新增/修改/删除 + 一句话） | 引用规则 | 模块约束 |
| --- | --- | --- | --- | --- |
| 1 | `order-service/.../service/OrderService.java` | `createOrder` 增优惠码校验段（新增 `validateCoupon`） | C-3（fail-closed 校验）、T-5（先扣后落顺序） | `agents/order-service.md`「下单不变量」 |
| 2 | `order-service/.../form/OrderForm.java` | 新增 `couponCode` 字段（`@Size(max=16)` 可选） | C-3 | `agents/order-service.md` |
| 3 | `order-service/.../web/ShopController.java` | `POST /order` 表单绑定 `couponCode`（:84） | C-1（web 层薄） | `agents/order-service.md` |
| 4 | `order-service/.../templates/order-confirm.html` | 表单加优惠码输入框 + 展示 | C-1 | `agents/order-service.md`「页面走模板」 |
| 5 | `order-service/.../client/MedicineClient.java` | 无改动（复用 `getMedicine` 取单价/折扣） | T-3 | `agents/order-service.md` |

**分层改动点**（勾选本次涉及）：web（页面/表单）✓ · api（JSON）□ · service（编排/事务）✓ · client（下游）□ · repository（SQL）□ · model/DTO ✓

**受影响既有用例**：`OrderServiceTest.createOrder*` 系列（若改 `createOrder` 签名，全部需同步）；`ShopControllerTest`（若有）。

**验证方式**：起服务后带 `X-Trace-Id: demo-coupon` 走下单链路（POST /order），按 traceId 串 order→medicine 日志验证校验段生效（T-6）；另跑 `./mvnw.cmd clean test` 全量回归。

---

## 转化自检（对应 `harness/commands/checklists/spec-quality-gates.md`）

- [x] **A-1~A-4 双向可追溯**：每条 Spec 项带 `ref`（FR-2/AC-FR2-*/EX-*/NFR-*/SD-02），可从 PRD 反向定位
- [x] **A-5 术语对齐**：§0 用词全部可在 `harness/references/glossary.md` 解析（下单/原子扣减/回补/幂等键/快照/订单号/Rx/统一错误体/埋点）
- [x] **B-1~B-4 可测试性**：R-3~R-6 可转断言；每个异常（E-1~E-36）有对应验收；NFR 有量化验收
- [x] **C-1~C-4 无歧义**：边界均为明确数字（quantity∈[1,999]、DECIMAL(12,2)），无歧义词；两处口径差异入 OPEN
- [x] **D-1~D-3 覆盖完备**：FR-2 全部 AC（9）+ 关键 EX（11）+ NFR（4）已映射
- [ ] **D-4 OPEN 清零**：OPEN-1/2 未清零 —— **故意保留**：它们正是管线应暴露的「需产品拍板」项（EX-6 已实现未回填 PRD、库存文案口径）。正式转化时须先澄清清零，Spec 才能进 `REVIEWED`
- [x] **E-1~E-4 单一事实来源**：与方案 §3/§4/§5/§6/§8、代码 `OrderService`/`ShopController`/`MedicineClient` 一致；Rx 流程与 product→medicine 差异标注为延伸/现状差异
- [x] **E-5 改造定位可执行**：§3.5 现状调用链 file:line 实测可定位；演示改动点含插入点与不变量，证据来源已标注
- [x] **F-1~F-3 自包含**：每条含文件+章节定位，验证方式在 §11/§13
- [x] **G-1 规则引用齐全**：§2.5/§13 引用 T-3/T-4/T-5、C-1/C-3/C-4，无自造中间件/模式
- [x] **G-2 模块约束已应用**：§13 引用 `agents/order-service.md`（下单不变量、页面走模板）
