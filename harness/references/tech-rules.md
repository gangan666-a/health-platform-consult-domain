# 技术架构规则（T-x）

> 全仓统一的**技术架构 rules**：用什么中间件、服务间怎么通信、数据边界、一致性、链路追踪纪律。Spec §2.5 列出本 Feature 适用的 T-x 并引用，**不许自造中间件或通信模式**。事实来源：`docs/02-solution-design.md`、`docs/08-design-IM.md`、`docs/13-code-guide.md`、`docs/trace/02-instrumentation-design.md`。

## T-1 必用中间件与存储

| 组件 | 用途 | 强制语义 |
| --- | --- | --- |
| MySQL 8.4 | 业务存储 | **每服务独立 schema**（`shopdb_<service>`）；建库授权在根 `init.sql`，各服务**只建表不建库** |
| Spring Data JPA | ORM | `ddl-auto=update` 自动建表；新表/新列随 Spec §5 数据模型走 |
| Spring `RestClient` | 服务间 HTTP | 不用 Feign；统一在 `RestClientConfig` / 各 Client 构造器挂 `TraceContext.interceptor()` 传播 traceId |
| `mall-common` | 共享库 | DTO/枚举/`BusinessException`/`ApiError`/埋点四件套；被所有服务 compile 依赖 |

## T-2 领域中间件（按业务域可选）

| 组件 | 用在哪 | 纪律 |
| --- | --- | --- |
| Netty WebSocket | 仅 `consult-service`（:8086 实时聊天） | 事件循环内**零阻塞**（写操作走 `MessageWriteQueue` 批量落盘）；WS 线程无 MDC，traceId 记 `-`（设计预期非 bug） |
| DeepSeek API | `recommend-service` AI 推荐、`agent-service` 多 Agent | 无 Key/调用失败 → **自动降级**：推荐走规则补足，Agent 走占位回复，绝不 500 |
| Elasticsearch | 仅 `agent-service` RAG 向量检索（`health-knowledge` index） | 连接失败 → `KnowledgeRetrievalService` 降级为空上下文，不阻断主流程 |
| Spring AI | 仅 `agent-service`（ChatClient + @Tool + ChatMemory） | 会话内存态（`AgentSessionStore`），进程重启即失，属已知限制 |

## T-3 服务间通信与错误契约

- 统一错误体 `{"message":"…"}`（`ApiError`）；下游错误由各 Client 经 `ClientSupport.toBusinessException` 转成业务异常（order 侧）。
- 下游不可用 → 显式映射为「XX 服务不可用，请稍后重试」，不得把下游 500 原样透传。
- 超时纪律：默认 `connect 3s / read 30s`；LLM 慢接口（agent chat）单独 builder 放宽 read 60s（见 `order-service/client/RestClientConfig.java`）。
- 服务只暴露业务语义端点；健康检查用真实 API 探测（无根映射服务 `GET /` 返回 500 属正常）。

## T-4 数据边界

- **每服务独立 DB，禁止跨库 JOIN**；跨服务只读一律走 REST（order→product/medicine/payment/consult）。
- **快照语义**：跨库数据进入本库即固化（`OrderItem` 存商品名/单价/数量快照），后续源数据变更不影响历史。
- 跨服务写动作必须带**幂等键**（如 `order_no` UK、处方消费 `orderNo`）。

## T-5 一致性（无分布式事务）

- **不用**真实分布式事务/Saga 框架/Outbox/MQ（PRD NG-4）；采用**失败补偿 + 流水状态**。
- 跨服务动作固定「先扣后补」：主路径成功即落库；失败走补偿（`restoreStock` / `restorePrescription`）。
- **补偿 best-effort，不掩盖主因**：补偿失败只记 ERROR 日志（含 orderNo/productId/quantity）供人工对账，不吞主异常。
- 回调幂等：`confirmPayment` CAS `CREATED→PAID`，重复回调不改结果。

## T-6 链路追踪（动态埋点）纪律

- 入站 `TraceFilter` 解析 `X-Trace-Id` → MDC；出站 RestClient 挂 `TraceContext.interceptor()` 自动带上游 traceId，形成跨服务一条完整 trace。
- **强制执行（不靠自觉）**：`.claude/hooks/check-instrumentation.py`（PostToolUse，matcher Write|Edit|MultiEdit）自动门禁——任何调用 Spring `RestClient.builder()/create()` 的 `.java` 必须含 `TraceContext.interceptor()`，违规 exit 2 阻断并回喂模型自纠（fail-closed）；只注入/消费 Builder Bean 的客户端、Elasticsearch 客户端（`org.elasticsearch.client.RestClient` / `co.elastic.clients`）豁免。手工复跑：`python .claude/hooks/check-instrumentation.py --check <file>`。
- `TraceAspect` 零侵入切 `@RestController`/`@Service`，记 `[TRACE]` ENTER/EXIT/ERROR，**异常记录后原样重抛，绝不吞异常**。
- 新功能验证：给入口请求显式带 `-H "X-Trace-Id: <id>"`，按 traceId 串联多服务日志（见 `docs/trace/03-dynamic-traces.md`）。
- WS/异步线程无 MDC，traceId 记 `-`，不阻断功能；如需追踪需在帧处理器手工写 MDC（已知限制）。

## T-7 文件上传与解析引擎（报告解读专用）

- **文件边界**：multipart 上传白名单格式（pdf/png/jpg/jpeg/webp）、大小上限（10MB）、`consent` 授权 fail-closed（默认拒绝）；越权访问他人数据拒绝。
- **格式门禁（v1.5）**：扩展名/Content-Type 可伪造不作信任依据，上传内容按魔数识别（`FormatSniffer`：pdf/jpeg/png/webp/heic）；HEIC/HEIF → 存储具备转码能力（OSS `x-oss-process=image/format,jpeg`）时自动转 JPEG 供解析（原文留档仍存原始字节，`OssReportFileStorage.transcodeHeic` 一次性临时管道），否则定向拒绝并提示「iPhone 默认拍照格式，请转 JPEG/PNG 重传」（纯 Java 解 HEIC 无好方案，TwelveMonkeys 官方无 HEIF 解码器，`com.twelvemonkeys.imageio:imageio-heif` 不存在）；webp 经 twelve-monkeys `imageio-webp`（只读不写）解码归一为 JPEG/PNG；任何无法解码的图片内容 fail-closed 拒绝，绝不把未知格式裸字节静默送达 VLM；错误消息区分原因（空/超限/扩展名/内容无法识别/HEIC）。
- **解析引擎一律接口抽象**：`OcrEngine`（OCR 通道）与 `VisionAnalyzer`（多模态通道）必须是接口 + 可降级实现；无真实 Key/引擎不可用 → Mock/占位，**绝不 500**（对齐 T-2）。
- **双通道规则**：结构化版式（化验单/体检单/扫描 PDF）走 OCR 精确抽数值；复杂版式/影像走多模态兜底；解析不出的指标标 `UNKNOWN`，不猜默认。
- **外部只读依赖**：参考范围对齐走 ES `lab_exam`，ES 不可用 → 本地默认规则标记并标注「参考范围未经校验」，不阻断落库。
- **隐私**：入库/入 prompt 前脱敏（剥离 PII）；解读 prompt 数据最小化；报告数据不用于 LLM 训练（依赖服务商协议/私有化，设计约束）。

## T-8 模型切换单一事实源（升级 / 兜底模型）

- **单一事实源**：所有"切更强模型"的配置只允许来自 `.claude/llm-switch.json`（cc-switch 风格，每供应商字段：`role`（`chat`|`vision`）/ `name` / `website` / `baseUrl` / `apiKey` / `model` / `escalationModel`）。**禁止**在 `application.properties`、Java、文档里硬编码模型名。
- **渲染与消费**：`.claude/hooks/sync-llm-model.py` 幂等渲染为 `agent-service/src/main/resources/llm-models.properties`（确定性顺序 + LF），经 `spring.config.import=optional:classpath:llm-models.properties` 被 Spring 消费；**主与测试 properties 都要挂该 import**（测试资源整文件遮蔽主配置，漏挂会静默沿用旧值）。
- **两条升级链同源**：LLM 升级（`AbstractSubAgent` → `agent.llm.escalation-model`）与视觉升级（`GlmVisionAnalyzer` → `agent.report.vision.fallback-model`）读同一份渲染结果；换更强模型 = 改 JSON 一行，Java 零改动。
- **强制执行（不靠自觉）**：`sync-llm-model.py`（PostToolUse 命中 `.claude/llm-switch.json`；SessionStart 对齐渲染）——role 非法 / 字段缺失 / 出现停用模型名（`deepseek-chat`、`deepseek-reasoner`）→ exit 2 阻断并回喂模型自纠（fail-closed）；SessionStart 不能 block，仅对齐。手工复跑：`python .claude/hooks/sync-llm-model.py --check`。
- **密钥不进渲染**：`apiKey` 仅作展示（只填 `${ENV}` 占位），真实 Key 仍走 `application.properties` 的 `${DEEPSEEK_API_KEY:...}`，避免密钥复制成两份。
- **模型名时效**：DeepSeek 已于 2026-07-24 停用 `deepseek-chat` / `deepseek-reasoner`（映射 V4 Flash/Pro）；当前主档 `deepseek-v4-flash`、升级档 `deepseek-v4-pro`。
