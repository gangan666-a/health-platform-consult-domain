# 上线就绪报告（Release / Go-Live Readiness）

## 结论

**可上线（Go-Live Ready）** —— 依据大厂流程：PRD → 方案设计 → 方案评审 → 代码实现 → 测试（单元/接口/功能）→ Bug 闭环 → 全量测试通过。

- 测试：**199 用例全部通过，0 失败 0 错误**（见《05-test-report.md》）
- 评审阻断项（1 P0 + 3 P1）已全部修复并回归
- 无未决 P0/P1 问题

## 本迭代交付范围

| 阶段 | 产出 | 位置 |
| --- | --- | --- |
| PRD（产品） | 需求/验收标准/边界场景 62 条验收 | `docs/01-PRD.md` |
| 方案设计（研发） | 架构/API/DB/状态机/容错/并发/测试策略 + 10 条 ADR | `docs/02-solution-design.md` |
| 方案评审 | 1 P0 + 3 P1 + P2 问题清单，有条件通过 | `docs/03-design-review.md` |
| 代码实现 | 修复评审全部阻断项 + 规范化（见下） | 各服务 src/main |
| 测试 | 单元 + 接口 + 功能共 199 用例 | 各服务 src/test |
| 测试报告 | Bug 闭环记录 + 遗留决策 | `docs/05-test-report.md` |

## 代码整改清单（生产代码）

**payment-service**
- P0：`setStatus(PROCESSING)` 前置到 `saveAndFlush` 前，首付正常落库
- 金额 null/≤0 前置校验；约束异常按「唯一冲突(23505)→重复支付」与「其他→通用文案」区分
- OrderClient 增加 connect/read 超时；异常处理不回显内部信息

**order-service**
- 新增 `RestClientConfig`：统一客户端超时（connect 3s / read 5s，推荐 12s）
- `ShopController.paySuccess` 推荐等待加 `orTimeout(2s)` + 降级空列表，满足页面渲染 ≤3s NFR
- 下单金额校验（价格 null/≤0、数量 ≥1、总价 >0）；补偿失败 `log.error` 留痕
- `cancelOrder` 改为先落库 CANCELLED 后回补库存，杜绝孤儿二次回补
- `order-confirm.html` 渲染数量校验错误提示；错误处理统一 `{"message":...}` 不回显

**product-service**
- 扣库存 `quantity` 必填，缺参 400；价格 ≤0 视为业务异常 400
- 原子扣减 `WHERE stock>=?` 保持（防超卖）

**recommend-service**
- 编排层防御性 try-catch：AI 异常回退规则、规则异常返回空，绝不 500
- 编排层统一按 topN 截断；topN≤0 语义一致化
- `RecommendRequest` 加校验，缺必填字段 400；`RecommendationController` 启用 `@Valid`
- AI 层 null/空 content 直接返回空，去掉脆弱 NPE
- 规则互补热销补足去重 bug 修复

**mall-common**
- `RecommendRequest` 加 `@NotNull` 校验注解

## 上线检查清单

- [x] 全量编译通过（`mvnw clean compile`）
- [x] 全量测试通过（`mvnw clean test`，199/199）
- [x] 评审阻断项已关闭（P0-01 支付重试/首付、P1-01 超时、P1-02 补偿、P1-03 金额）
- [x] 错误体统一 `{"message":...}`，异常处理不回显内部信息
- [x] 卡号仅存尾号 4 位脱敏
- [x] 推荐降级（AI→规则→隐藏区块）已测
- [ ] 上线环境需配置 `deepseek.api-key`（无 Key 时自动降级规则推荐）
- [ ] 建议做一次真实 MySQL + 四服务联调冒烟（`run.cmd`），手工走通「下单→支付→推荐」

## 遗留风险（生产已知，非本期阻断）

1. **分布式事务**：下单「扣库存」与「落订单」、支付「流水」与「订单状态」跨库无强一致，靠补偿 + 幂等兜底；生产需 Saga/Outbox（README 已声明）。
2. **orderNo 生成**：时间戳 + 4 位随机，同秒高并发有碰撞概率，依赖唯一索引兜底；生产建议雪花/序列。
3. **restore 超原库存**：无条件回补可虚增库存，cancel 主路径已防二次回补；建议后续加对账。
4. **资源不存在返回 400 而非 404**：统一契约，符合设计，评审已知悉。

## 发布建议

- 建议按服务独立发布（order→payment→product→recommend 无强依赖顺序，可并行启动）。
- 建议 CI 先 `clean` 再 `test`（规避本机 Eclipse JDT/旧 class 干扰，见 QA 报告备注）。
- 上线后监控：下单成功率、支付成功率、推荐服务可用率、页面渲染时长（NFR-10）。
