# Report Agent 全链路框架梳理（v1.0）

> 配图：`report-agent-arch-1.0.svg`（archify drafted-sheet 风格，7 泳道 + 模型清单 + 18 处降级地图）。
> 本文是图的文字版，作为后续逐步完善的底稿。代码位置均在 `agent-service` 模块内。

## 0. 一句话总览

Report Agent = **双入口**（文件上传 / 会话对话）→ **fail-closed 合规门禁** → **plan-execute 解析管线**（LLM 规划/分类/抽取 + 确定性兜底）→ **三道确定性防线与知识增强** → **事务收窄落库** → **best-effort LLM 解读**（多级降级）→ **响应组装交付**。
设计基调：**规则先于 LLM、宁可未识别绝不虚构、单点失败不扩散、一切降级可审计**。

## 1. 输入层（两条入口）

| 入口 | 端点 | 说明 |
|---|---|---|
| 同步上传 | `POST /api/agents/report/upload` | multipart（userId/file/consent），同步返回完整解读 |
| 异步任务 | `POST /api/agents/report/upload/async` | 校验同步完成（非法输入即时 400）→ 202 + taskId；虚拟线程 + Semaphore（并发 8）执行；`GET /task/{id}` 轮询、`/task/{id}/events` SSE 进度；终态 TTL 30min 惰性清理 |
| 会话对话 | `POST /api/agents/chat` | 上传后追问/历史对比，走同一编排链路（Supervisor 定向委派 / Handoff 续聊） |

## 2. 合规门禁（输入侧，fail-closed）

- **E-1 授权**：`consent=false` 直接拒绝（默认拒绝）。
- **E-2 文件校验**：白名单 pdf/png/jpg/jpeg/webp/heic/heif + ≤10MB + 非空。
- **内容魔数门禁**：`FormatSniffer` 按字节识别，不信任扩展名/Content-Type；HEIC → OSS 转码 JPEG（不可转码定向拒绝）。
- **SHA-256 幂等**：同用户同文件命中 → 直接回放响应快照（防重复解析/落库/计费）。
- **数据最小化与注入隔离（PromptGuard，贯穿全链路）**：
  - PII 脱敏：身份证掩码、剔除含姓名/证件/医院/电话的行；
  - 三层防线：① 文件派生内容围入 `<uploaded_report_data>` 数据围栏 ② scrub 剥离伪角色/围栏伪造 ③ 输出侧泄露检测 → 拦截降级 + 告警。

## 3. 解析管线（plan-execute，页级虚拟线程并行）

1. **PROFILE 文件画像（确定性工具）**：魔数路由 —— PDF → `PdfBoxTool` 逐页文本层；图片 → `ExifTool` EXIF 方向矫正 + 降采样 2048px + 剥 GPS + 归一 JPEG。日期兜底链：显式日期 > EXIF 拍摄日 > 当天。
2. **PLAN 规划**：`LlmReportPlanner`（deepseek-v4-flash，可配 `agent.report.planner.model`）按每页 400 字预览决定通道 TEXT_LLM/VISION + 预期页型，输出白名单校验。**降级**：失败/无 Key/输出全不合法 → 确定性计划。
3. **CLASSIFY 页型分类（页级并行）**：`LlmPageClassifier`（deepseek-v4-flash，可配 classifier.model）判 8 类页型。**降级**：图片页/失败/不可解析 → `PageTypeKeywordDetector` 关键词规则 → OTHER；页异常兜底 OTHER 不阻断。
4. **EXTRACT 双通道抽取**：
   - TEXT_LLM：`LlmTextPageExtractor`（可配 extractor.model）按页型配方 prompt 结构化抽取；缺名缺值丢弃不猜；DISCLAIMER 页确定性跳过；**数值双次交叉校验**（`agent.report.verify.enabled`）第二次 LLM 对照原文纠正抄错/幻觉，channel 标记 llm-verified / llm-verified-corrected。**降级** → `TextLayerParser` 确定性行文法（deterministic-fallback）。
   - VISION：`GlmVisionAnalyzer`（glm-4.6v-flash 免费档，Semaphore 并发 2）一次调用完成页型自判+抽取，数值逐字照抄；溢出/失败 → 升级付费档 glm-4.6v 重试；未配 Key → `MockVisionAnalyzer` 明确未识别；单页异常 → 空抽取不阻断整批。
5. **AGGREGATE 聚合**：`PageAggregator` 归并统一 schema（ReportDoc）；`IndicatorNormalizer` 同义词归一 + LOINC 编码（未命中保留原名）；`UnitConverter` 单位归一（值与参考范围联动换算）；重名保留首次、缺值丢弃记 note；source 标记 llm > deterministic > vision。
6. **VALIDATE 校验**：备注与计划偏差进 `PlanExecutionTrace`（全程可审计）。
7. **E-3 零内容拒绝**：零指标且零所见 → 400「请上传更清晰的图片」，不虚构、不落库。

## 4. 确定性防线与知识增强（规则先于 LLM）

- **防线① 参考范围比对**：`ReportReferenceService` 纯 Java `RangeParser` 判 HIGH/LOW/NORMAL/UNKNOWN（解析不出→UNKNOWN 不猜）；报告自带范围优先，ES lab_exam 兜底；口径不一致记交叉核验日志、以自带为准。**降级**：ES 不可达 → `referenceVerified=false`（E-4，注入 prompt 提示）。
- **防线③ 危急值规则引擎**：`CriticalValueEvaluator` + `critical-rules.json`（指标阈值 GT/GE/LT/LE + 所见关键词）；告警强制注入解读 prompt、必须原样传达并建议立即就医；词典缺失 → **启动失败**（唯一 fail-fast，安全兜底绝不静默）。
- **知识增强（全部可降级为空，不阻断）**：
  - `GraphKnowledgeService`：Neo4j 异常指标→疾病三路证据包（支持/反向/待补充）+ 纯 Java 规则三态判定（SATISFIED/INSUFFICIENT/EXCLUDED）；**降级链**：图失败 → ES knowledge_relation 查表 → 全降级空串；连接超时 2s 快速失败 + 探活缓存 5s。
  - `ReportKnowledgeService`：ES report_template 模板 + lab_exam 异常含义/建议；不可用/未命中 → 空。
  - `HealthMemoryService`：异常指标/危急值合并进 L1 健康事实卡（幂等 upsert），注入后续对话。

## 5. 持久化（事务收窄 B-10）

- **原文留档**：OSS（生产，预签名回看）/ 本地（演示）；**降级**：失败不留档（storageType=none），解读不受影响。
- **三表同事务 T-1**：`TransactionTemplate` 收窄 —— report_record + report_indicator_record；慢速 LLM 移出事务（T-3）。
- **响应快照落库**：幂等回放与原报告回看依据；独立短事务，失败不回滚主数据。

## 6. LLM 解读（best-effort，多级降级）

1. `buildLlmInput`：指标名/值 scrub、所见包围栏；追加 referenceVerified 提示、危急值强制注入、图谱证据、知识库片段。
2. 编排：`AgentOrchestrator` → Safety 前置（fail-closed）→ Supervisor 定向委派 `target=REPORT`。**降级**：无 LLM → `ManagerRouter` 关键词路由；Manager 空返回 → 确定性兜底文案。
3. `ReportAgent.handle`（继承 `AbstractSubAgent`）：
   - system prompt 九原则（客观中立/区分事实-原因-建议/免责声明/隐私/需人工核对标注/不推翻系统判定/危急值硬性传达/影像不下诊断/数据隔离声明）；
   - + RAG `KnowledgeRetrievalService`（BM25+向量 RRF；embedding 无 Key → BM25-only）；
   - + 长期记忆（健康档案派生摘要 + persona 卡）；
   - + 工具 `ReportTools`（权限=本人数据）：`queryReportCompare` 同指标历史对比、`getHealthProfile` 健康档案、`queryIndicatorTrend` 跨报告趋势。
4. **LLM 网关** `RoutedChatModel`（@Primary）：模型亲和路由 + 限流 30 次/10s（等待>5s 判失败转移）+ 熔断（10 次窗口失败率≥50% 开闸 30s）+ 跨供应商故障转移 + 成本计量；429/5xx 由 Spring AI 供应商内退避重试。
5. **升级重试**：主档 deepseek-v4-flash 失败 → `deepseek-v4-pro` 重试 ×1（`agent.llm.escalation-model`）。仍失败 → 异常上抛 → 调用点降级。
6. **输出侧防护**：`PromptGuard.looksCompromised` 疑似注入泄露 → 替换降级回复 + `degrade("prompt-guard")` 告警。

## 7. 输出层

- 解读失败 → 占位文案「报告解读服务暂时不可用…」（落库/快照已提交，不受影响）。
- `ReportUploadResponse`：reportId、检查名/日期、source、referenceVerified、指标视图（偏高/偏低/正常/无法判定）、脱敏所见、summary、interpretation、免责声明（必带）、handoffs（异常→问诊）、criticalViews、执行轨迹 trace。
- 进度事件全程 SSE：PROFILE→PLAN→CLASSIFY→EXTRACT→ALIGN→PERSIST→INTERPRET→DONE；SSE 断开轮询兜底。

## 8. 模型清单

| 模型 | 角色 | 备注 |
|---|---|---|
| deepseek-v4-flash | 主力 LLM：对话解读/规划/页分类/文本抽取，T=0.3 | api.deepseek.com（OpenAI 兼容） |
| deepseek-v4-pro | 主档失败升级重试 ×1 | `agent.llm.escalation-model`，留空关闭 |
| glm-4.6v-flash | 视觉通道主力（免费档，并发 2） | open.bigmodel.cn |
| glm-4.6v | 视觉付费档：并发溢出/失败升级 | ¥1/¥3 每百万 token |
| text-embedding-v3 | DashScope 混合检索向量路 | 无 Key → BM25-only |

> classifier/planner/extractor.model 可独立换档，留空沿用主力 chat 模型。
> 主模型 / 升级（更强）模型 / 视觉模型来自 `.claude/llm-switch.json`（T-8 单一事实源，由 `sync-llm-model` hook 渲染）。

## 9. 降级地图（18 处）

| # | 环节 | 失败场景 → 降级行为 |
|---|---|---|
| 01 | PLAN | LLM 失败/无 Key/输出全不合法 → 确定性计划（有文本走 TEXT_LLM，无文本走 VISION） |
| 02 | CLASSIFY | 失败/不可解析/图片页 → 关键词规则 → OTHER（不阻断） |
| 03 | EXTRACT·文本 | LLM 失败/无 Key → TextLayerParser 确定性行文法（channel 可审计） |
| 04 | EXTRACT·视觉 | 溢出→直接付费档；失败→付费档重试 ×1；仍失败→「未识别」空抽取，绝不虚构 |
| 05 | 页级隔离 | 单页异常→空抽取不阻断整批；DISCLAIMER 页确定性跳过 |
| 06 | 零内容 | 无视觉 Key → Mock 明确未识别；全零内容 → E-3 400 拒绝不落库 |
| 07 | 参考范围 | ES 不可达 → 仅用自带范围，referenceVerified=false（E-4） |
| 08 | 危急值 | 词典缺失 → 启动失败（安全兜底绝不静默，唯一 fail-fast） |
| 09 | 知识图谱 | Neo4j 不可用/推理失败（2s 快速失败+探活缓存）→ ES 关系查表 → 空串 |
| 10 | 知识库 | ES 不可用/未命中 → 片段为空，不阻断解读与落库 |
| 11 | LLM 网关 | 429/5xx 供应商内退避 → 限流/熔断 → 跨供应商故障转移 → 全失败上抛 |
| 12 | Agent 调用 | 主档失败 → reasoner 升级 ×1 → 仍失败 → 占位文案（落库不受影响） |
| 13 | 输出防护 | 疑似注入泄露 → 拦截替换降级回复 + degrade 告警 |
| 14 | 无 Key 兜底 | stubReply 占位（框架可联调）；Manager 无 LLM → 关键词路由/兜底文案 |
| 15 | 原文留档 | 存储失败 → 不留档继续（storageType=none） |
| 16 | 响应快照 | 快照落库失败 → 不影响主数据，退化为下次重新解析 |
| 17 | 进度交付 | SSE 断开 → 轮询兜底；任务终态 TTL 30min 惰性清理 |
| 18 | 幂等 | 重复上传 → SHA-256 命中 → 回放快照（防重复解析/落库/计费） |

## 10. 待完善（后续迭代方向）

- 页级分类的视觉通道（图片页当前走关键词兜底，待 VLM 分类接入）；
- 异步任务生产化（进程内队列 → MQ + Redis 任务表）；
- 参考范围显著性判定、跨报告趋势的医学阈值确认；
- 备用 LLM 供应商（zhipu 等）正式挂载与故障转移演练。
