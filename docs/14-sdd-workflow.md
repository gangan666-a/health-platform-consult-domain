# 14. SDD 工作流接入（Spec-Driven Development）

> 本文档说明 SDD harness 如何接入本项目的现有文档流。框架本体见 [`harness/`](../harness/README.md)，转化器为 `/sdd` skill（`.claude/skills/sdd/SKILL.md`）。

## 为什么引入 SDD

项目现有 `docs/01-PRD.md`（产品）与 `docs/02-solution-design.md`（架构）质量都很高，但二者与「逐功能、可测试、代码就近」的契约层之间存在缺口：

- PRD 描述**业务意图**（FR/AC/EX/NFR），但不含接口契约、数据模型、状态机等代码必需细节；
- 方案设计描述**系统架构**（ADR/API/DB），但按服务而非按功能组织，难以逐功能核对验收与测试。

SDD 补齐这一层：**每个 Feature 一份 Spec**，把 PRD 的可测验收 + 方案的契约细节 + 代码现状对齐，收敛成「这个功能必须满足什么、怎么验证、在哪里改、守什么规则」的**需求与技术中态**单一事实来源，代码输出以 Spec 为准。

### 背景与目的：vibe coding 提效下的三处暗礁

AI 辅助编程让个人编码效率提升 30%–50%，但设计债也在同步累积：**代码质量漂移**（中间件随手 new、埋点漏加、分层边界模糊，「看起来对」≠「符合约定」）、**沟通熵增**（产品与开发对同一句话口径不一，评审时讨论的不是同一件事）、**方案失真**（AI 出的方案是「加几个接口」的套路，落不到具体代码坐标）。SDD Harness 用「**Spec + 静态调用链图谱（AST 级）+ 动态 Trace + Hook 门禁**」一一对应：Spec 治沟通熵增，静态+动态证据的改造定位治方案失真，Hook 门禁 + agents 约束治质量漂移。目标是**保留 30-50% 提效收益的同时，代码质量 0 回退、新增代码 100% 带埋点可追溯**——提效收益 × 质量约束 = 可持续的研发节奏（完整表述见 [`harness/README.md`](../harness/README.md) 「项目背景与目的」）。

## SDD 在现有文档流中的位置

```
docs/01-PRD.md（产品：业务意图）
   │  /sdd 转化（深读 PRD → 载入知识层 + 模块约束 + trace 证据）
   ▼
docs/spec/*.spec.md（SDD：逐功能契约层 = 需求与技术中态）── 一 Feature 一文件
   │   + SPEC-INDEX.md 索引
   │   + COVERAGE.md 双向追溯矩阵
   │   + §0 术语对齐 / §2.5 业务域与规则 / §3.5 改造定位 / §13 研发方案
   ▼
docs/02-solution-design.md（架构：ADR/API/DB/状态机）── 系统级设计，Spec 与它互相引用、不得矛盾
   ▼
代码实现（以 Spec 为验收依据，按 agents/<module>.md 约束落地）
   ▼
docs/04-test-plan.md / 05-test-report.md（测试计划与执行，用例追溯到 Spec 编号）
```

**职责划分**：
| 层 | 内容 | 归属 |
| --- | --- | --- |
| PRD | 业务意图、FR/AC/EX/NFR、非目标 | 产品 |
| **Spec** | 逐功能契约 + 研发方案：功能/流程/接口/数据/状态/异常/边界/补偿/验收/测试映射 + §0 术语 + §3.5 改造定位 + §13 研发方案 | 研发 + 测试（从 PRD 转化） |
| 方案设计 | 系统架构、跨服务边界、ADR | 架构 |
| **知识层（references/）** | 统一术语（glossary）、技术规则（T-x）、代码规则（C-x）、模块契约（M-x） | 架构（随迭代增量回写） |
| **模块约束（agents/）** | 每模块 DO/DON'T、落点、不变量、影响面 | 架构（谁改谁加载） |
| 测试计划 | 用例追溯到 Spec §10/§11 | QA |

## 使用方式

```bash
# 1. 产品写完/更新 PRD 后，在 Claude Code 中执行：
/sdd                      # 转化 docs/01-PRD.md 全部功能
/sdd FR-2                 # 只转化 FR-2
/sdd docs/07-PRD-IM.md FR-D1   # 指定 PRD + 指定功能范围

# 2. 转化产出（docs/spec/ 下）：
#    - FR-2-place-order.spec.md   逐功能 Spec（含 §0 术语/§2.5 业务域/§3.5 改造定位/§13 研发方案）
#    - SPEC-INDEX.md              索引（限长）
#    - COVERAGE.md                双向追溯矩阵
# 3. /sdd 自动载入 harness/references/* + 触及模块的 harness/agents/<module>.md + harness/scripts/* + docs/trace/* 证据，并做改造定位；
#    转化后增量回写知识层（新术语/新代码位置/新规则）。
# 4. Spec 评审通过（REVIEWED）后，运行：
/implement FR-2              # Spec → 代码：复验 §3.5 file:line → 按 agents/references 约束实现 → 编译单测 → 状态 IMPLEMENTED
#    实现受埋点 hook（T-6）门禁：新建 Spring RestClient 必须挂 TraceContext.interceptor()，违规阻断。
# 5. 测试按 Spec §10/§11 写用例并追溯到 Spec 编号；起服务带 X-Trace-Id 走黄金链路验证。
```

## 状态流转与迭代

每份 Spec 走状态机：`DRAFT → REVIEWED → IMPLEMENTED → VERIFIED`。

| 触发 | 动作 |
| --- | --- |
| PRD 变更（prd_updated） | 重跑 `/sdd` 只改受影响章节 + 同步追溯矩阵，Spec 回 `DRAFT` |
| 评审发现缺陷（review_gap） | 修正 Spec 对应章节后复检门禁，回 `REVIEWED` |
| 测试发现未覆盖/失败（test_gap） | 补 Spec §10 验收或修实现，复验后回 `VERIFIED` |
| 评审通过进入实现（implement） | 运行 `/implement <Spec>`：复验 §3.5 file:line（静态 gitnexus/grep + 动态 trace）→ 按 agents/references 约束实现（埋点 hook T-6 门禁）→ 编译/单测 → Spec 置 `IMPLEMENTED` |
| OPEN 待澄清项未清零 | **Spec 不得进入 REVIEWED**，先回产品澄清 |

## 当前状态

- 管线：`harness/commands/`（六阶段 + 门禁 + 状态机 + 知识反馈）已就绪。
- 知识层：`harness/references/`（glossary 术语对齐 / tech-rules T-x / code-rules C-x / module-contracts M-x）已就绪，作为 Spec 引用的规则事实底座。
- 模块约束：`harness/agents/`（AGENTS.md + 8 个模块约束文件）已就绪，谁改哪个模块就加载哪份。
- Spec 模板：`harness/assets/spec-template.md` 已升级为 14 章节（新增 §0 术语对齐 / §2.5 业务域与规则 / §3.5 改造定位 / §13 研发方案）。
- 示例：`harness/assets/examples/FR-2-place-order.spec.md` 由 `docs/01-PRD.md` FR-2 转化，已验证新增章节（§0/§3.5/§13）用法，含 2 个 OPEN 待澄清项（EX-6 已实现未回填、库存文案口径）。
- 实现闭环：`.claude/skills/implement/SKILL.md`（`/implement`）把 REVIEWED Spec 落地为代码；`.claude/settings.json` + `.claude/hooks/check-instrumentation.py`（PostToolUse）强制埋点（T-6）门禁；`.claude/hooks/sync-llm-model.py`（PostToolUse 命中 `.claude/llm-switch.json` + SessionStart）强制模型切换单一事实源（T-8）。
- 待办：对 PRD 其余 FR（FR-1/3/4/5/6/7/8/9）逐一跑 `/sdd` 产出 Spec；IM 迭代（docs/07-PRD-IM.md）按需单独转化；每次转化按 Stage 6a 增量回写知识层；REVIEWED Spec 用 `/implement` 落地并验证。
