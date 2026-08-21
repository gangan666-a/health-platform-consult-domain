# Spec 质量门禁（产出验收清单）

> Stage 5 使用。每份 Spec 交付前逐项勾选，**任一不过 → 迭代修正，不得进入 REVIEWED**。对齐范式：`harness-code-quality`（可追溯、可度量、自包含、fail-closed 默认）+ `harness-agentic-loop`（门禁即收束条件）。

## 使用方法

对 `docs/spec/*.spec.md` 逐份执行。逐项标注 `✅ / ❌` + 证据（章节/行）。全部 ✅ 才能把 Spec 状态置为 `REVIEWED`。

## 验收清单

### A. 双向可追溯

- [ ] A-1 每条 Spec 项带 PRD 引用（`ref: FR-x / AC-FR?-y / EX-z / NFR-w`）
- [ ] A-2 研发补全项标注来源（`ref: SD-02 §x` 或 `derived` + 推导理由），无发明需求
- [ ] A-3 从 PRD 任一 FR/AC/EX 能反向定位到对应 Spec 章节（用追溯矩阵核对）
- [ ] A-4 非目标红线已抄入 Spec 边界声明，且 Spec 未越界

### B. 可测试性

- [ ] B-1 每条业务规则（§2 R-x）可转成可执行断言
- [ ] B-2 每个异常（§7 E-x）至少对应一条验收（§10 T-y）
- [ ] B-3 每个 NFR 至少一条可量化验收（有数字/边界）
- [ ] B-4 §10 验收全部为 Given-When-Then，且含具体数值/状态/文案

### C. 无歧义（fail-closed）

- [ ] C-1 不含歧义词库词（可能/也许/视情况/待定/优化/尽量/合理/若干）
- [ ] C-2 边界有明确数字（如 `quantity ∈ [1,999]`），无「适量/较多」类模糊
- [ ] C-3 多分支行为有明确取舍（无「A 或 B」未裁定）
- [ ] C-4 默认值显式声明（fail-closed：默认即最安全，危险面显式 override）

### D. 覆盖完备

- [ ] D-1 追溯矩阵中 PRD 该 Feature 的 FR 元素全覆盖（100%）
- [ ] D-2 AC 全部映射进 §10（无遗漏）
- [ ] D-3 EX 全部映射进 §7（无遗漏）
- [ ] D-4 OPEN 待澄清项已清零（或已评审裁定，非「搁置」

### E. 单一事实来源

- [ ] E-1 Spec 内部无自相矛盾（同一行为只描述一次）
- [ ] E-2 Spec 之间无冲突（涉及同一契约/数据模型时引用同一处）
- [ ] E-3 与方案设计（docs/02-solution-design.md）无矛盾
- [ ] E-4 与代码现状一致；不一致处已标注为「现状差异/演进建议」

### F. 自包含

- [ ] F-1 每条 Spec 项给出文件+章节定位
- [ ] F-2 每条验收给出验证方式（测试层次/工具）
- [ ] F-3 无「based on findings, fix it」式把责任推回 PRD/上一阶段

## 门禁结果

- 全部 ✅ → Spec 状态 → `REVIEWED`（`transition: review_approved`）。
- 任一 ❌ → 回到 Stage 3/4 修正对应章节（`transition: review_gap`），修正后复检。
- OPEN 未清零 → 强制回到 Stage 1/2 回产品（`transition: prd_updated`）。
