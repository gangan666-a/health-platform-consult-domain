# Harness 工程：SDD（Spec-Driven Development）框架

> 从 Claude Code 源码工程哲学中提炼的可执行范式，落地到本项目，形成「产品 PRD → 高质量 Spec → 高质量代码」的规格驱动开发闭环。

## 背景：6 个源范式

本项目 harness 工程源自对 Claude Code 源码的 6 个提炼（`~/.claude/skills/harness-*`）：

| 范式 | 一句话 | 在本 SDD 框架中的落点 |
| --- | --- | --- |
| `harness-safety-permissions` | fail-closed：默认拒绝、不猜测、解析失败即拒绝 | **Parse 阶段**：PRD 歧义/缺失项标记为 OPEN，绝不猜测 |
| `harness-tool-system` | 工具 = 纯声明 + 执行器分离；先校验后执行 | **Validate / Transform 阶段**：先过 PRD 质量门禁，再按声明式映射转化 |
| `harness-architecture` | 文件即数据库 + 索引；模块边界即契约 | **Emit 阶段**：一 Feature 一 Spec 文件 + SPEC-INDEX + 追溯矩阵 |
| `harness-code-quality` | 可追溯、可度量、自包含、fail-closed 默认 | **Verify 阶段**：Spec 质量门禁逐项勾选 |
| `harness-agentic-loop` | 状态机编入循环，显式 transition 收束 | **Loop 阶段**：Spec 状态机 DRAFT→REVIEWED→IMPLEMENTED→VERIFIED |
| `harness-io-performance` | 高频合并低频、只算变化、预检短路 | 文档维护纪律：Spec 增量更新、索引限长、变更只写 diff |

## 核心闭环

```
产品写 PRD ──▶ 质量门禁 ──▶ 转化 Spec ──▶ Spec 质量门禁 ──▶ 研发按 Spec 写代码
   ▲                │            │                 │                │
   │                ▼            ▼                 ▼                ▼
   └──────── PRD 澄清 ◀── OPEN 待澄清 ── 评审缺陷 / 测试缺口 ◀── 测试验证
```

SDD = **以 Spec 为契约**：代码输出前先有可测试、可追溯、无歧义的规格；Spec 不通过评审则代码不落。

## 目录结构

```
harness/
├── README.md                  # 本文件：总览
└── sdd/
    ├── README.md              # SDD 流水线：6 阶段 + Spec 状态机
    ├── prd-to-spec.md         # ★ 转化规则：PRD 元素 → Spec 元素映射表 + 研发补全清单
    ├── spec-template.md       # ★ 高质量 Feature Spec 模板（12 章节）
    ├── checklists/
    │   ├── prd-quality-gates.md   # 转化前 PRD 质量门禁（fail-closed）
    │   └── spec-quality-gates.md  # 产出后 Spec 质量门禁（验收清单）
    ├── coverage-matrix.md     # 双向追溯矩阵模板（FR×AC×EX×NFR → Spec×Test）
    └── examples/
        └── FR-2-place-order.spec.md   # ★ 示例：用 docs/01-PRD.md 的 FR-2 转化出的完整 Spec
```

配套可执行入口：`.claude/skills/sdd/SKILL.md`（`/sdd` 指令）。接入说明见 [`docs/14-sdd-workflow.md`](../docs/14-sdd-workflow.md)。

## 使用方式

```bash
# 产品写完 PRD（如 docs/01-PRD.md）后，在 Claude Code 中执行：
/sdd PRD 路径或功能范围       # 自动跑 Parse→Validate→Transform→Emit→Verify
```

或按 [`sdd/README.md`](sdd/README.md) 的 6 阶段手工执行。框架文件是唯一规则来源，模板随框架维护；`/sdd` skill 只是执行器，不改写规则。
