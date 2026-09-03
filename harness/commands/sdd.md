# SDD 流水线：6 阶段 + Spec 状态机

> 本文档定义 PRD → Spec 的转化流水线。每条规则对齐一个 harness 范式（见 `harness/README.md`）。转化器（`/sdd` skill 或人）必须按阶段顺序执行，不得跳过 Validate 直接转化。

## 输入 / 输出

- **输入**：产品写的 PRD（本项目示例 `docs/01-PRD.md`），含 FR（功能需求）、AC（验收标准）、EX（异常场景）、NFR（非功能）、术语、非目标。
- **规则输入（研发方案设计必需，转化前载入）**：
  - 术语对齐：`harness/references/glossary.md`
  - 技术架构规则（T-x）：`harness/references/tech-rules.md`
  - 代码架构规则（C-x）：`harness/references/code-rules.md`
  - 模块/业务域契约（M-x）：`harness/references/module-contracts.md`
  - 模块约束：目标功能触及模块的 `harness/agents/<module>.md`
  - 改造定位证据：静态调用链 `docs/trace/01` §6 + 动态 trace `docs/trace/03`
  - 工具接口（gitnexus/grep/python-hook/maven 精确命令与降级）：`harness/scripts/README.md`
- **输出**：
  - `docs/spec/<FR>-<slug>.spec.md` —— 一 Feature 一 Spec 文件（14 章节，含 §0 术语/§2.5 业务域/§3.5 改造定位/§13 研发方案）；
  - `docs/spec/SPEC-INDEX.md` —— 索引（一行一个，限长）；
  - `docs/spec/COVERAGE.md` —— 双向追溯矩阵；
  - PRD 质量报告 + OPEN 待澄清项清单（临时产出，可并入评审纪要）；
  - **知识回写**（增量）：新术语/新代码位置/新端点回写 glossary / module-contracts / agents。
  - **下游消费**：REVIEWED Spec 由 `/implement` 落地为代码（§3.5+§13），受埋点 hook（T-6）门禁——见 `.claude/skills/implement/SKILL.md`。

## 6 阶段管线

### Stage 1 — Parse 解析（对齐 `harness-safety`：fail-closed 三态）

把 PRD 解析成结构化中间表示（IR），**三态**判定每个元素：

| 状态 | 含义 | 处置 |
| --- | --- | --- |
| ✅ 可解析 | 语义清晰、可量化、可测试 | 进入 IR，供转化 |
| ⚠️ 缺失 | 该有的结构缺失（FR 无 AC、目标不可量化、术语未定义） | 记入 OPEN，**暂停转化该项**，回产品补齐 |
| ❌ 歧义 | 含模糊措辞、前后矛盾、多重解释 | 记入 OPEN，**不猜测**，产品澄清后再转化 |

**歧义探测器词库**（命中即 OPEN，不放过）：`可能 / 也许 / 视情况 / 待定 / 优化 / 尽量 / 友好提示 / 若干 / 视业务而定 / 合理` 以及任何「不可量化」的目标（无数字、无边界、无时间）。术语出现但术语表未定义 → OPEN。

> 纪律：**宁可 OPEN 也不猜**。猜测 = 把产品决策权挪给研发，是需求漂移的源头。

### Stage 2 — Validate 质量门禁（对齐 `harness-tool-system`：先校验后执行）

转化前跑 [`checklists/prd-quality-gates.md`](checklists/prd-quality-gates.md)，产出 **PRD 质量报告**：

- 每个 FR 是否 ≥ 1 个 AC？每个 AC 是否 Given-When-Then 可执行？
- 每个目标（G1..Gn）是否可量化（有指标 M1..Mn）？每个指标是否有目标值？
- 每个术语是否已定义？每个 EX 是否有「期望行为」？
- NFR 是否可验收（有验收要点）？非目标是否明确排除 scope creep？

**门禁结果**：任一必过项不过 → 报告列出缺口，回产品补齐后再进 Stage 3。不通过不得转化。

### Stage 3 — Transform 转化 + 补全（对齐 `harness-tool-system`：声明式映射）

按 [`prd-to-spec.md`](prd-to-spec.md) 的**声明式映射表**逐项转化，并做**研发补全**（PRD 没有但代码需要）。转化前先载入知识层与模块约束，并做**改造定位**：

- FR 需求描述 → 功能规格（输入/输出/业务规则/前置后置）
- FR 主流程步骤 → 流程规格（步骤/时序/调用边界）
- AC 验收标准 → 可测试验收（Given-When-Then，直接映射测试用例）
- EX 异常场景 → 异常规格（错误行为/错误码/降级路径）
- NFR → 非功能规格（性能预算/安全/可用性/幂等）
- 术语 → 术语表（复用 + 补全工程术语）
- 非目标 → 边界声明（Spec 不得越界，防 scope creep）

**研发补全清单**（Spec 独有，代码必需）：API 契约、数据模型、状态机、边界与校验规则、事务与补偿边界、测试映射、**改造定位（§3.5）**、**规则引用与模块约束（§2.5/§13）**。每一项都必须从 PRD 或既有方案（`docs/02-solution-design.md`）或代码证据（静态调用链/动态 trace/`gitnexus context`）可推导，**不得发明 PRD 没要求的业务行为**。

> 改造定位纪律：§3.5 必须给出「新增/修改/删除哪些类.方法、file:line/插入点、变更前后对比、必须保持的不变量」，证据来源标注静态调用链/动态 trace/代码阅读；拿不出落点 = Spec 不具备研发方案价值，不得宣称可进入代码。

### Stage 4 — Emit 产出（对齐 `harness-architecture`：文件即数据库 + 索引）

- **一 Feature 一文件**：`docs/spec/<FR>-<slug>.spec.md`（用 [`../assets/spec-template.md`](../assets/spec-template.md)）。
- **索引**：`docs/spec/SPEC-INDEX.md`，一行一个 `- [<标题>](<路径>) — <一句话> · 状态`；设行数与字节上限，超限截断（凡常载进上下文的文件必须限长）。
- **追溯矩阵**：`docs/spec/COVERAGE.md`（见 [`../assets/coverage-matrix.md`](../assets/coverage-matrix.md)），FR×AC×EX×NFR → Spec 章节 × 测试用例，双向可查。

### Stage 5 — Verify 质量门禁（对齐 `harness-code-quality`：验收清单）

转化完成后过 [`checklists/spec-quality-gates.md`](checklists/spec-quality-gates.md)：

- **双向可追溯**：每条 Spec 项带 PRD 引用 `(ref: FR-2 / AC-FR2-3 / EX-19 / NFR-6)`；从 PRD 任一元素能定位到对应 Spec 章节。
- **可测试性**：每条业务规则/异常可转成可执行断言（Given-When-Then 或契约或约束）。
- **无歧义**：不含「可能/待定/视情况」；边界有明确数字。
- **覆盖完备**：追溯矩阵中 FR×AC×EX 覆盖率 100%。
- **单一事实来源**：Spec 之间、Spec 与方案/代码现状无矛盾。
- **自包含**：每条 Spec 项含 文件+章节 定位，不含「based on findings」式懒惰委托。
- **术语对齐（A-5）**：§0 用词均可在 `harness/references/glossary.md` 解析（或已先扩展 glossary）。
- **改造定位可执行（E-5）**：§3.5 每个改动点可定位到 file:line，不变量明确，证据来源已标注。
- **规则引用齐全（G-1/G-2）**：触及模块的必守 `T-x/C-x` 已引用、`harness/agents/<module>.md` 已应用，无自造中间件/模式。

**门禁结果**：任一不过 → 迭代修正 Spec（回到 Stage 3/4），直到全绿。不过不得进入代码阶段。

### Stage 6 — Loop SDD 闭环（对齐 `harness-agentic-loop`：状态机 + transition）

Spec 状态机（每份 Spec 的状态）：

```
 DRAFT ──review──▶ REVIEWED ──implement──▶ IMPLEMENTED ──test──▶ VERIFIED
   │                   │                        │                   │
   └── prd_updated ────┴── review_gap ──────────┴── test_gap ──────┘
        （PRD 变更，Spec 需重转）   （评审发现缺陷）    （测试未覆盖/失败）
```

| 状态 | 含义 | 进入条件（transition） |
| --- | --- | --- |
| `DRAFT` | 刚转化产出，待评审 | 转化完成 |
| `REVIEWED` | 已过 Spec 质量门禁 + 评审通过 | review 通过 |
| `IMPLEMENTED` | 代码已按 Spec 实现 | 实现完成（`/implement` 执行，复验 §3.5 file:line + 编译单测 + 埋点 hook 通过） |
| `VERIFIED` | 测试已验证 Spec 全部可测项 | 测试通过 |

**回环纪律**：仅当出现 `prd_updated / review_gap / test_gap` 时 continue（重转/修订）；否则收束为 VERIFIED。`OPEN` 待澄清项未清零前，Spec 不得进入 `REVIEWED`。

**知识反馈（持续完善，对齐 `harness-io-performance` 只算变化）**：每次转化后，把新发现的事实**增量回写**知识层——新术语 → `glossary.md`「待确认」；新代码位置/端点/依赖 → `module-contracts.md` 与对应 `agents/<module>.md`；新规则需求 → 扩展 `tech-rules.md`/`code-rules.md`（按编号续号，不改既有条目）。这是 harness 随迭代「自学习」的闭环。

## 与既有文档流的关系

```
01-PRD（产品） ──▶ [14-sdd-workflow] ──▶ 02-solution-design（架构） ──▶ 代码 ──▶ 04-test-plan
                       │
                       ├──▶ harness/references/（术语 T / 技术规则 T-x / 代码规则 C-x / 模块契约 M-x）
                       ├──▶ harness/agents/（模块约束，谁改谁加载）
                       └──▶ docs/spec/*.spec.md（逐功能契约层 = 需求与技术中态，含 §0/§2.5/§3.5/§13）
```

Spec 是**逐功能、可测试、代码就近**的契约层；方案设计是**系统级架构**层。二者互补：方案定了「怎么搭」，Spec 定了「这个功能必须满足什么、怎么验证、在哪里改、守什么规则」——即**需求与技术的中态**。知识层（references/）与模块约束（agents/）是 Spec 引用的事实底座，随迭代增量回写（见 Stage 6 知识反馈）。

## 文件即数据库：维护纪律

- 新增/修改 Spec：先写内容文件，再更新索引指针（两步写入）。
- Spec 增量更新：PRD 变更时**只改受影响章节 + 同步追溯矩阵**，不整篇重写（对齐 `harness-io-performance`：只算变化）。
- 索引限长：SPEC-INDEX 设行数/字节上限，防止常载上下文膨胀。
