# 统一术语表（术语对齐）

> 概念层对齐的单一事实来源。Spec §0 必须把本 Feature 用到的每个关键业务/技术概念映射到本表；查不到 → 记 Spec §12 OPEN 或先在此登记（见「待确认」区），**不得凭空造词**。
> 事实来源：`docs/01-PRD.md`、`docs/07-PRD-IM.md`、`docs/02-solution-design.md`、`docs/08-design-IM.md`、`docs/13-code-guide.md`、`docs/trace/*`。代码符号以当前代码为准（行号仅供参考，见各 agents/* 与 module-contracts.md）。

## 映射表

| 统一术语 | 一句话定义 | PRD 术语 | 代码符号（模块） | 状态 |
| --- | --- | --- | --- | --- |
| 下单 | 用户确认商品与数量、填收货信息后创建订单并扣减库存 | FR-2 下单 | `ShopController.createOrder` → `OrderService.createOrder`（order） | ✅ |
| 原子扣减 | 数据库层 `UPDATE … WHERE stock>=?` 一次性扣减，**并发正确性唯一边界** | 原子扣减 | `ProductService.deductStock`（product）/ `MedicineService.deductStock`（medicine） | ✅ |
| 库存回补 | 下单失败时把已扣库存加回，best-effort 不掩盖主因 | 回补库存 | `ProductService.restoreStock`（product）/ `OrderService.restoreStockQuietly`（order） | ✅ |
| Saga 补偿 | 无分布式事务下的跨服务失败回补（先扣后补） | 失败补偿 | `OrderService.createOrder`(Rx)（order） | ✅ |
| 幂等键 | 保证重复提交不重复生效的唯一业务键 | 幂等 | `shop_order.order_no`(UK)；处方 `orderNo`（consult） | ✅ |
| 快照 | 下单时固化商品名/单价/数量，后续改价不影响历史 | 明细快照 | `OrderItem`（order） | ✅ |
| 订单号 | `SO+yyyyMMddHHmmss+4位随机`，业务单号，≤32 位 | 订单号 | `OrderService` 生成（order） | ✅ |
| 支付回调 | payment 回调 order 确认支付，幂等 `CREATED→PAID` | 支付确认回调 | `OrderApiController.confirmPayment`（order）/ `OrderClient.confirmPayment`（payment） | ✅ |
| AI 推荐 | DeepSeek 大模型推荐同类/互补商品 | AI 智能导购 | `AiRecommendationService`（recommend） | ✅ |
| 规则补足 | AI 结果不足 topN 时按关键词规则补足 | 规则推荐 | `RuleRecommendationService`（recommend） | ✅ |
| 降级 | 外部不可用时吞错回退空结果/已收集结果，**绝不 500** | 自动回退 | `RecommendClient`（order）/ `KnowledgeRetrievalService`（agent） | ✅ |
| 问诊会话 | 一次在线问诊会话，状态 `WAITING/ONGOING/PRESCRIPTION_ISSUED/ENDED` | 在线问诊/会话 | `ConsultationSessionService` / `SessionLifecycle`（consult） | ✅ |
| 接诊 | 医生认领会话 `WAITING→ONGOING`（CAS） | 医生接单 | `ConsultationSessionService.accept`（consult） | ✅ |
| 开方 | 医生开具处方并置会话 `PRESCRIPTION_ISSUED`（同事务） | 开方 | `PrescriptionService.prescribe`（consult） | ✅ |
| 处方 | 医生开的购药凭证，状态 `ACTIVE/USED` | 处方 | `PrescriptionService`（consult） | ✅ |
| 处方消费 | 凭方下单一次性消费处方 `USED`（CAS 防复用） | 处方购药 | `PrescriptionService.consume`（consult） | ✅ |
| Rx 药品 | 需处方购药的药品（`rxFlag=true`），无处方号拒下单（fail-closed） | 处方药 | `Medicine.rxFlag`（medicine）/ `OrderService.createOrder` Rx 分支（order） | ✅ |
| 药品目录 | 药品列表/详情/原子扣减/回补接口 | 药品品类 | `MedicineController` / `MedicineClient`（medicine） | ✅ |
| 在线问诊 WS | Netty WebSocket 实时聊天（:8086，browser 直连，持 token） | IM 在线问诊 | `WsFrameHandler`（consult） | ✅ |
| 导购 Agent | 按 `AgentType` 分型的 AI 助手（1 Manager + 6 Sub Agent） | AI 助手/多 Agent | `AgentOrchestrator` / `SubAgentRegistry`（agent） | ✅ |
| handoff | Sub Agent 将对话转交 peer Agent 续聊 | 交接 | `HandoffOrchestrator` / `HandoffTools`（agent） | ✅ |
| Supervisor 委派 | Manager 通过 `@Tool` 委派 Sub Agent 路由 | 路由 | `SupervisorOrchestrator` / `ManagerDelegateTools`（agent） | ✅ |
| Safety 前置 | 所有消息先过合规/紧急拦截，命中即返回 | 安全审查 | `SafetyAgent.preCheck`（agent） | ✅ |
| RAG | 基于知识库检索增强生成（ES 向量检索） | 知识库 | `KnowledgeRetrievalService`（agent） | ✅ |
| 埋点 | 零侵入 AOP 链路追踪（ENTER/EXIT/ERROR） | 动态埋点 | `TraceAspect` / `TraceFilter` / `TraceContext`（mall-common） | ✅ |
| traceId | 贯穿一次请求跨服务的链路 ID，经 `X-Trace-Id` 传播 | 链路追踪 | `TraceContext`（mall-common） | ✅ |
| 业务异常 | 服务端校验失败抛出的业务错误，不吞不改 | 业务异常 | `BusinessException`（mall-common） | ✅ |
| 统一错误体 | 服务间错误响应统一 `{"message":"…"}` | 错误体 | `ApiError`（mall-common）/ `ClientSupport.toBusinessException`（order） | ✅ |
| BFF | 浏览器唯一入口（:8082），聚合下游、签发 WS token | BFF | `order-service`（order） | ✅ |
| schema | 每服务独立数据库，禁止跨库 JOIN | 独立库 | `shopdb_product/order/payment/consult/medicine` | ✅ |
| DTO | 跨层/跨服务数据传输对象（避免暴露 JPA 实体） | DTO | `*DTO`（mall-common） | ✅ |
| 会话令牌 | WS 直连校验用的 token（user/doctor） | token | `ConsultClient.createSession` / `refreshUserToken`（order） | ✅ |
| 报告解读 | 解析用户上传的检查报告并严谨解读（客观中立/免责） | 报告解读 | `ReportAgent` / `ReportParser`（agent） | ✅ |
| 检查报告 | 化验单/体检单等检查结果文档（PDF/图片） | 化验单/体检单 | `ReportDoc`（agent） | ✅ |
| 化验单 | 结构化指标+数值+参考范围的检验报告 | 化验单 | `ReportTypeDetector` / ES `lab_exam` | ✅ |
| OCR | 从图片/扫描件提取文字（化验单数值抽取） | 文字识别 | `OcrEngine` / `MockOcrEngine`（agent） | ✅ |
| 多模态 LLM | 直接理解图片/复杂版式的视觉模型 | 图片理解 | `VisionAnalyzer` / `MockVisionAnalyzer`（agent） | ✅ |
| 参考范围 | 指标正常区间（lab_exam 指标 ref_range） | 参考区间 | ES `lab_exam.indicators.ref_range` | ✅ |
| 指标 | 单个检查项目（名称+数值+单位+参考范围+状态） | 检查项目 | `ReportIndicator`（agent） | ✅ |
| 异常标记 | 指标相对参考范围的判定（HIGH/NORMAL/LOW/UNKNOWN） | 高/正常/低 | `IndicatorStatus`（agent） | ✅ |
| 长期记忆 | 用户健康档案摘要，注入后续 Agent 上下文 | 健康档案 | `HealthProfile` / `HealthMemoryService`（agent） | ✅ |
| 报告对比 | 同指标不同日期结果并列展示 | 新旧对比 | `ReportCompareService`（agent） | ✅ |
| 脱敏 | 剥离姓名/证件号/医院等 PII 后再入库/入 prompt | 隐私保护 | `ReportComplianceService`（agent） | ✅ |
| 数据最小化 | 解读 prompt 仅含必要检查数据，不含 PII，不用于训练 | 不用于训练 | `ReportComplianceService.buildLlmInput` | ✅ |
| 授权同意 | 上传报告前用户同意存储/解读/对比 | 用户同意 | upload 入参 `consent`（fail-closed） | ✅ |
| 免责声明 | 回复保留客观中立与「不构成诊断」声明 | 免责 | `ReportAgent.systemPrompt()` 尾句 | ✅ |

## 待确认（⚠️ 新词登记，转化时在此区追加；确认后升为 ✅）

> 命中 `docs/01-PRD.md` / `docs/07-PRD-IM.md` 里本表没有的业务概念时，先在此登记、再决定进表还是回产品澄清。例如：「优惠券」「积分」「购物车」等 PRD 未定义、代码未实现的词 → 属 scope creep，不入表。

| 统一术语 | 一句话定义 | PRD 术语 | 代码符号 | 状态 |
| --- | --- | --- | --- | --- |
| （暂无登记） | — | — | — | ⚠️ |
