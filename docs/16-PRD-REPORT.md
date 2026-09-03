# 产品需求文档（PRD）— 报告解读 Agent（REPORT · 问诊 toC AI 健康助手）

| 项目名称 | AI 智能导购微服务商城 simple-mall（问诊 toC AI 健康助手） |
| --- | --- |
| 文档版本 | v1.0 |
| 文档状态 | 评审中（Draft for Review） |
| 编写人 | 产品经理（PM） |
| 编写日期 | 2026-08-25 |
| 关联文档 | `docs/15-agent-architecture.md`（多 Agent 架构）、`docs/spec/report-interpretation.spec.md`（研发 Spec）、`docs/07-PRD-IM.md`（M2 问诊 PRD）、`docs/01-PRD.md`（M1 商城 PRD） |
| 需求来源 | 0802-4 M3 迭代：问诊 toC AI 健康助手（多 Agent），本期聚焦**报告解读 Agent**；需求评审 = PM 设计评审对话（2026-08-24，ref `PM-DESIGN-2026-08-24`） |

> 说明：本文档为产品侧需求描述。功能行为以 agent-service 现状代码为基线；研发细化见 `docs/spec/report-interpretation.spec.md`（Spec，含 R-x 功能项与 C-x/T-x 规则追溯）。凡与代码不符之处以本文档为准，差异项在各 FR 的「现状差异」中标注。验收标准必须防 happy path。

---

## 1. 项目背景

M1 已交付「浏览商品 → 下单 → 模拟支付 → AI 智能导购」闭环（product/order/payment/recommend 四服务）。M2 已交付「药品品类 + IM 在线问诊（Netty WS）+ 处方购药」闭环（新增 consult-service）。M3 引入**多 Agent 健康助手**：`agent-service`（:8087）以 1 个 Manager + 6 个 Sub Agent（CONSULTATION 问诊 / MEDICINE_QA 问药 / SHOPPING_GUIDE 导购 / SCIENCE 科普 / REPORT 报告解读 / SAFETY 安全）通过自然语言为用户提供健康服务，Supervisor 与 Handoff 混合编排。

本期聚焦 **报告解读 Agent（REPORT）**：用户上传体检单/化验单（PDF/图片），系统完成「上传 → 双通道解析 → 参考范围比对 → 严谨解读 → 长期记忆 → 同指标历史对比」，在对话中回传结构化指标与解读；报告作为用户健康档案长期保留（支持历史回看与同指标多日期对比）。

现状要点（摘自代码）：

- **REPORT 已是 Sub Agent**：`ReportAgent` 现有 System Prompt 文本，但**缺少**「上传→解析→解读→记忆→对比」整条管线，无独立持久化。
- **agent-service 无数据库**：本期新增 `shopdb_agent`，建报告 / 指标明细 / 健康档案三表。
- **ES 已有知识索引**：`lab_exam` 等 10 个医学知识索引已建，`lab_exam` 内含 NESTED 指标参考范围（`indicators.ref_range`），供参考范围对齐。
- **AI 能力**：DeepSeek `deepseek-chat`（兼容 OpenAI，无视觉/无 Embedding），未配 Key 时 Agent 走占位回复。
- **隐私合规（产品红线）**：报告数据**不用于 LLM 训练**；prompt 只发必要报告内容、剥离 PII；授权 + 脱敏；回复客观中立、严谨、带免责声明。

---

## 2. 目标用户

| 用户类型 | 描述 | 主要诉求 |
| --- | --- | --- |
| **买家 / 患者（核心用户）** | 有检查报告（化验/体检）的普通用户 | 上传报告即得结构化指标 + 严谨解读；异常项能被识别并引导就医；多份同类报告可对比变化；历史报告可回看 |
| **研发 / 测试（系统用户）** | 开发与质量保障人员 | 明确可测试的解析、比对、合规、降级边界与验收标准 |

> 说明：沿用项目演示身份机制（前端生成 `userId`），**不含登录鉴权**（对齐 M1/M2 Non-Goal）。**服务端强制校验 `userId` 归属**，仅本人可上传/查看/对比自己的报告。

---

## 3. 项目目标

1. **G1 · 报告解读闭环**：用户上传化验单/体检单（PDF/图片）→ 结构化指标 + 参考范围比对（HIGH/NORMAL/LOW）+ 严谨解读 + 免责声明；异常项自动建议（handoff 问诊/用药/安全）。
2. **G2 · 长期记忆**：报告摘要写入健康档案，后续 Agent 对话自动注入上下文。
3. **G3 · 历史对比**：同指标多日期并列展示，Agent 对比解读变化（不做前端图表）。
4. **G4 · 合规红线**：报告不用于 LLM 训练（数据最小化）；授权 fail-closed；PII 脱敏；回复客观中立、不猜、带免责声明。
5. **G5 · 高可用降级**：ES 不可用 → 本地规则比对（标注未校验）；LLM 无 Key/失败 → 占位解读；解析失败 → UNKNOWN 不猜。**任何降级不阻断落库与回传**。

---

## 4. 非目标（Non-Goals）

以下内容**不在本期范围内**，明确排除：

| 编号 | 非目标 | 说明 |
| --- | --- | --- |
| NG-1 | 影像诊断 | B 超等影像仅做版式识别 + 报告文本解读；影像本身解读属放射 AI 领域，不做 |
| NG-2 | 真实 OCR / VLM 集成 | 本期**接口抽象 + 模拟实现**（`OcrEngine` / `VisionAnalyzer` + Mock），无真实 Key 可本地联调 |
| NG-3 | 前端 | 无上传页、无趋势图表；验证用 curl / 接口测试 |
| NG-4 | 其他领域代码 | 只写 agent-service；order BFF 上传代理、知识库灌入等列后置迭代 |
| NG-5 | 报告用于 LLM 训练 | 严格禁止；prompt 只发必要报告内容、剥离 PII（数据最小化） |
| NG-6 | 报告分享 / 多端同步 | 仅单用户本人可访问，不提供分享、导出、多端同步 |

---

## 5. 成功指标（可量化）

| 指标 | 定义/口径 | 目标值 | 对应目标 |
| --- | --- | --- | --- |
| M1 解析成功率 | 合法上传中成功抽取 ≥1 条结构化指标的比例 | ≥ 95%（演示文本格式） | G1 |
| M2 异常识别率 | 指标实际超出/低于参考范围时被标记 HIGH/LOW 的比例 | = 100% | G1 |
| M3 参考范围命中率 | 指标参考范围成功对齐 ES `lab_exam` 的比例 | ≥ 90% | G1 |
| M4 ES 降级可用 | ES 不可用时仍完成比对并返回（`referenceVerified=false`）的比例 | = 100% | G5 |
| M5 LLM 降级可用 | 无 Key/调用失败时仍返回占位解读 + 免责声明的比例 | = 100% | G5 |
| M6 合规拒绝率 | 无授权（consent=false）的上传被 fail-closed 拒绝的比例 | = 100% | G4 |
| M7 PII 泄露次数 | 落库与响应中出现的完整身份证号/证件号等 PII 次数 | = 0（脱敏） | G4 |
| M8 异常 handoff 触发率 | 存在 HIGH/LOW 指标时返回建议问诊 handoff 的比例 | = 100% | G1 |
| M9 对比完整度 | 同指标多日期数据按时间升序并列返回的比例 | = 100% | G3 |
| M10 回归完整度 | 本文档验收标准（AC）在测试中的覆盖率 | 100% 执行，通过率 ≥ 95% | 全部 |

---

## 6. 名词与术语

| 术语 | 说明 |
| --- | --- |
| 报告解读 | REPORT Agent 核心能力：上传 → 解析 → 比对 → 解读 → 记忆 → 对比 |
| 检查报告 | 用户上传的化验单 / 体检单 / 报告扫描件（PDF/图片），含检查项目与数值 |
| 化验单 / 体检单 | 报告两类优先覆盖的版式：`ReportTypeDetector` 判 `LAB` / `PHYSICAL` |
| 双通道解析 | OCR 通道（结构化版式抽数值）+ 多模态通道（复杂版式/影像兜底） |
| 参考范围 | 指标的正常区间（如 WBC 3.5-9.5 10^9/L）；权威值对齐 ES `lab_exam.indicators.ref_range` |
| 指标（Indicator） | 检查项目：名称 + 数值 + 单位 + 参考范围 + 异常标记 |
| 异常标记 | `HIGH 偏高 / NORMAL 正常 / LOW 偏低 / UNKNOWN 无法判定`；解析不出 → UNKNOWN，不猜 |
| 长期记忆 | 健康档案 `HealthProfile`：报告摘要注入后续 Agent 对话上下文 |
| 报告对比 | 同 `userId` + 同指标名多日期并列，Agent 对比解读 |
| 脱敏 | 剥离姓名/证件号等 PII（`ReportComplianceService.desensitize`） |
| 数据最小化 | prompt 只发必要报告内容，不用于 LLM 训练 |
| 授权同意 | 上传前置 `consent=true`（fail-closed，默认拒绝） |
| 免责声明 | 固定尾句「本报告解读由 AI 生成，仅供参考，不能替代专业医疗诊断」 |
| Handoff | 异常指标触发转交：建议问诊（CONSULTATION）/ 问药（MEDICINE_QA）/ 安全（SAFETY） |

---

## 7. 用户角色与权限

| 角色 | 可访问能力 | 权限边界 / 隔离 |
| --- | --- | --- |
| **买家 / 患者（核心用户）** | 上传**自己的**报告、查看结构化指标与解读、查看/对比**自己的**历史指标、通过 Agent 对话追问报告 | **只能上传/查看/对比自己的报告**（服务端按 `userId` 强制校验，越权拒绝）；不泄露他人报告 |
| **系统内部服务** | `AgentOrchestrator` 调 REPORT Agent；`ReportReferenceService` 调 ES `lab_exam`；`ReportUploadService` 调 LLM（best-effort） | 报告上传端点供调试直连（agent-service 直曝），**BFF 唯一入口原则**本期已知偏离（见 NG-4/§13.2） |
| **研发 / 测试** | 执行验收标准、模拟异常（ES 停机、无 Key、越权 userId、非法文件） | 可模拟降级路径与 fail-closed |

**隔离实现约定（产品要求，落实到服务端最终校验）：**

- 上传与对比接口均以 `userId` 为归属边界；对比仅返回 `userId` 本人数据。
- 页面隐藏入口只是体验，**隔离的正确性由服务端强制校验**（对齐 M1/M2「服务端最终校验」原则）。

---

## 8. 功能需求（Functional Requirements）

> 编号规则：`FR-R-x`（Report）。研发细化逐条追溯 Spec `R-x`（见 `docs/spec/report-interpretation.spec.md` §2）。AC 编号见第 12 节（`AC-R-x-y`）。每条的「现状差异」标注相对现状代码的改动点。

### FR-R-1 报告上传（授权 + 文件校验）

**需求描述**：用户以 `multipart` 上传检查报告，需先明确授权（`consent=true`，fail-closed 默认拒绝）；文件格式与大小须合法。

- 请求参数：`userId`（必填）、`sessionId`（可选，缺省自动生成）、`file`（pdf/png/jpg/jpeg/webp，≤10MB）、`consent`（默认 `false`）。
- **授权 fail-closed**：`consent=false` 一律拒绝（提示「需先同意报告存储与解读协议」），不进入任何解析/存储流程。
- **文件白名单**：仅接受上述格式；扩展名不合法或超 10MB 拒绝。
- 上传响应在 Agent 会话中形成一次「报告解读」对话记录（`sessionId` 关联）。

**前置条件**：agent-service 可用；`shopdb_agent` 已建库。

**主流程步骤**：
1. 客户端 `POST /api/agents/report/upload`（multipart：userId、sessionId、file、consent）。
2. `ReportComplianceService.requireConsent`：未授权 → 抛「需先同意报告存储与解读协议」。
3. `ReportComplianceService.validateFile`：格式白名单 + ≤10MB，非法 → 抛「文件格式不合法」/「文件过大」。
4. 读取文件字节 → 进入解析（FR-R-2）。

**后置条件**：合法文件进入解析管线；非法/未授权请求被拒绝且不产生任何数据。

**现状差异**：全新能力，现状无上传端点。

---

### FR-R-2 双通道解析

**需求描述**：对上传文件识别版式并解析出结构化指标。化验单/体检单走 **OCR 通道**（精确抽数值）；复杂版式/影像走 **多模态通道**（兜底）。

- `ReportTypeDetector`：按文件名 + MIME + 内容启发式判定 `LAB（化验单）/ PHYSICAL（体检单）/ OTHER（其他/复杂版式/影像）`。
- **OCR 通道**（`OcrEngine` 接口 + `MockOcrEngine`）：解析 `#报告名`、`检查日期: yyyy-MM-dd`、`指标名 数值 单位 参考范围` 文本，输出指标列表。
- **多模态通道**（`VisionAnalyzer` 接口 + `MockVisionAnalyzer`）：复杂版式/影像默认样本（体检生化 GLU/TC/UA），保证链路可联调。
- 解析产出 `ReportDoc`：`examName / reportDate / source(ocr|vision) / type / rawText / indicators[]`。
- **不猜原则**：解析不出数值或单位 → 指标 `UNKNOWN`，不臆造。

**前置条件**：文件已通过授权与格式校验（FR-R-1）。

**主流程步骤**：
1. 字节流入 `ReportParser.parse(content, fileName, contentType)`。
2. `ReportTypeDetector` 判定类型：`LAB`/`PHYSICAL` → OCR 通道；`OTHER` → Vision 通道。
3. 通道返回 `ParsedReport` → 组装 `ReportDoc`（含原始文本，供脱敏与比对）。

**后置条件**：产出结构化 `ReportDoc`；无指标时指标列表为空（后续按 UNKNOWN 处理）。

**现状差异**：全新能力，现状无解析层。

---

### FR-R-3 参考范围比对与异常标记

**需求描述**：将指标数值与权威参考范围对齐，标记 `HIGH / NORMAL / LOW / UNKNOWN`；ES 不可用时**降级本地规则**比对并标注「参考范围未经校验」。

- 权威参考范围：ES `health-lab_exam` 索引的 NESTED 指标 `name → ref_range`。
- **比对判定**：数值落在区间内 → `NORMAL`；高于 → `HIGH`；低于 → `LOW`；解析不出/区间无法判定 → `UNKNOWN`（不猜）。
- **ES 降级（对齐项目 T-2 纪律）**：ES 查询失败/未命中权威范围 → 沿用报告自带 `refRange` + 本地规则比对，`referenceVerified=false`，响应带提示「（提示：参考范围未经校验）」。
- 指标 value/refRange 按检查当时**快照固存**，后续权威范围变更不影响历史对比。

**前置条件**：`ReportDoc` 已产出（FR-R-2）。

**主流程步骤**：
1. `ReportReferenceService.align(doc)`：逐个指标查 ES `lab_exam` 取权威范围。
2. 命中 → 用权威范围判定；未命中/ES 失败 → 用报告自带范围 + 本地规则判定。
3. 返回 `ReportDoc`（补充 `referenceVerified` 与各指标 status）。

**后置条件**：每个指标有明确 status（HIGH/NORMAL/LOW/UNKNOWN）；ES 不可用不影响返回。

**现状差异**：全新能力，现状无比对层。

---

### FR-R-4 事务落库 + 长期记忆

**需求描述**：报告记录 + 指标明细 + 健康档案摘要**同事务**写入 `shopdb_agent`；摘要注入后续 Agent 对话。

- **三表**：`report_record`（报告主表：examName/reportDate/source/summary/createdAt）、`report_indicator`（指标明细，复合索引 `user_id, indicator_name, exam_date` 支撑对比）、`health_profile`（健康档案，`user_id` 唯一，摘要追加）。
- **同事务（T-1）**：报告 + 全部指标一次提交，任一步失败整体回滚，不产生孤儿数据。
- **摘要生成**：`HealthMemoryService.summarize` 产出自然语言摘要（如「2026-08-01 血常规：1 项异常（ALT 60 U/L ↑）」）。
- **长期记忆**：摘要追加进 `health_profile`（幂等 upsert，上限保护）；后续 REPORT Agent 对话自动注入该用户健康档案摘要作为上下文。
- 报告保留在对话记录，支持历史回看与对比。

**前置条件**：`ReportDoc` 已比对完成（FR-R-3）；`shopdb_agent` 表已建。

**主流程步骤**：
1. 组装 `ReportRecord`（userId/sessionId/examName/reportDate/source/summary）→ `recordRepository.save`。
2. 逐指标组装 `ReportIndicatorRecord`（含 status、examDate 快照）→ `indicatorRepository.save`。
3. `healthMemory.upsert(userId, summary)` 更新健康档案。
4. 事务提交。

**后置条件**：报告/指标/档案一致落库；无孤儿报告；后续对话可读到健康摘要。

**现状差异**：全新能力；现状无 DB。

---

### FR-R-5 LLM 严谨解读 + 异常建议（Handoff）

**需求描述**：基于结构化报告调用 REPORT Agent 生成客观中立、严谨、带免责声明的解读；异常项自动建议转交问诊/用药。

- **System Prompt 强化**（`ReportAgent.systemPrompt`）：客观中立、只基于报告数据解读、不猜、不渲染焦虑；尾句固定免责声明。
- **数据最小化**：`buildLlmInput` 只发必要报告内容（指标名/值/单位/参考范围/异常标记），剥离 PII；**不用于训练**。
- **Handoff 建议**：存在 `HIGH/LOW` 指标 → 建议转交问诊（CONSULTATION）；LLM 输出 handoff 指令（`isHandoff`/`handoffTo`）→ 追加对应目标（问药 MEDICINE_QA / 安全 SAFETY）。
- **LLM 降级（T-3）**：无 Key/调用失败/超时 → 返回占位文本「报告解读服务暂时不可用，请稍后重试或咨询问诊医生」，**不影响已提交的落库事务**（best-effort）。

**前置条件**：报告已落库（FR-R-4）。

**主流程步骤**：
1. 组装最小化 LLM 输入（含 referenceVerified 提示）。
2. `AgentOrchestrator.chat(targetAgent=REPORT, session)` 生成解读（会话已绑定 userId，注入健康档案摘要）。
3. 失败 → 降级占位；成功 → 取解读文本。
4. 汇总响应：指标视图 + 摘要 + 解读 + 免责声明 + handoff 建议。

**后置条件**：响应含结构化指标 + 解读 + 免责声明；异常项带 handoff 建议；LLM 失败时解读为占位但数据已落库。

**现状差异**：REPORT 已有 Agent 壳子，本期补齐上传管线入口、memory 注入与工具（见 FR-R-6/FR-R-7）。

---

### FR-R-6 同指标历史对比

**需求描述**：用户可查看**同一指标**多日期的历史数值，多日期**并列**返回；Agent 基于并列数据对比解读变化。

- 查询维度：`userId`（本人）+ `indicatorName`（指标名）。
- 返回：该指标全部历史记录按 `examDate` 升序并列（值/单位/参考范围/状态/检查日期）。
- 无数据 → 返回友好提示「无该指标的历史数据」（fail-closed，不报错）。
- 标注说明：对比仅并列展示数值变化，**不做显著性/病理趋势判定**（产品口径：AI 建议线下就医，不自作诊断）。

**前置条件**：用户已上传 ≥1 份含该指标的报告（FR-R-4 落库）。

**主流程步骤**：
1. 客户端 `GET /api/agents/report/{userId}/compare?indicator={name}`。
2. `ReportCompareService.compare(userId, indicatorName)`：查 `report_indicator` 按 `examDate` 升序。
3. 空结果 → 抛「无该指标的历史数据」；否则返回并列条目 + 注记。

**后置条件**：同指标多日期并列返回；数据为检查当时快照，不受后续变更影响。

**现状差异**：全新能力，现状无。

---

### FR-R-7 隐私合规（贯穿管线）

**需求描述**：整条管线贯彻隐私合规红线：不训练、授权、脱敏、数据最小化、免责。

- **脱敏**（`ReportComplianceService.desensitize`）：剥离姓名/证件号等 PII；原始文本不入库、不进 prompt。
- **数据最小化**：prompt 只发必要报告内容；报告不用于 LLM 训练。
- **免责**：所有解读响应固定携带免责声明。
- **权限**：仅本人可上传/查看/对比（服务端强制校验）。
- **客观中立**：解读只基于报告数据，异常项引导线下就医，不渲染焦虑、不自作诊断。

**前置条件**：管线各环节就绪。

**主流程步骤**：贯穿 FR-R-1~R-6 各环节（授权/脱敏在入口，最小化在 prompt 构造，免责在输出）。

**后置条件**：落库与响应无 PII；prompt 无 PII 且最小化；所有解读带免责声明。

**现状差异**：全新能力（REPORT 原 System Prompt 无合规条款）。

---

### FR-R-8 知识库关联解读

**需求描述**：报告解读时从**报告相关的 ES 知识索引**检索解释性片段（检查模板 + 异常指标含义与建议），注入解读上下文；任一索引不可用/未命中**降级为空，不阻断**解读与落库。

- **关联索引（本期启用）**：
  - `lab_exam`：异常指标含义（`high_meaning` / `low_meaning`）+ 异常建议（`abnormal_advice`）；
  - `report_template`：检查项目模板信息（`template_name` / `description` / `layout`）。
- **其余报告相关索引**（`symptom` / `disease` / `department` / `drug` / `drug_interaction` / `health_content` / `faq_compliance` / `knowledge_relation`）：作为关系图谱与就诊/用药建议的知识来源，**待知识库灌入后按需启用**（见 §14.5.4）。
- **数据最小化**：仅注入指标相关片段，无 PII、无报告原文。
- **降级（对齐 T-2）**：ES 不可用或未命中 → 该片段为空，不影响解读与落库。

**前置条件**：`ReportDoc` 已比对完成（FR-R-3）。

**主流程步骤**：
1. `ReportUploadService` 构造最小化 LLM 输入后，调用 `ReportKnowledgeService.enrich(aligned)`。
2. 查 `report_template` 匹配检查项目模板；查 `lab_exam` 取各异常指标的含义与建议。
3. 命中片段追加到 LLM 输入；全部未命中 / ES 不可用 → 不追加，解读照常。

**后置条件**：解读可引用知识片段；任何检索失败不影响落库与返回。

**现状差异**：全新能力（原仅 `ReportReferenceService` 对齐参考范围，无解释性知识注入）。

---

## 9. 用户流程（User Flow）

> 以下为「上传报告 → 收到解读 → 历史对比 → Agent 追问」主路径的文字描述。

1. **上传报告**：用户进入对话（sessionId 已建立），同意「报告存储与解读协议」（`consent=true`），选择化验单/体检单文件（PDF/图片）上传。
2. **收到结构化解读**：系统双通道解析 → 参考范围比对 → 落库 → 摘要记忆 → 调用 REPORT Agent 生成解读；页面/对话回传：指标列表（数值/单位/参考范围/偏高/正常/偏低）+ 摘要 + 解读文本 + 免责声明。
3. **异常引导**：若存在偏高/偏低指标，解读中提示异常项并建议转交问诊医生（handoff）；用户可一键发起问诊。
4. **历史对比**：用户再次上传同项目报告（如 3 个月后的 WBC），在「历史对比」查询同指标多日期并列数值；Agent 对比解读变化。
5. **Agent 追问**：用户基于报告追问（如「我这个 ALT 偏高要紧吗」），REPORT Agent 自动注入健康档案摘要 + 报告工具（`queryReportCompare` / `getHealthProfile`）作答，全程带免责声明。

---

## 10. 非功能需求（NFR）

| 编号 | 分类 | 需求描述 | 验收要点 |
| --- | --- | --- | --- |
| NFR-R-1 | 合规 | **报告不用于 LLM 训练**：prompt 只发必要报告内容、剥离 PII；授权 fail-closed；PII 脱敏；回复客观中立 + 免责声明 | 落库/响应无 PII；无授权被拒；解读带免责 |
| NFR-R-2 | 容错 | **降级不阻断**：ES 不可用 → 本地规则比对（referenceVerified=false）；LLM 失败 → 占位解读；解析失败 → UNKNOWN 不猜 | 停 ES / 无 Key 后上传仍落库并返回 |
| NFR-R-3 | 一致性 | 报告 + 指标 + 健康档案**同事务**（T-1）；指标 value/refRange 快照固存；H2 与 MySQL 兼容（`value` 保留字规避） | 任一环节失败整体回滚，无孤儿数据 |
| NFR-R-4 | 可维护性 | 统一错误体 `{"message":"..."}`；枚举 JSON 保持 name（HIGH/NORMAL/LOW/UNKNOWN），中文走 `display` | 前端/服务间解析一致 |
| NFR-R-5 | 可观测 | 上传 → 解析 → 存库 → 解读 用 traceId 串联日志（对齐项目 T-6 埋点纪律） | 走 `X-Trace-Id` 对日志链路 |
| NFR-R-6 | 安全 | 文件白名单 + ≤10MB；文件仅内存处理，无路径穿越/落盘攻击面 | 非法格式/超限被拒 |
| NFR-R-7 | 性能 | 上传→落库→解读端到端响应可接受；LLM 调用为 best-effort，失败不拖垮主流程 | 无 Key 时响应不超时挂起 |
| NFR-R-8 | 可测试性 | 接口抽象（OcrEngine/VisionAnalyzer）+ Mock，无真实 Key 可本地全链路联调（对齐项目「降级/占位」惯例） | 测试跑 H2 + 占位 Key 全绿 |

---

## 11. 边界与异常场景（Anti-Happy-Path）

> 每条给出**期望行为**；对应可测试验收见第 12 节 AC。`★` 标注测试必须重点覆盖的防 happy path 场景。

| 编号 | 异常场景 | 期望行为 |
| --- | --- | --- |
| EX-R-1 ★ | 未授权上传（consent=false） | fail-closed 拒绝「需先同意报告存储与解读协议」，不进入任何解析/存储 |
| EX-R-2 ★ | 文件格式不合法（如 .exe） | 拒绝「文件格式不合法」，不解析 |
| EX-R-3 ★ | 文件超过 10MB | 拒绝「文件过大」，不解析 |
| EX-R-4 ★ | 文件读取失败 | 抛「文件读取失败，请重试」 |
| EX-R-5 ★ | 报告无可识别版式/解析不出指标 | 指标 `UNKNOWN`，解读明确「无法判定」，**不猜**、不渲染焦虑 |
| EX-R-6 ★ | ES 不可用 / 未命中权威参考范围 | 降级本地规则比对，`referenceVerified=false`，响应带「参考范围未经校验」提示 |
| EX-R-7 ★ | LLM 无 Key / 调用失败 / 超时 | 返回占位解读「报告解读服务暂时不可用…」，**已落库数据不受影响**（T-3） |
| EX-R-8 ★ | 指标高于/低于参考范围 | 标记 `HIGH/LOW`；响应建议转交问诊（handoff CONSULTATION） |
| EX-R-9 ★ | 对比指标无历史数据 | 抛「无该指标的历史数据」，不返回空脏数据 |
| EX-R-10 ★ | 越权对比（他人 userId） | 服务端校验归属，拒绝；不泄露他人数据 |
| EX-R-11 ★ | 报告中含 PII（身份证号/姓名等） | 脱敏：身份证号掩码、PII 行过滤；原始文本不入库、不进 prompt |
| EX-R-12 ★ | H2/MySQL 关键字冲突（`value`） | 物理列命名为 `indicator_value` 规避（H2 兼容），建表/查询不失败 |
| EX-R-13 ★ | 落库中途异常（报告已存、指标失败） | 三表同事务回滚，无孤儿报告/指标 |
| EX-R-14 | 同指标多份报告日期相同 | 按 examDate 升序并列，重复日期正常展示（不覆盖） |
| EX-R-15 ★ | 报告相关 ES 知识索引（`lab_exam` / `report_template`）不可用或无命中 | 知识片段为空，解读与落库照常（T-2 降级），不出现 500 |

---

## 12. 验收标准（Acceptance Criteria）

> 每条均为可执行的 Given-When-Then 验收，**必须防 happy path**：除正常路径外，每条功能至少含一条异常验收，覆盖 EX-R-1~14。测试环境：agent-service + MySQL（H2 内存库跑单测）+ 占位 API Key 就绪。

### FR-R-1 报告上传

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R1-1（正常） | Given 合法化验单 PNG + consent=true；When 上传；Then 返回结构化指标 + 摘要 + 解读 + 免责声明，且报告/指标/档案落库 |
| AC-R1-2（正常） | Given 合法上传且无 sessionId；When 上传；Then 自动生成会话 ID，解读关联该会话 |
| AC-R1-3（异常/EX-R-1） | Given consent=false；When 上传；Then fail-closed 拒绝「需先同意报告存储与解读协议」，无任何落库 |
| AC-R1-4（异常/EX-R-2） | Given 文件扩展名 .exe；When 上传；Then 拒绝「文件格式不合法」 |
| AC-R1-5（异常/EX-R-3） | Given 文件超 10MB；When 上传；Then 拒绝「文件过大」 |

### FR-R-2 双通道解析

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R2-1（正常） | Given 文本格式血常规（#血常规 + 检查日期 + WBC/RBC/ALT 行）；When 解析；Then OCR 通道抽取全部指标，examName=血常规、reportDate 正确 |
| AC-R2-2（正常） | Given 复杂版式/影像（OTHER）；When 解析；Then 走 Vision 通道，返回默认样本指标，source=vision |
| AC-R2-3（异常/EX-R-5） | Given 内容无法识别指标；When 解析；Then 指标标记 UNKNOWN 且不猜，解读明确「无法判定」 |

### FR-R-3 参考范围比对

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R3-1（正常） | Given WBC 12.0（区间 3.5-9.5）；When 比对；Then 标记 HIGH |
| AC-R3-2（正常） | Given HGB 138（区间 115-150）；When 比对；Then 标记 NORMAL |
| AC-R3-3（异常/EX-R-6） | Given ES 不可用；When 比对；Then 降级本地规则判定（12.0→HIGH），referenceVerified=false |
| AC-R3-4（异常/EX-R-6） | Given ES 不可用且未命中权威范围；When 比对；Then 沿用报告自带 refRange + 本地规则，referenceVerified=false |

### FR-R-4 事务落库 + 长期记忆

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R4-1（正常） | Given 合法上传；When 完成后查询；Then 报告 1 条 + 指标 N 条 + 健康档案存在（profileSummary 非空） |
| AC-R4-2（正常） | Given 二次上传同指标不同日期；When 查询对比库；Then 同指标两日期记录并存，examDate 升序 |
| AC-R4-3（异常/EX-R-13） | Given 模拟落库中途失败；When 上传；Then 三表回滚，无孤儿报告/指标 |
| AC-R4-4（正常） | Given 已上传报告；When 后续 Agent 对话；Then 自动注入健康档案摘要作为上下文 |

### FR-R-5 LLM 解读 + Handoff

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R5-1（正常） | Given 合法上传（占位 Key 走 Mock 解读）；When 完成；Then interpretation 非空且非空白，含免责声明 |
| AC-R5-2（正常/EX-R-8） | Given ALT 60（超 7-40）；When 完成；Then 指标 HIGH，suggestedHandoffs 含 CONSULTATION |
| AC-R5-3（正常） | Given 全部指标正常；When 完成；Then 无异常 handoff 建议 |
| AC-R5-4（异常/EX-R-7） | Given LLM 调用抛异常；When 完成；Then 返回占位解读「报告解读服务暂时不可用…」，已落库数据完整 |

### FR-R-6 同指标历史对比

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R6-1（正常） | Given 同指标 2026-06-01 与 2026-08-01 两份报告；When 对比；Then 两日期并列，按 examDate 升序，含注记 |
| AC-R6-2（异常/EX-R-9） | Given 无该指标历史数据；When 对比；Then 抛「无该指标的历史数据」 |
| AC-R6-3（异常/EX-R-10） | Given 传入他人 userId；When 对比；Then 拒绝/无数据，不泄露他人报告 |

### FR-R-7 隐私合规

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R7-1（安全/NFR-R-1） | Given 报告中含身份证号/姓名；When 上传完成；Then 落库与响应中无完整 PII（掩码/过滤） |
| AC-R7-2（安全/NFR-R-1） | Given 任意成功上传；When 查看响应；Then 携带免责声明且含「仅供参考」 |
| AC-R7-3（安全/NFR-R-1） | Given 任意成功上传；When 检查 prompt 输入；Then 仅含必要报告内容，无 PII、无原始全文冗余 |

### FR-R-8 知识库关联解读

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-R8-1（正常） | Given ES `lab_exam` 命中 ALT（`high_meaning` + `abnormal_advice`）；When 上传含 ALT 偏高报告；Then 解读输入含知识片段（指标含义 + 建议） |
| AC-R8-2（正常） | Given ES `report_template` 命中检查项目模板；When 上传；Then 解读输入含模板信息片段 |
| AC-R8-3（异常/EX-R-15） | Given ES 不可用/未命中；When 上传；Then 知识片段为空，解读与落库照常，无 500 |
| AC-R8-4（安全/NFR-R-1） | Given 任意命中；When 检查知识片段；Then 仅指标相关，无 PII、无报告原文 |

### 附加验收（跨功能 / 降级 / 回归）

| 编号 | 验收条件（Given-When-Then） |
| --- | --- |
| AC-RX-1（降级/NFR-R-2） | Given ES 停机 + 无 LLM Key；When 上传合法报告；Then 仍完成本地比对、落库、返回占位解读，全程不抛 500 |
| AC-RX-2（一致性/NFR-R-3） | Given H2（测试）/MySQL（运行）双环境；When 建表与增删查；Then `report_indicator` 均正常（indicator_value 规避保留字） |
| AC-RX-3（可观测/NFR-R-5） | Given 一次上传；When 携带 X-Trace-Id；Then 上传→解析→存库→解读日志可串联 |
| AC-RX-4（回归） | Given agent-service 全部既有测试；When 运行回归；Then 既有 AgentOrchestrator 等测试保持绿 |

---

## 13. 发布范围（M3 报告解读）

### 13.1 本期包含

| 模块 | 范围 | 对应 FR |
| --- | --- | --- |
| 上传入口 | `ReportController`：`POST /api/agents/report/upload`、`GET /api/agents/report/{userId}/compare?indicator=` | FR-R-1 / R-6 |
| 解析层 | `report/` 包：`ReportParser`（双通道）、`ReportTypeDetector`、`OcrEngine`/`MockOcrEngine`、`VisionAnalyzer`/`MockVisionAnalyzer`、`ReportDoc`/`ReportIndicator`、`RangeParser` | FR-R-2 / R-3 |
| 持久化 | `shopdb_agent` 三表：`report_record` / `report_indicator`（对比主索引）/ `health_profile`（健康档案） | FR-R-4 |
| 服务 | `ReportUploadService`（门面）、`ReportComplianceService`（合规）、`HealthMemoryService`（长期记忆）、`ReportCompareService`（对比）、`ReportReferenceService`（ES 对齐 + 降级） | FR-R-3~R-7 |
| Agent 增强 | `ReportAgent`（System Prompt 强化 + memory 注入）、`ReportTools`（`queryReportCompare` / `getHealthProfile`）、`AgentSession.userId` | FR-R-5~R-7 |
| 合规 | 授权 fail-closed、PII 脱敏、数据最小化、免责声明 | FR-R-1 / R-7 |
| 知识库关联 | `ReportKnowledgeService`：`lab_exam` 异常指标含义/建议 + `report_template` 模板信息；ES 降级为空 | FR-R-8 |
| 测试 | `ReportParserTest` / `RangeParserTest` / `ReportReferenceServiceTest` / `ReportUploadServiceTest`（含对比） | 第 12 节 |
| 交付物 | 本文档 PRD、`docs/spec/report-interpretation.spec.md`（Spec）、harness 知识回写、代码实现、测试 | 全流程 |

### 13.2 本期不包含（见 4. 非目标，后续迭代）

- 影像诊断（B 超等影像解读）
- 真实 OCR / VLM 集成（接口 + Mock）
- 前端上传页 / 趋势图表；order BFF 上传代理（**BFF 唯一入口原则本期已知偏离**，Spec §13 已标注）
- 其他领域代码（知识库灌入、ES 增量索引等）
- 报告分享 / 多端同步 / 导出

---

## 14. 附录：管线、接口与数据库

### 14.1 报告解读管线（P-1..P-10）

```
授权(consent) → 文件校验 → 读字节 → 双通道解析(OCR/Vision)
→ PII 脱敏 → ES 参考范围对齐(降级本地) → 摘要生成
→ 事务落库(报告+指标+健康档案) → LLM 解读(best-effort，降级占位)
→ 响应(指标视图+摘要+解读+免责+handoff 建议)
```

### 14.2 关键接口

| 服务 | 接口 | 说明 |
| --- | --- | --- |
| agent | `POST /api/agents/report/upload` | multipart 上传：userId/sessionId/file/consent；返回 `ReportUploadResponse` |
| agent | `GET /api/agents/report/{userId}/compare?indicator=` | 同指标多日期并列对比；返回 `ReportCompareResponse` |
| agent | `AgentOrchestrator.chat(targetAgent=REPORT)` | 内部：REPORT Agent 解读（注入健康摘要 + 报告工具） |
| agent | ES `health-lab_exam` | 内部：参考范围对齐（`indicators.ref_range`） |

### 14.3 数据库（`shopdb_agent`）

| 表 | 关键字段 | 索引/约束 |
| --- | --- | --- |
| `report_record` | id / user_id / session_id / exam_name / report_date / source / summary(Lob) / created_at | idx(user_id, session_id, created_at) |
| `report_indicator` | id / user_id / report_id / indicator_name / **indicator_value** / unit / ref_range / status / exam_date | **idx_indicator_compare(user_id, indicator_name, exam_date)** 对比主索引 |
| `health_profile` | id / user_id / profile_summary(Lob) / updated_at | uk_profile_user(user_id) |

> 注：`value` 为 H2 保留字，物理列命名 `indicator_value` 规避（NFR-R-3）。

### 14.4 与现有代码的集成点（现状差异汇总）

- `agent-service` 新增 `report/`、`model/`、`repository/`、`dto/`、`service/ReportUploadService` 等；`orchestration/tools/ReportTools`；`controller/ReportController`；`ReportAgent` 增强（systemPrompt / memoryContext / tools）。
- `agent-service/pom.xml` 新增 `spring-boot-starter-data-jpa` + `mysql-connector-j` + H2（test）；`application.properties` 新增 `shopdb_agent` 数据源。
- `init.sql` 新增 `CREATE DATABASE shopdb_agent` + GRANT。
- `AgentSession` 增加 `userId`（会话绑定用户，供报告工具与 memory 注入）。
- 复用（不重复造轮子）：`AgentOrchestrator` / `AbstractSubAgent` / `AgentSessionStore` / `HandoffTools` / `ManagerDelegateTools` / `BusinessException` / `ApiError` / `TraceAspect` / ES `lab_exam` 索引 / LLM 占位机制。

### 14.5 Agent 编排与报告解读

#### 14.5.1 REPORT 与各 Sub Agent 的关系（handoff 矩阵）

编排框架：1 个 Manager（Supervisor）+ 6 个 Sub Agent（CONSULTATION / MEDICINE_QA / SHOPPING_GUIDE / SCIENCE / REPORT / SAFETY）。`AgentOrchestrator` 混合编排，每次消息三步：

1. **Safety 前置**：所有消息先过 `SafetyAgent.preCheck`（fail-closed），命中紧急/违规关键词直接拦截返回；
2. **有 active Sub Agent** → `HandoffOrchestrator` 续聊（会话内可再次 handoff）；
3. **否则** → `SupervisorOrchestrator`：Manager 通过 `ManagerDelegateTools` 的 @Tool 委派最匹配的 Sub Agent。

REPORT 与其他 Sub Agent 的协作关系：

| 关系 | 方向 | 触发语义 |
| --- | --- | --- |
| Manager → REPORT | 委派（Supervisor） | 用户问「帮我看看这份报告/化验单」→ Manager 调 `reportAgent` 工具 |
| REPORT → CONSULTATION | handoff | 异常指标需就医建议 → 转问诊 Agent |
| REPORT → MEDICINE_QA | handoff | 异常指标涉及用药/禁忌 → 转问药 Agent |
| CONSULTATION → REPORT | handoff | 问诊中用户上传化验报告 → 转报告解读 Agent |
| 其他（SCIENCE / MEDICINE_QA 等） | 无直接边 | 经 Manager 或 CONSULTATION 中转 |

> handoff 白名单由各 Agent `handoffTargets()` 声明，`HandoffTools.doHandoff` 校验，越权拒绝（如 REPORT 不能直接转导购/科普）。
> 上传管线内的 handoff 建议由 `ReportUploadService.collectHandoffs` 生成：存在 HIGH/LOW 指标 → 建议 CONSULTATION；LLM 输出 handoff 指令 → 追加对应目标。

#### 14.5.2 如何新增一个 Sub Agent（框架约定）

1. `AgentType` 枚举（mall-common）新增类型（`displayName`/`description`；非 MANAGER 即 `isSpecialist()`）；
2. `agent/specialist/XxxAgent.java` extends `AbstractSubAgent`：实现 `type()` / `handoffTargets()` / `systemPrompt()`；可选覆写 `tools(session)`（挂 @Tool 绑定对象）与 `memoryContext(session)`（注入用户级长期记忆）；
3. `@Component` 自动被 `SubAgentRegistry` 收集（构造注入 `List<SubAgent>`），**无需手写注册**；
4. `ManagerDelegateTools` 增加一个 @Tool 委派方法（Supervisor 路由入口）；
5. `HandoffTools` 增加 `handoffToXxx`（可选，接收来自其他 Agent 的转交）；
6. `ManagerRouter.heuristicRoute` 增加关键词（无 LLM 模式的规则路由）；
7. `SupervisorOrchestrator.managerSystemPrompt` 补充对新 Agent 的说明。

> REPORT 为「增强既有 Sub Agent」的案例：`AgentType.REPORT` 已存在，本期新增 `ReportTools`（工具）、注入 `HealthMemoryService`（长期记忆）、强化 systemPrompt（客观中立/免责/隐私），并打通「上传→解析→比对→落库→记忆→解读」整条管线。

#### 14.5.3 REPORT 使用的 Tools

| Tool | 绑定 | 说明 |
| --- | --- | --- |
| `queryReportCompare(indicatorName)` | `ReportTools.bind(userId)` | 同指标多日期历史并列，供对比解读；权限 = 本人（R-6）；无数据返回友好提示 |
| `getHealthProfile()` | 同上 | 读取用户健康档案摘要（长期记忆） |
| 上下文注入（非 Tool） | — | 健康档案摘要（`memoryContext`）、知识库片段（`ReportKnowledgeService`）、RAG 检索（`KnowledgeRetrievalService`，`agentType=REPORT` 过滤） |

> 工具经 `AbstractSubAgent.tools(session)` 挂到 REPORT 的 ChatClient（仅 `llmEnabled` 时生效）；无 API Key 走占位回复（不调用工具）。

#### 14.5.4 ES 知识库与报告解读的映射（10 索引）

| 索引（别名 `health-*`） | 与报告解读的关联 | 本期状态 |
| --- | --- | --- |
| `lab_exam` | 检查项目权威参考范围 + 异常指标含义/建议 | ✅ 已启用（参考范围比对 + 异常知识） |
| `report_template` | 检查项目模板 / 字段映射 / 版式 | ✅ 已启用（模板信息） |
| `symptom` | 异常指标相关症状 | ⏳ 待知识库灌入后启用 |
| `disease` | 异常指标相关疾病 | ⏳ 待知识库灌入后启用 |
| `department` | 建议就诊科室 | ⏳ 待知识库灌入后启用 |
| `drug` / `drug_interaction` | 异常指标相关用药与相互作用（handoff 问药前背景） | ⏳ 待知识库灌入后启用 |
| `health_content` | 报告解读科普 | ⏳ 待知识库灌入后启用 |
| `faq_compliance` | 解读合规话术（must_say / must_not_say） | ⏳ 待知识库灌入后启用 |
| `knowledge_relation` | 指标→疾病→科室→用药 关系图谱 | ⏳ 待知识库灌入后启用 |

> 检索实现分两类：**结构化查询**（`ReportReferenceService` / `ReportKnowledgeService`，原生 ES Java Client，架构决策 D1——绕开 Spring AI 固定 mapping 的向量库）；**向量 RAG**（`KnowledgeRetrievalService` 按 `agentType` 过滤，需 VectorStore Bean + Embedding 模型，当前未装配，本期不启用）。

---

*本文档由产品经理维护；如有需求变更，更新文档版本并同步研发/测试。研发细化与规则追溯见 `docs/spec/report-interpretation.spec.md`。*
