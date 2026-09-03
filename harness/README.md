# Harness 工程：SDD（Spec-Driven Development）框架 + 研发方案设计层

> 从 Claude Code 源码工程哲学中提炼的可执行范式，落地到本项目，形成「产品 PRD → 高质量 Spec（需求与技术中态）→ 高质量代码」的规格驱动开发闭环。Spec 不只是一份业务契约，还是**研发方案**：术语对齐（§0）、业务域与统一规则（§2.5）、改造定位（§3.5，结合 GitNexus 静态图谱 + 动态埋点 trace）、分文件实施清单（§13）。

## 背景：6 个源范式

本项目 harness 工程源自对 Claude Code 源码的 6 个提炼（`~/.claude/skills/harness-*`）：

| 范式 | 一句话 | 在本 SDD 框架中的落点 |
| --- | --- | --- |
| `harness-safety-permissions` | fail-closed：默认拒绝、不猜测、解析失败即拒绝 | **Parse 阶段**：PRD 歧义/缺失项标记为 OPEN，绝不猜测 |
| `harness-tool-system` | 工具 = 纯声明 + 执行器分离；先校验后执行 | **Validate / Transform 阶段**：先过 PRD 质量门禁，再按声明式映射转化 |
| `harness-architecture` | 文件即数据库 + 索引；模块边界即契约 | **Emit 阶段**：一 Feature 一 Spec 文件 + SPEC-INDEX + 追溯矩阵 |
| `harness-code-quality` | 可追溯、可度量、自包含、fail-closed 默认 | **Verify 阶段**：Spec 质量门禁逐项勾选 |
| `harness-agentic-loop` | 状态机编入循环，显式 transition 收束 | **Loop 阶段**：Spec 状态机 DRAFT→REVIEWED→IMPLEMENTED→VERIFIED |
| `harness-io-performance` | 高频合并低频、只算变化、预检短路 | 文档维护纪律：Spec 增量更新、索引限长、知识层增量回写 |

## 核心闭环

```
产品写 PRD ──▶ 质量门禁 ──▶ 转化 Spec ──▶ Spec 质量门禁 ──▶ 研发按 Spec 写代码
   ▲                │            │                 │    （/implement 落地 · PostToolUse 埋点 hook T-6 门禁）
   │                ▼            ▼                 ▼                │
   │                ▼            ▼                 ▼                ▼
   └──────── PRD 澄清 ◀── OPEN 待澄清 ── 评审缺陷 / 测试缺口 ◀── 测试验证
                                                           │
                                                           ▼
                                           知识回写（glossary / module-contracts / agents）
```

SDD = **以 Spec 为契约**：代码输出前先有可测试、可追溯、无歧义、**含研发方案（改造定位）**的规格；Spec 不通过评审则代码不落。

## 目录结构

> 目录编排对齐 Claude Code 惯例：`commands/`（一命令一组规则文件）、`agents/`（一文件一单元）、`references/`（按需加载的参考知识）、`assets/`（模板与示例）、`scripts/`（工具/执行器）。原 `knowledge/` → `references/`、`sdd/` → `commands/` + `assets/`、`tools/` → `scripts/`，内容与功能不变。

```
harness/
├── README.md                  # 本文件：总览
├── references/                # ★ 知识层（对齐 skill 的 references/：按需加载的事实底座）
│   ├── README.md              #   维护纪律（只算变化 / fail-closed 未知术语→OPEN）
│   ├── glossary.md            #   术语对齐：PRD 术语 ↔ 统一术语 ↔ 代码符号
│   ├── tech-rules.md          #   技术架构规则（T-x：中间件/通信/数据边界/一致性/trace）
│   ├── code-rules.md          #   代码架构规则（C-x：分层/包/Bean注册/校验/补偿幂等/新服务）
│   └── module-contracts.md    #   业务域契约（M-x：端口/DB/端点/依赖矩阵/黄金链路）
├── agents/                    # ★ 模块约束（对齐 .claude/agents/：一文件一单元）：改哪个模块 → 读哪份
│   ├── AGENTS.md              #   顶层索引：角色分工 + 加载规则
│   └── <module>.md            #   mall-common / order / product / payment / recommend / consult / agent / medicine
├── commands/                  # ★ /sdd 管线规则（对齐 .claude/commands/：一命令一组规则文件）
│   ├── sdd.md                 #   SDD 流水线：6 阶段 + Spec 状态机 + 知识反馈
│   ├── prd-to-spec.md         #   ★ 转化规则：PRD 元素 → Spec 元素映射表 + 研发补全清单
│   └── checklists/
│       ├── prd-quality-gates.md   # 转化前 PRD 质量门禁（fail-closed）
│       └── spec-quality-gates.md  # 产出后 Spec 质量门禁（验收清单，含 A-5/E-5/G-1/G-2）
├── assets/                    # ★ 模板与示例（对齐 skill 的 assets/）
│   ├── spec-template.md       #   ★ 高质量 Feature Spec 模板（14 章节，含 §0/§2.5/§3.5/§13）
│   ├── coverage-matrix.md     #   双向追溯矩阵模板（FR×AC×EX×NFR → Spec×Test）
│   └── examples/
│       └── FR-2-place-order.spec.md   # ★ 示例：完整 Spec（演示 §0 术语/§3.5 改造定位/§13 研发方案）
└── scripts/                   # ★ 工具接口（对齐 skill 的 scripts/）：gitnexus/grep/python(hook)/maven 精确命令、输出→去向、降级
    └── README.md
```

配套可执行入口：`.claude/skills/sdd/SKILL.md`（`/sdd` 指令）与 `.claude/skills/implement/SKILL.md`（`/implement` 指令）；埋点门禁 hook 在 `.claude/settings.json` + `.claude/hooks/check-instrumentation.py`。接入说明见 [`docs/14-sdd-workflow.md`](../docs/14-sdd-workflow.md)。

## 分层职责（知识 / 约束 / 管线 / 模板 / 工具）

| 层 | 目录 | 内容 | 谁引用 |
| --- | --- | --- | --- |
| 知识层 | `references/` | 术语（glossary）、技术规则（T-x）、代码规则（C-x）、模块契约（M-x） | Spec §0/§2.5/§13；agents |
| 模块约束 | `agents/` | 每模块 DO/DON'T、落点、不变量、影响面 | Spec §13；改代码前必读 |
| 管线 | `commands/` | 六阶段转化 + 门禁 + 状态机 + 知识回写 | `/sdd` skill |
| 模板/示例 | `assets/` | Spec 模板、追溯矩阵模板、完整示例 Spec | `/sdd` Step 4；`/implement` |
| 工具层 | `scripts/` | 外部工具（gitnexus/grep/python-hook/maven）精确命令与降级 = 执行器 | `/sdd` Step 0/3a；`/implement` Step 0/2/4/5 |

## 使用方式

```bash
# 产品写完 PRD（如 docs/01-PRD.md）后，在 Claude Code 中执行：
/sdd PRD 路径或功能范围       # 自动跑 Parse→Validate→Transform（含改造定位）→Emit→Verify，并知识回写
/implement <Spec 路径或 FR-x>  # Spec（§3.5+§13）→ 代码：复验落点 → 按 agents/references 约束实现 → 编译单测 → 状态 IMPLEMENTED
```

或按 [`commands/sdd.md`](commands/sdd.md) 的 6 阶段手工执行。框架文件是唯一规则来源，模板随框架维护；`/sdd` skill 只是执行器，不改写规则。改代码前：按 `agents/AGENTS.md` 加载规则，读触及模块的 `agents/<module>.md` + `references/` 相关 T-x/C-x。
