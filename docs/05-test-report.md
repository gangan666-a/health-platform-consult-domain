# 测试报告（Test Report）

> 依据《04-test-plan.md》执行。执行命令：`./mvnw.cmd clean test`（JDK 25）。
> 结论：**全部通过，可进入上线放行评审。**

## 执行结果

| 模块 | 单元测试 | 接口测试 | 功能测试 | 小计 | 失败/错误 |
| --- | --- | --- | --- | --- | --- |
| product-service | 18 | 15 | 6 | **39** | 0 / 0 |
| order-service | 27 | 37（6 API + 31 Web） | 6 | **70** | 0 / 0 |
| payment-service | 23 | 8 | 6 | **37** | 0 / 0 |
| recommend-service | 46（service14+rule15+ai17） | 7 | — | **53** | 0 / 0 |
| **合计** | | | | **199** | **0 / 0** |

- 整体：`Tests run: 199, Failures: 0, Errors: 0` → **BUILD SUCCESS**
- 覆盖分层：单元（纯逻辑/状态机/金额精度/幂等）、接口（REST 契约/错误体/参数校验/状态码）、功能（Spring 全上下文 + H2 + MockMvc，mock 下游 HTTP 客户端，覆盖完整链路与降级）

## Bug 闭环记录（测试发现 → 开发修复 → 回归通过）

| 编号 | 级别 | 模块 | 位置 | 问题 | 修复 |
| --- | --- | --- | --- | --- | --- |
| BUG-1 | **P0** | payment | PaymentService | 先 `saveAndFlush` 后 `setStatus(PROCESSING)`，flush 时 status=null 触发 NOT NULL → 被 catch 误报「重复支付」→ **首付必 400、0 行落库** | `setStatus` 前置到 flush 前；4 条失败用例（功能/并发/单测）回归通过 |
| BUG-2 | **P1** | payment | PaymentService | 金额 null/≤0 未校验，被当作重复支付误报；catch 把所有约束违规统一转成「重复支付」 | 金额校验前置抛独立业务异常；catch 区分唯一约束(23505)→重复支付 400、其他→通用文案+log.error |
| BUG-3 | **P1** | order | OrderService.cancelOrder | 先回补库存后落库 CANCELLED，save 失败 →「已回补未取消」孤儿回补，再次取消二次回补库存虚增 | 改为**先落库 CANCELLED 后回补**，堵死二次回补；回补失败 log.error 留痕不抛新异常；回归用例改断言 save 失败不触发回补 |
| BUG-4 | **P2** | order | order-confirm.html | quantity 校验失败未渲染 `th:errors`，用户看不到「购买数量必须大于 0」 | 数量改 number 输入 + 渲染 `th:errors`，与其它字段一致 |
| BUG-5 | **P2** | recommend | RecommendationService | 编排层无 try-catch，AI 异常直上抛 500，降级依赖 AI 层内部 catch | 编排层防御性 try-catch → 回退规则，规则异常返回空不 500 |
| BUG-6 | **P2** | recommend | RecommendationService | 编排层不截断 topN；topN≤0 语义两处不一致 | 编排层统一按 topN 截断；topN≤0 统一返回空 |
| BUG-7 | **P2** | recommend | RecommendRequest | 缺 catalog/purchasedItems 返回 200 空而非 400 | 加 `@NotNull` + Controller `@Valid`，缺必填 400；空集合仍为 200 合法边界 |
| BUG-8 | **P2** | recommend | AiRecommendationService | null/空白 content 时 `stripCodeFence` NPE（靠外层 catch 吞） | null/空 content 直接返回空，不依赖外层 catch |
| BUG-9 | **P2** | product | ProductController | deduct 缺 quantity 参数静默默认 1 扣减，误扣风险 | 参数必填，缺参 400「quantity 不能为空」；`verify(never)` 确认不触达 service |

## 验收标准达成情况

- 覆盖 PRD 核心验收：下单/库存/取消/支付/推荐/降级，含 EX 边界与 AC 异常验收。
- 非 happy-path 专项：库存不足与超卖、金额精度 0.1×3、幂等回调、并发首付单成功败者 400、FAILED 重试、取消二次回补、推荐超时降级（2s）、AI 幻觉过滤（目录外/已购/重复/异品类）、非法 JSON、缺参/缺字段 400、非法状态流转、卡号尾号脱敏。

## 遗留已知决策（非阻断，上线评审知悉）

| 项 | 说明 | 决策 |
| --- | --- | --- |
| 资源不存在用 400 而非 404 | 各服务统一 `{"message":...}` + 400，符合设计 §3.1 契约 | 保持，README/设计已声明 |
| restore 无条件回补可超原库存 | 设计 §8.1「无条件追加」，多次调用可虚增；cancel 已改先落库杜绝二次回补主路径 | 保持，演示级 |
| orderNo 4 位随机后缀碰撞风险 | 同秒高并发碰撞，依赖唯一索引兜底 | 已知 P3，生产需雪花/序列 |
| 分布式事务最终一致 | 仅「失败补偿 + 幂等」兜底，无 Saga/Outbox | 演示级，README 已声明 |
| DeepSeek 无 Key 降级规则推荐 | 无 Key/超时/异常全部回退规则，页面不报错 | 已测，符合 PRD FR-9 |

## 建议（生产前）

- 本报告基于 H2 内存库 + mock 下游客户端的功能测试；生产上线前建议做一次真实 MySQL + 四个服务联调的**环境冒烟**（可用 run.cmd 一键启动后手工走通「下单→支付→推荐」主路径）。
- `restoreStock` 超原库存与 orderNo 生成器，如需更严可在后续迭代引入对账与雪花 ID。
