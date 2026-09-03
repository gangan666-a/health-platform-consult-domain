---
name: sdd
description: 把产品写的 PRD 按 harness/commands 管线转化为高质量 Spec（Spec-Driven Development）。执行 Parse→Validate→Transform→Emit→Verify 六阶段，产出 docs/spec/ 下的逐功能 Spec、SPEC-INDEX 索引与 COVERAGE 双向追溯矩阵；PRD 歧义/缺失项标记为 OPEN 绝不猜测（fail-closed）。PRD 更新、评审缺陷、测试缺口时迭代回环直至 Spec 全绿。
---

# SDD：PRD → 高质量 Spec（规格驱动开发）

把产品写的 PRD 转化成**逐功能、可测试、可追溯、无歧义、含研发方案（改造定位）**的 Spec 契约层，让后续代码输出以 Spec 为准（SDD）。Spec 是**需求与技术的中态**：既讲清「必须满足什么」（需求），也讲清「在哪里改、怎么改、守什么规则」（技术）。

> **规则唯一来源**：本 skill 只是执行器，**不改写规则**。所有映射表、模板、检查清单、规则、约束以 harness 目录为唯一事实来源：
> - 管线与状态机：`harness/commands/sdd.md`
> - 转化规则（映射表 + 研发补全 + 歧义探测器）：`harness/commands/prd-to-spec.md`
> - Spec 模板：`harness/assets/spec-template.md`
> - PRD 质量门禁：`harness/commands/checklists/prd-quality-gates.md`
> - Spec 质量门禁：`harness/commands/checklists/spec-quality-gates.md`
> - 追溯矩阵：`harness/assets/coverage-matrix.md`
> - 完整示例：`harness/assets/examples/FR-2-place-order.spec.md`
> - **术语对齐**：`harness/references/glossary.md`
> - **技术架构规则（T-x）**：`harness/references/tech-rules.md`
> - **代码架构规则（C-x）**：`harness/references/code-rules.md`
> - **模块/业务域契约（M-x）**：`harness/references/module-contracts.md`
> - **模块约束**：目标功能触及模块的 `harness/agents/<module>.md`
> - **改造定位证据**：静态调用链 `docs/trace/01-static-knowledge-graph.md` §6 + 动态 trace `docs/trace/03-dynamic-traces.md`
> - **工具接口**：`harness/scripts/README.md`（gitnexus/grep/python-hook/maven 精确命令与降级）

## 输入

- 第一个参数：PRD 路径（默认 `docs/01-PRD.md`）或功能范围（如 `FR-2` / `FR-7,FR-8`）。
- 可选第二个参数：目标 Spec 状态（`DRAFT` 默认；转化后至少到 `REVIEWED` 需先清 OPEN）。

## 执行步骤（严格按序，不得跳过 Validate 直接转化）

### Step 0 载入规则（含知识层 + 模块约束）
读取 `harness/commands/prd-to-spec.md` 与 `harness/assets/spec-template.md`；若 PRD 路径/范围已明确，先读 PRD 目标章节与相关方案（`docs/02-solution-design.md`）与代码现状，供补全与 E-4 一致性核对。**同时按功能触及的业务域载入**：
- `harness/references/glossary.md`（术语对齐）、`tech-rules.md`（T-x）、`code-rules.md`（C-x）、`module-contracts.md`（M-x）；
- 触及模块的 `harness/agents/<module>.md`（DO/DON'T 与落点）；
- 改造定位证据：`docs/trace/01` §6（静态调用链 file:line）+ `docs/trace/03`（动态 trace）。
- 用 `gitnexus context <关键符号>`（或 grep）核实落点行号是否与当前代码一致（trace 文档行号可能过期，以实测为准）。

### Step 1 Parse 解析（fail-closed 三态）
把 PRD 目标范围解析成结构化 IR：FR / AC / EX / NFR / 术语 / 非目标。逐项判定三态：
- ✅ 可解析 → 进 IR；
- ⚠️ 缺失 / ❌ 歧义 → 记入 **OPEN**（来源位置 + 问题 + 澄清需求），**不猜测、不转化该项**。
- 用 `harness/commands/prd-to-spec.md` §3 歧义探测器词库（可能/也许/视情况/待定/优化/尽量…）扫描。

### Step 2 Validate PRD 质量门禁
跑 `harness/commands/checklists/prd-quality-gates.md`，产出 PRD 质量报告（B-1~B-8 必过项逐项 `✅/⚠️` + 证据位置）。
- 任一必过项不过 → 报告列缺口，**暂停转化**，回产品补齐（或用户确认豁免后继续，豁免须记入 OPEN）。

### Step 3 Transform 转化 + 研发补全（含改造定位）
**3a 改造定位（先做）**：结合 Step 0 载入的证据，确定本 Feature 每个需求点落在哪个类/方法——
- 静态调用链 `docs/trace/01` §6 + 动态 trace `docs/trace/03`（traceId 实测）+ `gitnexus context <symbol>`/grep 核实 file:line；
- 产出 §3.5：待改造清单（动作/类.方法/位置 file:line/变更前后/不变量）+ §13 分文件清单（引用 T-x/C-x + `agents/<module>.md`）；
- 拿不出落点 = 研发方案不成立，回代码核证，不得凭印象写。
**3b 按映射表转化**：按 `harness/commands/prd-to-spec.md` §1 声明式映射表逐项转化（FR→§2 功能/§3 流程/§2.5 业务域+规则；术语→§0 对齐 glossary），再按 §2 研发补全清单补全（API 契约/数据模型/状态机/边界校验/事务补偿/测试映射/改造定位/规则引用/模块约束）。
- 纪律：补全项必须从 PRD 或方案或代码证据可推导，推导不出 = 发明需求 → 记 OPEN，不得自行拍板业务行为。
- 追溯：每条 Spec 项带 `（ref: FR-x / AC-FR?-y / EX-z / NFR-w）`；补全项标 `（ref: SD-02 §x）`、`（ref: T-x / C-x）` 或 `（derived + 理由）`。

### Step 4 Emit 产出
- 一 Feature 一文件：`docs/spec/<FR>-<slug>.spec.md`（用 `harness/assets/spec-template.md` 12 章节）。
- 更新 `docs/spec/SPEC-INDEX.md`（一行一个，限长）。
- 更新 `docs/spec/COVERAGE.md` 双向追溯矩阵（FR×AC×EX×NFR → Spec 章节 × 测试用例）。

### Step 5 Verify Spec 质量门禁
跑 `harness/commands/checklists/spec-quality-gates.md`（A 追溯 / B 可测试 / C 无歧义 / D 覆盖 / E 单一事实 / F 自包含）。
- 任一 ❌ → 回 Step 3/4 修正对应章节，复检；OPEN 未清零不得置 `REVIEWED`。

### Step 6 汇总（收束 + 知识反馈）
**6a 知识回写（只算变化，harness 自学习闭环）**：把本次转化新发现的事实**增量回写**知识层：
- 新术语 → `harness/references/glossary.md`「待确认」区；
- 新代码位置/端点/依赖 → `harness/references/module-contracts.md` 与对应 `harness/agents/<module>.md`；
- 新规则需求 → 扩展 `harness/references/tech-rules.md`（T-x）/ `code-rules.md`（C-x），按编号续号。
**6b 汇总输出**：
1. 产出的 Spec 清单（文件 + 状态）；
2. 追溯矩阵摘要（覆盖率）；
3. OPEN 待澄清项清单（若空，说明已全部澄清）；
4. 知识回写摘要（改了哪些 glossary / module-contracts / agents）；
5. 下一步建议（走 `/review` 评审 Spec / 评审通过后 `/implement <Spec>` 按 Spec 写代码 / 回产品澄清 OPEN）。

## 核心纪律（对齐 harness 范式）

- **fail-closed，宁可 OPEN 不猜**（`harness-safety`）：歧义/缺失 = OPEN，不把产品决策权挪给研发。
- **先校验后转化**（`harness-tool-system`）：PRD 门禁不过不转化，Spec 门禁不过不落 REVIEWED。
- **一 Feature 一文件 + 索引 + 追溯矩阵**（`harness-architecture`）：SPEC-INDEX/COVERAGE 限长，常载限长。
- **自包含、可追溯**（`harness-code-quality`）：每条 Spec 项带 ref + 文件章节定位，禁止「based on findings」式懒惰委托。
- **状态机回环**（`harness-agentic-loop`）：`DRAFT→REVIEWED→IMPLEMENTED→VERIFIED`；仅 `prd_updated / review_gap / test_gap` 时 continue，其余收束。
- **只算变化**（`harness-io-performance`）：PRD 变更时只改受影响章节 + 同步追溯矩阵，不整篇重写。
- **需求与技术中态**（本次演进核心）：Spec 必须含 §0 术语对齐、§3.5 改造定位（file:line + 证据来源 + 不变量）、§13 研发方案（T-x/C-x + agents 模块约束）；**无改造定位的 Spec 不宣称可进入代码**。
- **实现门禁外置**：代码落地由 `/implement` 执行，埋点（T-6）由 PostToolUse hook（`.claude/hooks/check-instrumentation.py`）强制，不靠自觉。
- **知识自学习**：转化后把新术语/新代码位置/新规则**增量回写** `harness/references/*` 与 `harness/agents/*`（Stage 6a），让 harness 随迭代自完善。

## 反模式（禁止）

- 把 OPEN 项当「默认值」直接拍板转化。
- 跳过 PRD 质量门禁直接产 Spec。
- 补全项不标来源就写「我们加个 X 接口」。
- 生成的 Spec 含「可能/待定/视情况」却不入 OPEN。
- Spec 门禁未过就宣称完成、可进入代码。
- **Spec 无 §3.5 改造定位（file:line + 证据）或 §13 研发方案就宣称可进入代码**。
- **Spec 用词 glossary 查不到却不登记「待确认」/不入 OPEN**（打破术语对齐）。
- **发明 `harness/references` 里没有的中间件/通信/校验/幂等模式**（应先扩展 rules）。
- **转化后不回写知识层**（glossary / module-contracts / agents），让 harness 失去自学习能力。
