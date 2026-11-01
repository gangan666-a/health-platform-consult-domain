# Agent 记忆分层设计（L0~L3）

> 关联：`docs/15-agent-architecture.md`、`docs/18-report-db-optimization.md`（Episode Graph 趋势投影）
> 定位：把 agent-service 的记忆实现按「L0 会话 / L1 事实 / L2 场景 / L3 人物角色」四层对齐并补齐短板——
> L0 从内存滑窗升级为落库可还原；L1 补上「观点（主观）」；L3 补上用户画像（偏好/沟通习惯）。

---

## 一、分层总览

| 层 | 语义 | 本项目实现 | 关键代码 | 存储 |
| --- | --- | --- | --- | --- |
| **L0** 所有对话 → 还原 | 对话原文全量可回放 | 持久化会话记忆：USER/ASSISTANT 正文全量落库，注入按窗口（默认 40 条）控制体积，重启不丢 | `PersistentChatMemory`、`ChatMessageRecord`、`ChatMessageRecordRepository`（`chat_message` 表） | MySQL |
| **L1** 事实(客观)+观点(主观)+约束 → 召回+操作 | 结构化长期事实，查询时召回并指导动作 | HealthFactCard：异常指标/危急值史（客观）+ 过敏/用药/慢病红线（约束）+ **OPINION 主观观点（新增）**；召回 = 红线段注入（规避禁忌）+ 观点段注入（沟通参考）+ 跨报告趋势投影 | `HealthFactCard`、`HealthMemoryService`、`PatientObservationService` | MySQL（health_profile.fact_card JSON） |
| **L2** 场景 → 恢复上下文 | 跨会话/跨 Agent 的场景续接 | 卡派生档案摘要（新会话注入）+ 跨 Agent 交接纪要（换 Agent 不复述病情） | `deriveSummary()`、`HandoffSummaryService` | MySQL（profile_summary）+ 进程内 |
| **L3** 人物 \| 角色 | 按「人」沉淀画像、按「角色」隔离记忆 | 人物 = userId 健康档案 + **用户画像卡（新增：称呼/沟通风格/健康目标/偏好）**；角色 = 会话×AgentType 双键隔离 + 各 Agent persona | `UserPersonaCard`、`UserPersonaService`、`AgentChatClientFactory.memoryKey()` | MySQL（health_profile.persona_card JSON） |

system prompt 组装顺序（`AbstractSubAgent.buildSystemPrompt`）：persona（L3 角色）→ 健康档案/红线/观点/画像（L1/L2/L3）→ RAG 知识 → Manager 委派要点 → 交接纪要（L2 场景）→ 会话窗口原文（L0，由 `MessageChatMemoryAdvisor` 自动回放）。

---

## 二、本次补齐的三块

### 1. L0：会话原文落库，重启可还原

- 旧实现：`MessageWindowChatMemory` 内存滑窗，重启即失，超窗消息永久丢失。
- 新实现：`PersistentChatMemory` 实现 Spring AI `ChatMemory`——`add` 时把 USER 正文与**无工具调用**的 ASSISTANT 回复写入 `chat_message`；`get` 按 id 倒序取最近 `agent.memory.window`（默认 40）条反转回放；`clear` 整会话删除。
- 落库纪律：SYSTEM persona 每次请求重建不落库；TOOL / 带工具调用的 ASSISTANT 是请求内协议消息，回放会因缺少配对 tool 结果被 API 拒绝，一律不落库。
- 与 Advisor 的配合（Spring AI 1.0.0 实测字节码确认）：请求前 advisor `get()` 历史并入 prompt 并把新 user 消息 `add()` 进记忆，响应后把回复 `add()` 进去——持久化实现全量累积、按窗口回放，语义等价于原滑窗但多了一份可还原的完整原文。

### 2. L1：事实卡补上「观点（主观）」

- `FactType` 新增 `OPINION`（name=主题、value=观点内容）。
- 合并语义与红线相反：**同主题最新表达覆盖旧观点**（观点可变），firstSeen 保留首次表达时间；红线类（过敏/用药/慢病）依旧首次为准、只增不减。
- 容量分档：红线类全保留；异常指标保最近 30；观点保最近 10（观点是参考信息，优先级低于医学事实）。
- 召回：`HealthMemoryService.subjectiveSection(userId)` 输出【患者近期观点（主观表达，供沟通参考，非医学事实）】段，与红线段分开标注，防止模型把主观表达当医学事实做用药判断。
- 写入路径：问诊/问药 Agent 新增工具绑定 `ProfileMemoryTools.recordOpinion(topic, content)`——只在用户明确表达时由模型调用（沿用 recordFact「明确陈述才落库」纪律），同主题再次表达自动覆盖。

### 3. L3：用户画像卡（人物维度）

- 新增 `UserPersonaCard`（JSON 存 `health_profile.persona_card` 列，`ddl-auto=update` 自动加列）：称呼 / 沟通风格 / 健康目标 / 其它偏好。
- 合并语义：标量非空覆盖（后写为准），列表去重追加、超 10 条滚动保留最近；全空卡不落库。
- 注入：`UserPersonaService.personaSection(userId)` 输出【用户画像（个性化沟通参考，非医学事实）】段——问诊/问药 Agent 与红线、观点段拼接注入，报告解读 Agent 与档案摘要拼接注入。
- 写入路径：`ProfileMemoryTools.updatePersona(nickname, commStyle, healthGoals, preferences)`（列表参数 CSV/顿号分隔，空参数=不修改），同样只在用户明确表达时调用。
- 工程注意：`isEmpty()` 必须 `@JsonIgnore`——Jackson 会把 `isEmpty()` 识别为 `empty` 属性序列化，往返即报未知字段（与 HealthFactCard 的 `facts()` 命名坑同类）。

---

## 三、验证

- `PersistentChatMemoryTest`（4 例）：只落 USER/无工具调用 ASSISTANT；空白回复跳过；回放正序 + 窗口参数透传；clear 按 key 全删。
- `HealthFactCardTest` 新增 4 例：同主题观点最新覆盖（保 firstSeen）、不同主题共存、超 10 条裁最旧、摘要含观点段。
- `HealthMemoryServiceTest` 新增 2 例：观点写入→subjectiveSection 渲染；仅红线事实时观点段为空。
- `UserPersonaCardTest`（5 例）+ `UserPersonaServiceTest`（3 例）+ `ProfileMemoryToolsTest`（4 例）：合并/往返/注入/权限兜底。
- `HandoffGraphTest` 构造器同步更新；全套 199 例通过。

## 四、遗留与演进

- L0 目前同进程内多实例部署会各有一份内存会话状态（AgentSessionStore 仍是进程内），对话原文已落库但会话路由状态未持久化。
- L1 的 OPINION / L3 画像依赖模型在对话中主动调用写入工具；可演进为会话结束时的后台抽取（LLM one-shot 批量提取，失败不阻断）。
- 图2 右侧数据源中「问诊记录」（consult-record-service）与「用户信息」（上游领域）尚未回流进记忆层，当前只有「病历/报告」接入。
