# Spec：报告解读 Agent（REPORT · 问诊 toC 健康助手）

| 项 | 内容 |
| --- | --- |
| Feature | 报告解读 Agent：用户上传体检单/化验单（PDF/图片），plan-execute 解析（页型注册表 + 页级并行）→ 参考范围比对（自带优先）→ 危急值规则 → 严谨解读 → 长期记忆 → 同指标历史对比；支持异步任务 + SSE 进度 |
| PRD 版本 | `docs/16-PRD-REPORT.md`（报告解读 Agent PRD v1.0，2026-08-25）；需求来源 = 该 PRD + PM 设计评审对话（2026-08-24，ref 前缀 `PM-DESIGN-2026-08-24`）+ 现状代码 + v1.1 架构升级（2026-08-29，ref 前缀 `ARCH-2026-08-29`） |
| Spec 状态 | DRAFT（v1.5，同步图片格式魔数门禁 + webp 归一 + HEIC 自动转码/拒绝） |
| 负责人 | 研发 / 测试 |
| 关联 Spec | FR-2-place-order.spec.md（复用 order→agent 链路）；ES 知识库索引（`agent-service/src/main/resources/es/*`） |
| 覆盖 PRD 元素 | `16-PRD-REPORT.md` v1.0（FR-R-1..R-7）；覆盖设计对话 6 项决策 + 隐私合规红线 + v1.1 八项架构决策（见 §1） |
| 触及业务域 | M-7 agent-service（端口 8087，DB `shopdb_agent`） |

---

## 0. 术语对齐

> 查 `harness/references/glossary.md`。新词登记 glossary「待确认」区并回写（见 §13）。

| Spec 用词 | 统一术语（glossary） | PRD 术语 | 代码符号 | 模块 |
| --- | --- | --- | --- | --- |
| 报告解读 | 报告解读 | 报告解读 | `ReportAgent` / `ReportParser`（agent） | M-7 |
| 检查报告 | 检查报告 | 化验单/体检单 | `ReportDoc`（agent） | M-7 |
| 化验单 | 化验单 | 化验单 | `PageType.LAB` | M-7 |
| 页型 | — | 页面类型 | `PageType`（8 种：COVER/SUMMARY/LAB/ULTRASOUND/RADIOLOGY/ECG/DISCLAIMER/OTHER） | M-7 |
| 页型注册表 | — | — | `PageTypeRegistry` + `resources/report/pages/*.md` | M-7 |
| 多模态 LLM | 多模态 | 图片理解 | `VisionAnalyzer`（agent） | M-7 |
| 参考范围 | 参考范围 | 参考区间 | ES `lab_exam.indicators.ref_range` | M-7 |
| 指标 | 指标 | 检查项目 | `ReportIndicator`（agent） | M-7 |
| 指标归一 | — | 同义词归一 | `IndicatorNormalizer` + `report/indicator-aliases.json` | M-7 |
| 危急值规则 | — | — | `CriticalValueEvaluator` + `report/critical-rules.json` | M-7 |
| LLM 网关 | — | — | `RoutedChatModel`（@Primary ChatModel：路由+限流+熔断+计量） | M-7 |
| 异步任务 | — | — | `ReportTaskService`（进程内任务队列 + SSE） | M-7 |
| 异常标记 | 异常标记 | 高/正常/低 | `IndicatorStatus{HIGH,NORMAL,LOW,UNKNOWN}` | M-7 |
| 长期记忆 | 健康档案 | 健康档案 | `HealthProfile`（agent） | M-7 |
| 报告对比 | 历史对比 | 新旧对比 | `ReportCompareService`（agent） | M-7 |
| 脱敏 | 脱敏 | 隐私保护 | `ReportComplianceService`（agent） | M-7 |
| 数据最小化 | 数据最小化 | 不用于训练 | `ReportComplianceService.buildLlmInput` | M-7 |
| 授权同意 | 授权 | 用户同意 | `consent`（upload 入参，fail-closed） | M-7 |
| 免责声明 | 免责 | 客观中立 | `ReportAgent.systemPrompt()` 尾句 | M-7 |

## 1. 需求来源

**设计对话 6 项决策（ref: PM-DESIGN-2026-08-24）**：
1. **双通道解析**：OCR 通道（化验单/体检单/扫描 PDF，精确抽数值）+ 多模态通道（复杂版式/影像兜底）；
2. **覆盖范围**：化验单/体检单优先；
3. **实现深度**：接口抽象 + 模拟实现（OcrEngine/VisionAnalyzer 接口 + Mock，无真实 Key 可联调）；
4. **存储归属**：agent-service 加库 `shopdb_agent`；
5. **长期记忆**：结构化指标 + 自然语言摘要结合；
6. **历史对比**：同指标多日期并列（不做前端图表）。

**隐私合规红线（ref: PM-DESIGN-2026-08-24·合规）**：报告数据不用于 LLM 训练（数据最小化：prompt 只发必要报告内容、剥离 PII）；授权 + 脱敏（仅存内部 userId + 检查数据）；报告保留在对话记录，支持历史回看与对比；回复客观中立、严谨、带免责声明。

**v1.1 架构升级决策（ref: ARCH-2026-08-29，共八项）**：
1. **plan-execute 解析模式**：PROFILE（确定性前置工具）→ PLAN（LLM 规划器 + 确定性兜底）→ EXECUTE（页级分类/抽取/聚合/校验，虚拟线程页级并行）；计划与执行轨迹可审计（响应 parseTrace）；
2. **页型注册表**：8 页型各绑定独立抽取 prompt（`report/pages/*.md` 模板进 git 可评审），新增页型零代码改动；
3. **防线1（B-8）**：报告自带参考范围优先于 ES 权威范围（机构方法学差异），异常标记纯 Java 判定（RangeParser），解读 prompt 声明「判定已由系统完成，不得自行比对/推翻」；
4. **防线3（B-7）**：危急值规则引擎（`critical-rules.json` 配置化，指标阈值 + 影像所见关键词），告警强制注入响应与解读 prompt；
5. **同义词归一（B-9，OPEN-3 落地）**：指标名归一到标准名（`indicator-aliases.json`），落库/对比/ES 检索统一口径；
6. **防线4（评测门禁）**：金标样例集 + 字段级 recall/precision 阈值测试（确定性路径 CI 必跑；LLM 路径设 `DEEPSEEK_API_KEY` 自动启用）；
7. **异步任务 + SSE 进度**（一二十页报告不阻塞等待）：进程内有界任务队列（生产演进 MQ + 任务表）；
8. **LLM 统一网关**：@Primary ChatModel 收口全应用调用，多供应商优先级路由 + 每供应商限流/熔断（重试由 Spring AI RetryTemplate 内建，网关不叠加避免双重重试）+ token/成本计量（`GET /api/agents/llm-usage`）。

**非目标红线（NG，本期不得实现）**：
- 不做影像诊断（B超等影像仅文字结论解读，影像本身解读属放射 AI 领域；图片页视觉模型未接入时明确「未识别」，绝不虚构数据——对齐 E-3）；
- 不做真实 VLM 集成（接口 + Mock，接入时仅替换 `VisionAnalyzer` 实现）；
- 不做前端上传页/趋势图表；
- 不写其他领域代码（order BFF 上传代理列后置，见 §13 边界说明）。

**用户角色与权限**：仅用户本人可上传/查看/对比自己的报告；服务端强制校验 `userId`（C-3 fail-closed）。

## 2. 功能规格

| 编号 | 类型 | 描述 | 追溯 |
| --- | --- | --- | --- |
| R-1 | 输入 | multipart 上传：`userId`(必填)、`sessionId`、`file`(pdf/png/jpg/jpeg/webp/heic/heif，≤10MB)、`consent`(默认 false)；**内容按魔数识别**（扩展名/Content-Type 可伪造，不作信任依据） | `（ref: PM-DESIGN-2026-08-24·4/合规）` |
| R-2 | 输出 | 结构化报告 + 解读：指标列表（含数值/单位/参考范围/异常标记）+ 摘要 + 解读文本 + 免责声明 + 异常项建议（触发 handoff 目标） | `（ref: PM-DESIGN-2026-08-24·1/合规）` |
| R-3 | 业务规则 | plan-execute 解析：PROFILE 分页/归一 → PLAN 规划每页通道（TEXT_LLM/VISION）→ EXECUTE 页型分类（8 页型）+ 按页型配方抽取 + 聚合归一（同义词）+ 确定性校验；参考范围比对**自带优先**（B-8），标记 HIGH/NORMAL/LOW/UNKNOWN（解析不出→UNKNOWN，不猜）；危急值规则触发强制提醒（B-7） | `（ref: PM-DESIGN-2026-08-24·1/3 + ARCH-2026-08-29·1/3/4/5）` |
| R-3a | 业务规则 | 零指标且零所见（如无文本层图片件）→ E-3 拒绝，不虚构数据、不落库 | `（ref: ARCH-2026-08-29·NG）` |
| R-4 | 前置条件 | `consent=true`（fail-closed，默认拒绝）；文件格式/大小合法 | `（ref: 合规红线）` |
| R-5 | 后置条件 | 报告记录 + 指标明细落库 `shopdb_agent`；摘要更新健康档案（长期记忆） | `（ref: PM-DESIGN-2026-08-24·4/5）` |
| R-6 | 权限边界 | 仅 `userId` 本人可上传/对比；服务端校验，越权拒绝 | `（ref: 合规红线）` |

## 2.5 业务域与适用规则

**触及业务域**：M-7 agent-service（端口 8087，新增 DB `shopdb_agent`，会话内存态扩展为"报告/记忆持久化"）。

**适用技术架构规则**（`harness/references/tech-rules.md`）：
- `T-2` ES 检索失败 → 参考范围比对降级本地规则，不阻断 —— 理由：对齐既有 ES 降级纪律（`KnowledgeRetrievalService`）；
- `T-3` 统一错误体 `{"message"}`；下游/解析失败映射为友好提示 —— 理由：报告上传是外部输入边界，错误语义必须一致；
- `T-4` 每服务独立 DB，禁止跨库 JOIN —— 理由：`shopdb_agent` 独立建库，报告/指标/健康档案自包含；
- `T-6` 埋点链路：上传→解析→存库→解读 用 traceId 串联 —— 理由：新功能验证走 `X-Trace-Id` 对日志。

**适用代码架构规则**（`harness/references/code-rules.md`）：
- `C-1` 分层：Controller(api) / service(编排) / repository(数据) / model(实体) 分离 —— 理由：报告管线多步，分层保证可测；
- `C-3` 校验 fail-closed：consent 默认 false、文件格式白名单 + 内容魔数校验、userId 必填 —— 理由：合规红线 + 不猜默认；
- `C-5` 错误处理统一 `ApiError`；解析/ES/LLM 异常不吞不 500 —— 理由：T-3 + 降级纪律。

> 纪律：本期引入「multipart 文件上传 + OCR/VLM 接口」属于新模式，已在 `tech-rules.md` 扩展 T-7（见 §13），不视为 Spec 自造；v1.5 图片格式门禁（魔数校验 / HEIC 定向拒绝 / webp 归一 / fail-closed）同列 T-7。

## 3. 流程规格

**主流程步骤**（P-x，标注跨服务调用点）：
1. P-1 浏览器/调试端 multipart 上传 → `POST /api/agents/report/upload`（同步）或 `POST /upload/async`（异步任务，consent/文件校验同步完成，受理即返回 taskId）（`（ref: R-1）`）
2. P-2 `ReportComplianceService.prepareContent` 校验 consent + 扩展名白名单 + 大小 + **内容魔数门禁**（`FormatSniffer.detect`：HEIC 经存储转码（OSS）为 JPEG 或拒绝提示、无法识别拒绝，fail-closed）
3. P-3 PROFILE：`PdfBoxTool.splitPages` 逐页抽文本层 / `ExifTool.normalize` 图片归一化（方向矫正 + 降采样 + 非 JPEG/PNG 源重编码，webp 经 twelve-monkeys 解码，无法解码 fail-closed，不读 GPS）→ `FileProfile`
4. P-4 PLAN：`LlmReportPlanner` 规划每页通道与预期页型（LLM 结构化输出 + 白名单校验，无 Key/失败 → 确定性兜底）
5. P-5 EXECUTE（`PlanExecutor`，虚拟线程页级并行）：页型分类（`LlmPageClassifier`→8 页型，关键词兜底）→ 按页型配方抽取（`LlmTextPageExtractor` 文本通道，失败回退 `TextLayerParser`；`VisionAnalyzer` 视觉通道，未接入时明确未识别）→ `PageAggregator` 聚合归一 + 校验
6. P-6 `ReportReferenceService` 参考范围比对：自带优先（B-8），ES 兜底 + 交叉核验 → 标记 HIGH/NORMAL/LOW/UNKNOWN
7. P-6a `CriticalValueEvaluator` 危急值规则评估（B-7）→ 告警强制注入
8. P-7 `ReportComplianceService` 脱敏（检查所见掩码/剔除 PII 行后进 prompt 与响应）
9. P-8 `ReportUploadService.persist`（TransactionTemplate 收窄）持久化 `ReportRecord` + `ReportIndicatorRecord`（同事务；LLM 解读在事务外）
10. P-9 `HealthMemoryService` 更新健康档案摘要（长期记忆）
11. P-10 RAG 检索（ES `lab_exam`/`report_template`）→ `ReportAgent` LLM 解读（判定由系统完成/危急值原样传达/免责）
12. P-11 返回解读 + 危急告警 + 检查所见 + 解析轨迹 + 异常项建议（handoff 问诊/用药）；异步任务经 SSE/轮询送达

**时序**：
```
调用端              agent-service
 │── multipart ──▶│  ReportController（同步 / upload/async 受理返回 taskId）
 │                 │  └─ ReportComplianceService(授权/白名单/脱敏)
 │                 │  └─ ReportParser(plan-execute 门面)
 │                 │      ├─ PROFILE: PdfBoxTool/ExifTool → FileProfile
 │                 │      ├─ PLAN: LlmReportPlanner(+确定性兜底) → ReportPlan
 │                 │      └─ EXECUTE: PlanExecutor(页级并行: 分类→配方抽取→聚合→校验)
 │                 │  └─ ReportReferenceService(自带优先, ES 兜底交叉核验)
 │                 │  └─ CriticalValueEvaluator(危急值规则, 强制提醒)
 │                 │  └─ persist(TransactionTemplate 收窄, LLM 在事务外)
 │                 │  └─ HealthMemoryService(摘要→健康档案)
 │                 │  └─ ReportAgent(LLM 解读, 经 RoutedChatModel 网关)
 │◀── 解读+危急+轨迹 ──│（异步：SSE progress/done/failed 事件流推送）
```

## 3.5 改造定位（Change Localization）

**证据来源**：代码阅读（grep 实测 2026-08-24）；静态调用链 `docs/trace/01 §6`（order→agent 链路）；trace 文档行号可能过期，以实测为准。

**现状调用链（agent 编排主路径）**：
```
order AgentClient POST /api/agent/chat → agent AgentController.chat:30
  → AgentOrchestrator.chat:36 → SupervisorOrchestrator.route → ManagerDelegateTools.reportAgent:51
  → ReportAgent.handle(AbstractSubAgent:35) → KnowledgeRetrievalService.retrieve → ES(降级空)
```

**待改造清单**：

| 动作 | 类.方法 | 位置（file:line / 插入点） | 变更前后对比 | 必须保持的不变量 |
| --- | --- | --- | --- | --- |
| 新增 | `report/ReportDoc` / `ReportIndicator` | `agent-service/.../report/` 新包 | 前：无；后：解析结构化 DTO | 指标 status 枚举固定 |
| 新增 | `report/ReportTypeDetector` | 同上 | 识别化验单/体检单/其他 | 未知类型不猜，走多模态通道 |
| 新增 | `report/OcrEngine`(接口) + `MockOcrEngine` | 同上 | 模拟抽指标 | 接口不绑定真实引擎 |
| 新增 | `report/VisionAnalyzer`(接口) + `MockVisionAnalyzer` | 同上 | 模拟多模态兜底 | 同上 |
| 新增 | `report/ReportParser` | 同上 | 双通道 → ReportDoc | 结构化→OCR，复杂→Vision |
| 新增 | `report/ReportReferenceService` | 同上 | 对齐 ES lab_exam → status | ES 失败→本地规则降级，不 500 |
| 新增 | `model/ReportRecord` `ReportIndicatorRecord` `HealthProfile` | `agent-service/.../model/` | JPA 实体 | 表结构随 §5 |
| 新增 | `repository/*Repository` ×3 | `agent-service/.../repository/` | JPA 接口 | 对比查询走 index |
| 新增 | `service/ReportUploadService` | `agent-service/.../service/` | 解析→比对→落库→摘要 | 三表同事务 |
| 新增 | `service/ReportCompareService` | 同上 | 同 userId+indicator 多日期并列 | 仅本人数据 |
| 新增 | `service/HealthMemoryService` | 同上 | 摘要写/读 | 幂等覆盖 |
| 新增 | `service/ReportComplianceService` | 同上 | 授权/脱敏/数据最小化 | consent fail-closed |
| 新增 | `controller/ReportController` | `agent-service/.../controller/` | upload + compare 端点 | 越权拒绝 |
| 新增 | `orchestration/tools/ReportTools` | `agent-service/.../orchestration/tools/` | @Tool 对比/健康档案 | 工具权限=本人 |
| 修改 | `agent/specialist/ReportAgent` | 现仅 systemPrompt:32 | prompt 强化 + 注入健康摘要 + tools | 不绕开 AbstractSubAgent 契约 |
| 修改 | `pom.xml` `application.properties` `init.sql` | 各根/模块 | +data-jpa +shopdb_agent | 独立 schema（T-4） |
| 新增 | `report/FormatSniffer` | `agent-service/.../report/` | 魔数嗅探（pdf/jpeg/png/webp/heic），上传校验与解析路由共用事实源 | 未知格式 fail-closed |
| 修改 | `service/ReportComplianceService.prepareContent` + `storage/OssReportFileStorage.transcodeHeic` + `ExifTool.normalize` | `agent-service/.../service/` `.../storage/` `.../report/` | 前：仅扩展名白名单 + 透传裸字节；后：魔数门禁 / HEIC OSS 自动转码或拒绝 / webp 归一 / 无法解码拒绝 | 非 JPEG/PNG 源不静默透传 |

## 4. 接口契约

| 方向 | 方法 | 路径 | 请求 | 响应 | 错误码 | 幂等 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 对外（agent 直接，调试期） | POST | `/api/agents/report/upload` | multipart：`userId`,`sessionId`,`file`,`consent` | `ReportUploadResponse`（结构化指标+检查所见+危急告警+解析轨迹+解读+免责） | 400 未授权/格式错/E-3 未识别；413 超大小；500 内部 | ❌（每次独立报告） | `（ref: PM-DESIGN-2026-08-24·4）` |
| 对外 | POST | `/api/agents/report/upload/async` | 同上 | 202 `{taskId,status,pollUrl,sseUrl}`（校验同步，非法输入仍 400） | 同上 | ❌ | `（ref: ARCH-2026-08-29·7）` |
| 对外 | GET | `/api/agents/report/task/{taskId}` | — | 任务快照（状态/进度事件/结果） | 400 任务不存在或已过期 | ⭐ | 同上 |
| 对外 | GET | `/api/agents/report/task/{taskId}/events` | — | SSE：progress 实时推送 + done/failed 终态 | 同上 | ⭐ | 同上 |
| 对外 | GET | `/api/agents/llm-usage` | — | LLM 调用计量（近期明细+按供应商累计+成本估算） | 500 | ⭐ | `（ref: ARCH-2026-08-29·8）` |
| 对外 | GET | `/api/agents/report/{userId}/compare?indicator=WBC` | path userId + query indicator（入参归一 B-9） | `ReportCompareResponse`（该指标多日期并列 + 解读建议） | 400 越权/无数据 | ⭐ | `（ref: PM-DESIGN-2026-08-24·6）` |

> 错误体统一 `ApiError {"message"}`（T-3）；consent=false → 400「需先授权」。越权访问他人报告 → 400「无权访问」。

## 5. 数据模型（shopdb_agent）

| 表 | 列 | 类型 | 约束/索引 | 说明 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| report_record | id | BIGINT | PK AUTO | 报告记录 | `（ref: §5 设计）` |
| report_record | user_id | BIGINT | IDX | 内部用户 ID（不存 PII） | |
| report_record | session_id | VARCHAR(64) | IDX | 来源会话 | |
| report_record | exam_name | VARCHAR(128) | — | 检查项目（如 血常规） | |
| report_record | report_date | DATE | — | 检查日期，缺省=上传日 | |
| report_record | source | VARCHAR(32) | — | 解析来源（ocr/vision） | |
| report_record | summary | TEXT | — | 自然语言摘要（长期记忆原料） | |
| report_record | created_at | DATETIME | IDX | 上传时间 | |
| report_indicator | id | BIGINT | PK AUTO | 指标明细 | `（ref: §5 设计）` |
| report_indicator | user_id | BIGINT | **IDX(user_id, indicator_name, exam_date)** | 冗余 userId 供对比查询 | |
| report_indicator | report_id | BIGINT | FK→report_record | 归属报告 | |
| report_indicator | indicator_name | VARCHAR(128) | 上 | 指标名（如 WBC） | |
| report_indicator | indicator_value | VARCHAR(64) | — | 数值原文（保精度；`value` 为 H2 保留字，故物理列取 indicator_value） | |
| report_indicator | unit | VARCHAR(32) | — | 单位 | |
| report_indicator | ref_range | VARCHAR(128) | — | 参考范围原文 | |
| report_indicator | status | VARCHAR(16) | — | HIGH/NORMAL/LOW/UNKNOWN | |
| report_indicator | exam_date | DATE | 上 | 检查日期 | |
| health_profile | id | BIGINT | PK AUTO | 长期记忆 | `（ref: PM-DESIGN-2026-08-24·5）` |
| health_profile | user_id | BIGINT | **UK** | 一人一条健康档案 | |
| health_profile | profile_summary | TEXT | — | 摘要（基线指标/关注点） | |
| health_profile | updated_at | DATETIME | — | 更新时间 | |

> 快照语义（T-4）：指标值/参考范围按检查当时固存，源数据后续变更不影响历史对比。

## 6. 状态机

**涉及状态**：报告处理为**一次性单向流水线**（无回退，不落持久状态字段）：

| 阶段 | 动作 | 下一阶段 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| UPLOADED | 校验通过 | PARSING | ✓ | consent/白名单 fail-closed |
| PARSING | 双通道解析 | REFERENCED / FAILED | ✓ | 解析不出→提示手动输入 |
| REFERENCED | 参考范围比对 | PERSISTED | ✓ | 标 HIGH/NORMAL/LOW |
| PERSISTED | 落库+摘要 | INTERPRETED | ✓ | 同事务 |
| INTERPRETED | LLM 解读 | DONE | ✓ | 无 Key→占位 |

## 7. 异常规格

| 编号 | 异常场景 | 触发条件 | 期望行为 | HTTP | message | 降级/兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| E-1 | 未授权上传 | `consent=false` | 拒绝上传 | 400 | 需先同意报告存储与解读协议 | — | `（ref: 合规红线）` |
| E-2 | 非法文件 | 空文件 / 超 10MB / 非白名单扩展名 / 内容魔数不识别 / HEIC 但环境无转码能力 | 拒绝，错误消息区分原因 | 400/413 | 空→「文件为空」；超限→「超过 10MB」；扩展名→「不支持的文件格式 .xx」；内容→「无法识别文件内容格式」；HEIC→「iPhone 默认拍照格式，当前环境无法转码，请转 JPEG/PNG 或启用 OSS 自动转码」 | — | `（ref: R-4/R-1）` |
| E-3 | 解析失败 | 全部页零指标且零所见（OCR/Vision 无法抽取） | 拒绝：明确提示重传，**不虚构数据、不落库** | 400 | 未能自动识别报告内容，请上传更清晰的图片或带文本层的 PDF 报告 | — | `（ref: R-3a）` |
| E-4 | ES 不可用 | 参考范围交叉核验失败 | 用报告自带范围判定（自带优先，B-8），标注"参考范围未经校验" | 200 | — | 本地规则降级 | `（ref: T-2）` |
| E-5 | LLM 不可用 | 无 API Key | 占位回复 + 结构化数据仍返回 | 200 | 框架占位 | `AgentLlmSupport` | `（ref: T-2）` |
| E-6 | 越权访问 | 访问他人报告/对比 | 拒绝 | 400 | 无权访问该报告 | — | `（ref: R-6）` |
| E-7 | 数值解析歧义 | 指标值含非数字字符无法判定 | 标 UNKNOWN，不猜 | 200 | 该指标需人工核对 | UNKNOWN | `（ref: R-3 fail-closed）` |
| E-8 | LLM 全供应商失败 | 网关路由所有供应商均失败/熔断开闸 | 抛出最后异常，由各调用点既有兜底接住（关键词规则/确定性解析/占位回复），不 500 | 200/降级 | — | `RoutedChatModel` 故障转移 | `（ref: ARCH-2026-08-29·8）` |

## 8. 边界与校验规则

| 编号 | 约束 | 值/语义 | fail-closed 说明 | 追溯 |
| --- | --- | --- | --- | --- |
| B-1 | 文件大小 | ≤ 10MB | 超限即拒，不读内容 | `（ref: R-1）` |
| B-2 | 文件格式 | 扩展名白名单（pdf/png/jpg/jpeg/webp/heic/heif）+ **内容魔数校验**（`FormatSniffer`：按字节识别 pdf/jpeg/png/webp/heic）；HEIC/HEIF 具备转码能力（OSS x-oss-process）时自动转 JPEG 供解析，否则拒绝提示；未知内容拒绝 | 白名单外 / 魔数不匹配 / HEIC 无法转码均拒，错误消息区分原因（E-2） | `（ref: R-1）` |
| B-3 | 授权 | consent 默认 false | 默认拒绝，显式同意才放行 | `（ref: 合规红线）` |
| B-4 | 指标值判定 | 数值+参考范围可解析→HIGH/NORMAL/LOW；否则 UNKNOWN | 不猜默认 | `（ref: E-7）` |
| B-5 | 报告日期 | 缺省=上传日 | 显式默认 | `（ref: R-5）` |
| B-6 | 数据最小化 | 解读 prompt 仅含指标+参考范围+异常+脱敏后检查所见，不含姓名/证件号/医院 | 剥离 PII | `（ref: 合规红线）` |
| B-7 | 危急值规则 | 纯 Java 确定性规则（阈值+所见关键词），触发即强制注入响应与 prompt；规则文件缺失启动失败（fail-fast，不静默失去安全兜底） | 规则引擎独立于 LLM | `（ref: ARCH-2026-08-29·4）` |
| B-8 | 参考范围优先级 | 报告自带可用范围 > ES 权威范围（仅兜底+交叉核验） | 机构方法学差异下以报告自带为准 | `（ref: ARCH-2026-08-29·3）` |
| B-9 | 指标同义词归一 | 聚合后归一落库；对比入参归一查询；未命中保留原名 | fail-open 不猜 | `（ref: ARCH-2026-08-29·5）` |
| B-10 | 事务边界 | 落库段 TransactionTemplate 收窄，LLM 解读与 ES 检索在事务外 | 慢调用不占用数据库连接 | `（ref: ARCH-2026-08-29）` |

## 9. 事务与补偿边界

| 编号 | 跨服务动作 | 主路径 | 失败补偿 | 兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| T-1 | 解析→落库 | report_record + report_indicator + health_profile **同事务**（本库，TransactionTemplate 收窄） | 落库失败→整事务回滚，不写半份 | 记 ERROR 日志含 userId/fileName | `（ref: R-5）` |
| T-2 | ES 参考范围交叉核验 | 外部只读 | 失败→自带范围判定，**不阻断落库** | 标注"参考范围未经校验" | `（ref: T-2/T-5）` |
| T-3 | LLM 解读 | 外部生成（**事务外**执行，B-10） | 失败→占位回复 | 结构化数据已落库，不受影响 | `（ref: T-2）` |

> 补偿语义：外部依赖（ES/LLM）best-effort 降级，**不抛出掩盖主因**（T-5）；本库事务失败原样回滚。

## 10. 可测试验收

| 编号 | 验收（Given-When-Then） | 来源 | 状态 |
| --- | --- | --- | --- |
| T-1 | Given 授权 + 化验单文件；When 上传；Then 返回结构化指标列表 + HIGH/NORMAL/LOW 标记 + 免责声明 | `PM-DESIGN·1/合规` | ☐ |
| T-2 | Given consent=false；When 上传；Then 400「需先授权」 | `合规红线` | ☐ |
| T-3 | Given 文件超 10MB 或非法格式；When 上传；Then 400/413 拒绝，错误消息区分原因（超大小 / 不支持扩展名 / 内容无法识别） | `R-4/E-2` | ☐ |
| T-4 | Given 同 userId 两次同指标报告（不同日期）；When `GET /compare`；Then 返回多日期并列 + 差异解读建议 | `PM-DESIGN·6` | ☐ |
| T-5 | Given 访问他人 userId；When 对比；Then 400「无权访问」 | `R-6` | ☐ |
| T-6 | Given ES 不可用；When 上传；Then 200 + 本地规则标记 + "参考范围未经校验"标注 | `T-2` | ☐ |
| T-7 | Given LLM 无 Key；When 上传；Then 结构化数据返回 + 占位解读 | `E-5` | ☐ |
| T-8 | Given 上传两次不同日期同指标；When 再次对话；Then 健康摘要注入 Agent 上下文（长期记忆生效） | `PM-DESIGN·5` | ☐ |
| T-9 | Given 无文本层图片件；When 上传；Then 400「未能自动识别」+ 不落库 + 无虚构指标（R-3a/E-3） | `ARCH·NG` | ☐ |
| T-10 | Given 血糖危急值报告（GLU 35.2）；When 上传；Then 响应含危急告警 + CONSULTATION handoff（B-7） | `ARCH·4` | ☐ |
| T-11 | Given 多页报告（化验+超声）；When 上传；Then 检查所见按页型归位 + 解析轨迹完整（R-3） | `ARCH·1/2` | ☐ |
| T-12 | Given 异步上传；When 轮询任务；Then QUEUED/RUNNING→DONE + 进度事件（PROFILE…DONE）+ 完整结果（R-1） | `ARCH·7` | ☐ |
| T-13 | Given 金标样例集；When 跑确定性管线；Then 字段级 recall/precision ≥ 0.90（不达标阻断构建）（B-防线4） | `ARCH·6` | ☐ |
| T-14 | Given 设 DEEPSEEK_API_KEY；When 跑 LLM 评测；Then recall/precision ≥ 0.80；未设则跳过 | `ARCH·6` | ☐ |
| T-15 | Given 主供应商持续失败；When 网关调用；Then 熔断开闸快速故障转移至备用供应商 + 计量记录（E-8） | `ARCH·8` | ☐ |
| T-16 | Given 伪装成 .jpg 的 HEIC 头文件；When 上传；Then 400 提示「HEIC，请转 JPEG/PNG」+ 不落库（E-2/B-2） | `R-1` | ☐ |
| T-17 | Given webp 图片件；When 上传；Then 魔数识别为 webp → 归一为 JPEG/PNG 进入解析，不落裸字节（B-2） | `R-3` | ☐ |
| T-18 | Given 扩展名 .jpg 但内容为文本；When 上传；Then 400「无法识别文件内容格式」，魔数校验拦截（E-2/B-2） | `R-1` | ☐ |
| T-19 | Given OSS 启用 + 真实 HEIC 图片；When 上传；Then 自动转 JPEG 完成解析，原文留档原始 HEIC 字节（B-2/R-3；转码依赖真实 OSS，人工/集成验收） | `R-1` | ☐ |

## 11. 测试映射

| 测试层次 | 用例 | 覆盖 Spec 项 | 受影响既有用例 | 追溯 |
| --- | --- | --- | --- | --- |
| 单元 | `ReportParserTest`（plan-execute 管线+进度监听+E-3 不虚构） | R-3 / R-3a / T-9 / T-11 | — | `（ref: §3.5）` |
| 单元 | `LlmPageClassifierTest`（8 页型 LLM/关键词降级） | R-3 | — | 同上 |
| 单元 | `LlmReportPlannerTest`（LLM 规划/白名单校验/确定性兜底） | R-3 | — | 同上 |
| 单元 | `PageTypeRegistryTest`（页型注册表加载完备） | R-3 | — | 同上 |
| 单元 | `CriticalValueEvaluatorTest`（阈值/别名/所见关键词/不误触/排序） | B-7 / T-10 | — | 同上 |
| 单元 | `IndicatorNormalizerTest`（别名/单位尾缀/全半角/未命中） | B-9 | — | 同上 |
| 单元 | `RoutedChatModelTest`（故障转移/熔断开闸/限流拒绝/计量） | E-8 / T-15 | — | 同上 |
| 单元 | `ReportReferenceServiceTest`（自带优先/ES 兜底 + ES 降级） | R-3 / B-8 / E-4 / T-6 | — | 同上 |
| 单元 | `ReportUploadServiceTest`（事务落库 + 摘要 + E-3 + 危急值 + 格式门禁：伪装 jpg / HEIC / 魔数不匹配） | R-5 / T-1 / T-7 / T-9 / T-10 / T-16 / T-18 | — | 同上 |
| 单元 | `ReportTaskServiceTest`（异步任务受理/终态/进度事件） | R-1 / T-12 | — | 同上 |
| 单元 | `FormatSnifferTest`（魔数识别 pdf/jpeg/png/webp/heic，avif/未知→null，null 安全） | B-2 / E-2 / T-17 | — | 同上 |
| 单元 | `ReportComplianceServiceTest`（prepareContent：HEIC 转码→JPEG / 无转码拒绝 / 未知拒绝 / JPEG 透传，stub 存储） | B-2 / E-2 / T-16 / T-19 | — | 同上 |
| 单元 | `ExifToolTest`（方向矫正/降采样/格式归一/无法解码 fail-closed/webp reader 注册） | R-3 / B-2 / T-17 | — | 同上 |
| 门禁 | `ReportEvalTest`（确定性评测，recall/precision ≥ 0.90 阻断构建） | T-13 | — | `（ref: ARCH·6）` |
| 门禁 | `LlmReportEvalTest`（LLM 评测，有 Key 启用，≥ 0.80） | T-14 | — | 同上 |
| 单元 | `ReportCompareServiceTest`（多日期并列 + 越权，并入 ReportUploadServiceTest） | R-6 / T-4 / T-5 | — | 同上 |
| 单元 | `ReportKnowledgeServiceTest`（知识库降级 + 指标知识解析） | R-3 / E-4 / T-2 | — | 同上 |
| 接口 | `ReportController` upload/compare（MockMvc） | §4 / T-2/T-3 | — | 同上 |
| 回归 | `AgentOrchestratorTest`（既有） | 编排不受影响 | AgentOrchestratorTest | `（ref: M-7）` |

## 12. 待澄清项（OPEN）

| 编号 | 来源位置 | 问题 | 澄清需求 | 状态 |
| --- | --- | --- | --- | --- |
| OPEN-1 | 上传前 | 授权协议具体文案 | 产品提供「报告存储与解读」同意书文本 | ☐ 待澄清 |
| OPEN-2 | `ReportTypeDetector` | 报告类型枚举完整性（除化验单/体检单外还有哪些优先） | 产品确认类型清单 | ☐ 待澄清 |
| OPEN-3 | `ReportReferenceService` | 指标归一化规则完整清单（同义指标名→标准名） | **v1.1 已落地首版**（`IndicatorNormalizer` + `indicator-aliases.json` 约 40 指标）；词典持续扩充由医学知识方跟进 | ✅ 首版落地 |
| OPEN-4 | `shopdb_agent` | 历史报告保留期限 | 合规/产品确认（默认先不设自动删除） | ☐ 待澄清 |
| OPEN-5 | `ReportCompareService` | 对比判定的显著性阈值（如 WBC 变化多少算显著） | 医学确认，本期先做"并列展示+提示咨询"不判显著 | ☐ 待澄清 |

> 纪律：OPEN 未清零前 Spec 不进入 REVIEWED。本期实现以 OPEN-5 的可执行简化（并列+咨询提示）先行，其余影响文案/枚举的作为待产品澄清。

## 13. 研发方案（Implementation Plan）

**知识层扩展（先于代码）**：
- `tech-rules.md` 新增 `T-7 文件上传与解析引擎`：multipart 白名单/大小；OCR/VLM 一律接口抽象 + 降级（无真实 Key → Mock/占位），不绑定具体引擎；
- `glossary.md` 新增 §0 所列术语（登记后升 ✅）；
- `module-contracts.md` M-7 更新：+DB `shopdb_agent`、+端点 `/api/agents/report/upload`、`/api/agents/report/{userId}/compare`、+关键类 report/*；
- `agents/agent-service.md` 扩展：定位加 DB；新增「报告落点指南」（解析/对比/记忆/合规）；DO/DON'T 加「不直接暴露用户 PII」「解析/ES/LLM 失败降级不 500」。

**分文件改造清单（v1.1 增量，ref: ARCH-2026-08-29）**：

| # | 文件 | 改动（新增/修改/删除 + 一句话） | 引用规则 | 模块约束 |
| --- | --- | --- | --- | --- |
| 1 | `report/PageType` `PageContent` `FileProfile` `PageChannel` | 新增：页级模型（8 页型/页内容/画像/通道） | C-1 | `agents/agent-service.md` |
| 2 | `report/PageTypeRegistry` + `resources/report/pages/*.md`（9 个） | 新增：页型注册表 + prompt 模板（进 git 可评审） | T-7 | 同上 |
| 3 | `report/PageTypeKeywordDetector` | 新增：页型分类关键词兜底 | C-3 | 同上 |
| 4 | `report/LlmPageClassifier`（重写） | 修改：逐页 8 页型分类（原为文档级 3 类） | T-7 | 同上 |
| 5 | `report/TextLayerParser` | 新增：确定性文本解析（兜底通道，含表头/值形态过滤） | T-7 | 同上 |
| 6 | `report/LlmTextPageExtractor` | 新增：文本页真实 LLM 抽取（配方 prompt + 结构化输出） | T-7 | 同上 |
| 7 | `report/VisionAnalyzer`（接口改页级）+ `MockVisionAnalyzer` | 修改：图片页明确未识别，删除虚构样例 | T-7 / E-3 | 同上 |
| 8 | `report/ReportPlanner` + `LlmReportPlanner` | 新增：PLAN 步骤（LLM 规划 + 确定性兜底 + 白名单校验） | T-7 | 同上 |
| 9 | `report/PlanExecutor` + `PageAggregator` + `ReportPlan` + `PlanExecutionTrace` | 新增：EXECUTE 执行器（页级虚拟线程并行）+ 聚合校验 + 审计轨迹 | C-1 | 同上 |
| 10 | `report/ReportParser`（重写） | 修改：plan-execute 门面（保留 resolveReportDate） | C-1 | 同上 |
| 11 | `report/ReportReferenceService`（修改） | 修改：自带范围优先 + ES 兜底交叉核验（B-8） | T-2 | 同上 |
| 12 | `report/CriticalValueRule` `CriticalAlert` `CriticalValueEvaluator` + `critical-rules.json` | 新增：危急值规则引擎（防线3/B-7） | C-3 | 同上 |
| 13 | `report/IndicatorNormalizer` + `indicator-aliases.json` | 新增：指标同义词归一（B-9/OPEN-3） | C-1 | 同上 |
| 14 | `config/LlmProviderProperties` `RoutedChatModel` `LlmRoutingConfig` `LlmUsageMeter` | 新增：LLM 网关（路由+限流+熔断+计量）+ pom 依赖（resilience4j/actuator） | T-2/E-8 | 同上 |
| 15 | `config/ReportTaskProperties` + `service/ReportTaskService` | 新增：异步任务（有界并发 + SSE + TTL 清理） | C-1 | 同上 |
| 16 | `service/ReportUploadService`（重构） | 修改：TransactionTemplate 收窄事务 + 危急值接入 + findings 脱敏 + 进度监听 + E-3 拒绝 | C-1/T-5/B-10 | 同上 |
| 17 | `dto/ReportUploadResponse` + `service/ReportComplianceService` | 修改：+criticalAlerts/findings/parseTrace；buildLlmInput 支持所见 | C-5/B-6 | 同上 |
| 18 | `controller/ReportController` + `controller/LlmUsageController` | 修改/新增：+upload/async、task 轮询/SSE 端点、llm-usage 端点 | C-1/T-3 | 同上 |
| 19 | `service/ReportCompareService`（修改） | 修改：对比入参同义词归一 | B-9 | 同上 |
| 20 | `agent/specialist/ReportAgent`（修改） | 修改：prompt 强化（判定由系统完成/危急值原样传达/所见不下诊断） | T-2 | 同上 |
| 21 | `application.properties`（主/测试） | 修改：+规划/抽取模型、任务、网关、计量配置；测试补 base-url 对齐 | T-4 | 同上 |
| 22 | 测试：改写 `ReportParserTest` `LlmPageClassifierTest`；新增 `PageTypeRegistryTest` `LlmReportPlannerTest` `IndicatorNormalizerTest` `CriticalValueEvaluatorTest` `RoutedChatModelTest` `ReportTaskServiceTest` `ReportEvalTest` `LlmReportEvalTest` + `report/eval/*.json` 金标集 | T-13/T-14 门禁 | C-1 | 同上 |
| 23 | `config/VisionProperties` `VisionConfig` + `report/GlmVisionAnalyzer` | v1.2 新增：视觉通道真实接入（GLM-4.6V，一次调用页型自分类+抽取；免费档信号量并发额度，溢出/失败升级付费档 glm-4.6v）；`MockVisionAnalyzer` 改为装配降级实现 | T-7 | 同上 |
| 24 | `config/RoutedChatModel`（升级）+ `LlmProviderProperties`（+models 清单） | v1.2 修改：**模型亲和路由**——请求模型名命中供应商托管清单则优先路由，其余候选作故障转移 | T-2/E-8 | 同上 |
| 25 | `agent/AbstractSubAgent`（升级） | v1.2 修改：解读失败升级重试（`agent.llm.escalation-model`，deepseek-chat 失败→deepseek-reasoner 再试一次，留空关闭） | T-2 | 同上 |
| 26 | 评测：`SafetyRedTeamEvalTest` `RoutingEvalTest`（CI 门禁）+ `LlmAgentBehaviorEvalTest` `RetrievalEvalTest` `VisionEvalTest`（env-gated）+ `agent-eval/*.json` 资产 + `docs/17-agent-evaluation.md` | v1.2 新增：四层评测框架（安全红队/路由金标/Agent 金标 judge/视觉 recall@0.90 门禁切档） | C-1/T-13 | 同上 |
| 27 | `graph/*`（MedicalKnowledgeSeeds/GraphQueryPort/Neo4jGraphQueryPort/RelationLookupService/GraphKnowledgeService/GraphSeedRunner）+ `KnowledgeSeedRunner` 扩灌 knowledge_relation | v1.3 新增：**知识关联演进**——v1 ES `knowledge_relation` 关系查表（逐指标并集）→ v2 Neo4j 图推理（多指标命中排序 + 科室/症状多跳），同源种子双载体，图不可用降级查表（T-2）；`GraphEvalTest`（env-gated）以 accuracy@1 证明图优于表；未部署 Neo4j 不阻断启动 | T-2/C-1 | 同上 |
| 28 | `service/HealthFactCard` + `HealthProfile.fact_card` + `HealthMemoryService` 重写 | v1.4 新增：**L1 事实卡**——异常指标合并保留 firstSeen、红线类事实（过敏/慢病）不受容量裁剪、摘要由卡派生（修复截断丢旧事实缺陷） | C-1 | 同上 |
| 29 | `ReportRecord`（+file_hash/file_key/storage_type/response_snapshot）+ 上传幂等 | v1.4 新增：**文件 hash 幂等**——同用户同文件回放响应快照，防重复解析/落库/计费 | C-3 | 同上 |
| 30 | `report/PromptGuard` + `buildLlmInput` 围栏化 + ReportAgent 数据隔离条款 + 输出侧检测 | v1.4 新增：**Prompt 注入隔离**——文件派生内容围栏包裹+伪造序列剥离+输出泄露检测降级（内容即指令风险） | C-5/B-6 | 同上 |
| 31 | `report/UnitConverter` + PageAggregator 接入；`LlmTextPageExtractor` 数值双次交叉校验 | v1.4 新增：**数值防线**——mg/dL↔mmol/L 等单位归一（值与参考范围联动换算）；抽取后二次核对按原文纠正（channel 可审计） | B-4 | 同上 |
| 32 | `storage/*`（ReportFileStorage/LocalReportFileStorage/OssReportFileStorage）+ 文件回看/删除端点 | v1.4 新增：**原文留档**——OSS 生产实现（SSE 加密+预签名+桶生命周期）/本地降级；GET file 回看、DELETE 报告（被遗忘权） | C-1 | 同上 |
| 33 | `config/ReportMetrics` + `AlertPublisher`（日志/Webhook）+ 熔断状态告警 + micrometer-tracing + 虚拟线程 MDC 传播 | v1.4 新增：**观测三支柱**——解析成功率/页型分布/通道分布/降级率业务指标（/actuator/metrics）、traceId 进日志贯穿页级并行、熔断开闸告警（Webhook 可选） | T-6 | 同上 |
| 34 | `report/FormatSniffer`（新增）+ `ReportComplianceService.prepareContent` + `storage/ReportFileStorage.transcodeHeic`（Oss 经 x-oss-process 转 JPEG / Local 返回空）+ `ExifTool.normalize` 重写 + `ReportParser.buildProfile` 魔数路由 + pom +`imageio-webp` | v1.5 新增：**图片格式魔数门禁与归一**——扩展名不再可信，按字节识别 pdf/jpeg/png/webp/heic；HEIC/HEIF 存储具备转码能力（OSS ImageMagick）时自动转 JPEG 供解析（原文仍留档原始字节），否则定向拒绝提示（纯 Java 解 HEIC 无好方案）；webp 经 imageio-webp（只读）解码归一为 JPEG/PNG；任何无法解码内容 fail-closed 拒绝，杜绝未知格式裸字节静默送达 VLM | C-3/B-2 | 同上 |

**原 v1.0 清单中的 `OcrEngine`/`MockOcrEngine` 未落地**：实现演进为「PDF 文本层（PdfBoxTool）+ 文本 LLM 抽取（LlmTextPageExtractor）+ 视觉通道接口（VisionAnalyzer）」三通道结构，OCR 通道被文本层直抽替代（更准且零成本），spec 据实修正。

**分层改动点**：api（JSON）✓ · service（编排/事务）✓ · repository（SQL）✓ · model/DTO ✓ · client ✗ · web ✗

**边界说明**：order BFF 上传代理属订单领域（本 Spec 不改），本期 agent-service 直接暴露端点供调试，标注为**已知偏离 BFF 唯一入口**，后置迭代收敛。

**受影响既有用例**：`AgentOrchestratorTest`（agent-service，回归保持绿）。

**验证方式**：起 MySQL（init.sql）+ 可选 ES → `curl -F "file=@化验单.png" -H "X-Trace-Id: <id>" http://localhost:8087/api/agents/report/upload` 走通上传→解析→存库→摘要→解读；再传第二次同指标报告 → `GET /api/agents/report/{userId}/compare?indicator=WBC` 见多日期并列；带 traceId 串日志验证（T-6）；跑 agent-service `clean test` 全绿。

---

## 转化自检（spec-quality-gates.md）

- [x] 双向可追溯：每条 Spec 项带 `PM-DESIGN-2026-08-24` / R-x / T-x 引用
- [x] 可测试性：§10 全部可转断言
- [x] 无歧义：无双关词，数值/分支/异常行为明确
- [x] 覆盖完备：6 项决策 + 合规红线全覆盖
- [x] 单一事实来源：与设计对话、ES 索引、agent 现状无矛盾
- [x] 自包含：文件+章节+验证方式齐全
- [x] 术语对齐：§0 全部登记 glossary（见 §13 回写）
- [x] 改造定位可执行：§3.5 每个改动点定位到 file:line / 新包
- [x] 规则引用齐全：T-2/T-3/T-4/T-6、C-1/C-3/C-5 + 新扩展 T-7
