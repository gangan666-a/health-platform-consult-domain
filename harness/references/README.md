# 知识层：需求 ↔ 技术中态的事实底座

> 研发方案设计要「概念层面术语对齐、技术架构与代码架构有统一 rules、业务领域清晰」——本目录就是这层事实底座。Spec（`harness/assets/spec-template.md`）的 §0/§2.5/§3.5/§13 直接引用这里的编号，`agents/` 模块约束也引用这里的规则，**不各自发明**。

## 文件清单

| 文件 | 内容 | 编号前缀 | 谁引用 |
| --- | --- | --- | --- |
| [`glossary.md`](glossary.md) | 统一术语表：PRD 术语 ↔ 代码符号 ↔ Spec 用词 对齐 | 无 | Spec §0 术语对齐 |
| [`tech-rules.md`](tech-rules.md) | **技术架构**规则：必须用哪些中间件、服务间通信、数据边界、一致性、trace 纪律 | `T-x` | Spec §2.5/§13；agents/* |
| [`code-rules.md`](code-rules.md) | **代码架构**规则：分层、包结构、Bean 注册、校验/补偿/幂等模式、新服务落地 | `C-x` | Spec §2.5/§13；agents/* |
| [`module-contracts.md`](module-contracts.md) | **业务域契约**：7 模块 + mall-common 的职责/端口/DB/端点/依赖矩阵/黄金链路 | `M-x` | Spec §2.5；agents/* |

## 定位

```
docs/01-PRD（产品意图）
   │  /sdd 转化（深读 PRD → 遵守 harness）
   ▼
docs/spec/*.spec.md（需求与技术的中态契约）
   │   §0 术语对齐（查 glossary）        §2.5 业务域与适用规则（查 module-contracts + tech/code-rules）
   │   §3.5 改造定位（查 gitnexus + 动态 trace）  §13 研发方案（引用 T-x/C-x + agents/<module>）
   ▼
代码实现（以 Spec 为验收依据）
```

## 维护纪律

- **只算变化**（对齐 `harness-io-performance`）：`/sdd` 每次执行后，把新发现的事实（新术语、新代码位置、新端点）**增量回写**到本层，不整篇重写。
- **fail-closed 不造词**（对齐 `harness-safety`）：Spec 用词必须在 `glossary.md` 可解析；查不到的 → 记入 Spec §12 OPEN，或先扩展 glossary，**不得凭空造统一术语**。
- **规则单一事实**：`T-x / C-x / M-x` 编号全仓唯一；Spec 与 agents 只能**引用**，不得改规则内容；要改规则 → 改本文件并同步影响面。
- **与既有文档一致**：`docs/01-PRD`（业务）、`docs/02-solution-design`（方案）、`docs/trace/*`（静态图谱+动态 trace）是事实来源；发现矛盾 → **以当前代码为准**，并回写本层消除歧义。

## 新词 / 新规则登记

新增术语走 `glossary.md` 的「待确认」区；新增规则按既有编号续号（如 `T-7`、`C-7`、`M-9`），不得改写既有条目。
