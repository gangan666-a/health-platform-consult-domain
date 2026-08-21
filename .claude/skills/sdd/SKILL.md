---
name: sdd
description: 把产品写的 PRD 按 harness/sdd 管线转化为高质量 Spec（Spec-Driven Development）。执行 Parse→Validate→Transform→Emit→Verify 六阶段，产出 docs/spec/ 下的逐功能 Spec、SPEC-INDEX 索引与 COVERAGE 双向追溯矩阵；PRD 歧义/缺失项标记为 OPEN 绝不猜测（fail-closed）。PRD 更新、评审缺陷、测试缺口时迭代回环直至 Spec 全绿。
---

# SDD：PRD → 高质量 Spec（规格驱动开发）

把产品写的 PRD 转化成**逐功能、可测试、可追溯、无歧义**的 Spec 契约层，让后续代码输出以 Spec 为准（SDD）。

> **规则唯一来源**：本 skill 只是执行器，**不改写规则**。所有映射表、模板、检查清单以 `harness/sdd/` 目录为唯一事实来源：
> - 管线与状态机：`harness/sdd/README.md`
> - 转化规则（映射表 + 研发补全 + 歧义探测器）：`harness/sdd/prd-to-spec.md`
> - Spec 模板：`harness/sdd/spec-template.md`
> - PRD 质量门禁：`harness/sdd/checklists/prd-quality-gates.md`
> - Spec 质量门禁：`harness/sdd/checklists/spec-quality-gates.md`
> - 追溯矩阵：`harness/sdd/coverage-matrix.md`
> - 完整示例：`harness/sdd/examples/FR-2-place-order.spec.md`

## 输入

- 第一个参数：PRD 路径（默认 `docs/01-PRD.md`）或功能范围（如 `FR-2` / `FR-7,FR-8`）。
- 可选第二个参数：目标 Spec 状态（`DRAFT` 默认；转化后至少到 `REVIEWED` 需先清 OPEN）。

## 执行步骤（严格按序，不得跳过 Validate 直接转化）

### Step 0 载入规则
读取 `harness/sdd/prd-to-spec.md` 与 `harness/sdd/spec-template.md`；若 PRD 路径/范围已明确，先读 PRD 目标章节与相关方案（`docs/02-solution-design.md`）与代码现状，供补全与 E-4 一致性核对。

### Step 1 Parse 解析（fail-closed 三态）
把 PRD 目标范围解析成结构化 IR：FR / AC / EX / NFR / 术语 / 非目标。逐项判定三态：
- ✅ 可解析 → 进 IR；
- ⚠️ 缺失 / ❌ 歧义 → 记入 **OPEN**（来源位置 + 问题 + 澄清需求），**不猜测、不转化该项**。
- 用 `prd-to-spec.md` §3 歧义探测器词库（可能/也许/视情况/待定/优化/尽量…）扫描。

### Step 2 Validate PRD 质量门禁
跑 `checklists/prd-quality-gates.md`，产出 PRD 质量报告（B-1~B-8 必过项逐项 `✅/⚠️` + 证据位置）。
- 任一必过项不过 → 报告列缺口，**暂停转化**，回产品补齐（或用户确认豁免后继续，豁免须记入 OPEN）。

### Step 3 Transform 转化 + 研发补全
按 `prd-to-spec.md` §1 声明式映射表逐项转化，再按 §2 研发补全清单补全（API 契约/数据模型/状态机/边界校验/事务补偿/测试映射）。
- 纪律：补全项必须从 PRD 或方案可推导，推导不出 = 发明需求 → 记 OPEN，不得自行拍板业务行为。
- 追溯：每条 Spec 项带 `（ref: FR-x / AC-FR?-y / EX-z / NFR-w）`；补全项标 `（ref: SD-02 §x）` 或 `（derived + 理由）`。

### Step 4 Emit 产出
- 一 Feature 一文件：`docs/spec/<FR>-<slug>.spec.md`（用 spec-template.md 12 章节）。
- 更新 `docs/spec/SPEC-INDEX.md`（一行一个，限长）。
- 更新 `docs/spec/COVERAGE.md` 双向追溯矩阵（FR×AC×EX×NFR → Spec 章节 × 测试用例）。

### Step 5 Verify Spec 质量门禁
跑 `checklists/spec-quality-gates.md`（A 追溯 / B 可测试 / C 无歧义 / D 覆盖 / E 单一事实 / F 自包含）。
- 任一 ❌ → 回 Step 3/4 修正对应章节，复检；OPEN 未清零不得置 `REVIEWED`。

### Step 6 汇总（收束）
输出：
1. 产出的 Spec 清单（文件 + 状态）；
2. 追溯矩阵摘要（覆盖率）；
3. OPEN 待澄清项清单（若空，说明已全部澄清）；
4. 下一步建议（走 `/review` 评审 Spec / 按 Spec 写代码 / 回产品澄清 OPEN）。

## 核心纪律（对齐 harness 范式）

- **fail-closed，宁可 OPEN 不猜**（`harness-safety`）：歧义/缺失 = OPEN，不把产品决策权挪给研发。
- **先校验后转化**（`harness-tool-system`）：PRD 门禁不过不转化，Spec 门禁不过不落 REVIEWED。
- **一 Feature 一文件 + 索引 + 追溯矩阵**（`harness-architecture`）：SPEC-INDEX/COVERAGE 限长，常载限长。
- **自包含、可追溯**（`harness-code-quality`）：每条 Spec 项带 ref + 文件章节定位，禁止「based on findings」式懒惰委托。
- **状态机回环**（`harness-agentic-loop`）：`DRAFT→REVIEWED→IMPLEMENTED→VERIFIED`；仅 `prd_updated / review_gap / test_gap` 时 continue，其余收束。
- **只算变化**（`harness-io-performance`）：PRD 变更时只改受影响章节 + 同步追溯矩阵，不整篇重写。

## 反模式（禁止）

- 把 OPEN 项当「默认值」直接拍板转化。
- 跳过 PRD 质量门禁直接产 Spec。
- 补全项不标来源就写「我们加个 X 接口」。
- 生成的 Spec 含「可能/待定/视情况」却不入 OPEN。
- Spec 门禁未过就宣称完成、可进入代码。
