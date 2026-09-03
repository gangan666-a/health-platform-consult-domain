# agent-service 模块约束（多 Agent 编排 + RAG · 项目主体）

## 1. 定位

**端口 8087 · DB `shopdb_agent`（报告记录/指标/健康档案，2026-08-24 新增；会话仍纯内存）· REST**。职责：1 个 Manager + 6 个 Sub Agent，Supervisor 与 Handoff 混合编排；Safety 前置审查；ES 知识库 RAG 检索；报告解读（上传→双通道解析→比对→记忆→对比）。**本项目（问诊领域工程）的主体模块**；上游导购领域的用户可经 `POST /api/agents/chat` 汇入问诊链路（ShoppingGuide/Consultation Sub Agent 负责把购药意图转化为问诊线索，交给 doctor-assignment-service 排队）。

## 2. 包结构与关键类（入口 file:line，实测 2026-08-24）

| 包/类 | 关键方法 | 说明 |
| --- | --- | --- |
| `controller/AgentController.java`（`/api/agents`） | `POST /chat` :29 · `GET /registry` :34 | 对外入口 |
| `orchestration/AgentOrchestrator.java` | `chat:36` | 门面：Safety 前置 → Supervisor/Handoff |
| `orchestration/SupervisorOrchestrator.java` | Manager + `@Tool` 委派 | 新会话首条/交还 Manager 路由 |
| `orchestration/HandoffOrchestrator.java` | Sub Agent 续聊 + handoff | activeAgent != MANAGER 时 |
| `orchestration/ManagerRouter.java` | 关键词启发式路由 | 无 LLM Key 时兜底 |
| `orchestration/AgentSessionStore.java` / `AgentSession` | 内存会话（activeAgent/mode/handoff 历史） | 进程重启即失（已知限制） |
| `orchestration/tools/ManagerDelegateTools.java` / `HandoffTools.java` | 各 6 个 `@Tool` | 委派/交接工具 |
| `agent/SubAgentRegistry.java` | AgentType → SubAgent | 注册表 |
| `agent/specialist/*` | Consultation/MedicineQa/ShoppingGuide/Science/Report/Safety | 6 个专业 Sub Agent |
| `rag/KnowledgeRetrievalService.java` | ES 向量检索 | 失败降级空上下文 |
| `report/`（`ReportParser`/`OcrEngine`/`VisionAnalyzer`/`ReportTypeDetector`/`ReportReferenceService`） | 双通道解析 + ES lab_exam 参考范围比对 | 解析/ES 失败降级不 500 |
| `service/`（`ReportUploadService`/`ReportCompareService`/`HealthMemoryService`/`ReportComplianceService`） | 上传编排/同指标多日期对比/长期记忆/脱敏授权 | 报告解读子域服务层 |
| `model/`+`repository/`（`ReportRecord`/`ReportIndicatorRecord`/`HealthProfile`） | shopdb_agent JPA 持久化 | 报告/指标/健康档案 |
| `controller/ReportController.java` | `POST /report/upload` + `GET /report/{userId}/compare` | 报告上传/对比端点 |
| `config/AgentAiConfig` / `AgentChatClientFactory` | 按 AgentType 构建 system prompt 的 ChatClient | LLM 装配 |
| `config/AgentLlmSupport` | 判定 API Key 是否启用 LLM | 无 Key → 占位回复 |

## 3. 本模块必守规则

- T-2：DeepSeek 无 Key → 占位回复（`AgentLlmSupport` 判定）；ES 检索失败 → 降级空上下文，不阻断。
- T-3：错误体统一 `{"message"}`；`@RestControllerAdvice` 兜底。
- C-1：编排在 `AgentOrchestrator`，Controller 薄；Sub Agent 遵循 `AbstractSubAgent` 契约（统一 LLM/RAG/占位）。
- 会话态内存（`AgentSessionStore`）：无持久化是设计事实，新增跨重启需求须另立方案（Redis/MySQL）。

## 4. 新功能落点指南（扩展点）

| 新增类型 | 落点 |
| --- | --- |
| 新 Sub Agent | `agent/specialist/` 加类 + `SubAgentRegistry` 注册 + `AgentType`（mall-common）加枚举 |
| 新业务 Tool | `orchestration/tools/`（ManagerDelegateTools / HandoffTools）加 `@Tool` 方法 |
| 编排策略调整 | `AgentOrchestrator.chat`（Safety 前置 / Supervisor / Handoff 分支） |
| 路由意图识别 | `ManagerRouter`（关键词启发式 → LLM 意图识别） |
| RAG 增强 | `KnowledgeRetrievalService`（ES index/metadata 按 AgentType 分） |
| 报告解析/上传 | `report/` 新解析引擎（换真实 OCR/VLM 只换 Mock 实现）；`ReportController.upload` |
| 报告对比 | `ReportCompareService`（同指标多日期；接阈值判定前先读 OPEN-5） |
| 长期记忆 | `HealthMemoryService` + `HealthProfile`（摘要注入 ReportAgent 上下文） |
| 合规 | `ReportComplianceService`（授权/脱敏/数据最小化，改合规红线先过它） |
| 流式 SSE | `AgentController.chat` 改流式（当前为整包响应） |

## 5. DO / DON'T

- ✅ 新 Agent 走 `SubAgentRegistry` + `AbstractSubAgent` 契约；✅ 新工具走 `@Tool` + 权限语义清晰；✅ Safety 前置必须最先执行。
- ✅ 报告上传走 `consent` fail-closed（默认拒绝）；✅ 解析/ES/LLM 失败一律降级占位，不 500；✅ 入库/入 prompt 前脱敏、只发必要检查数据。
- ❌ 不要绕开 `AgentOrchestrator` 直接编排；❌ 不要依赖会话跨重启保留；❌ 不要在 Controller 写编排逻辑；❌ 不要让 ES/LLM 失败导致 500。
- ❌ 不要直接暴露/入 prompt 用户 PII（姓名/证件号/医院）；❌ 不要对解析不出的指标猜默认值（标 UNKNOWN）；❌ 不要越权访问他人报告/健康档案。

## 6. 影响面

- 测试：`agent-service/src/test/...`（编排/工具/降级用例）。
- 回归：全量 `clean test` 全绿；起 `start-agent.cmd` 后直接 `POST http://localhost:8087/api/agents/chat` 验证（无 Key → 占位回复，`activeAgent`/`orchestrationMode` 回填）。
- 依赖：配置 `agent.deepseek.api-key` 与 `spring.elasticsearch.uris`（留空可本地联调占位）。
