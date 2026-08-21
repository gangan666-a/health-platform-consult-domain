# 14. SDD 工作流接入（Spec-Driven Development）

> 本文档说明 SDD harness 如何接入本项目的现有文档流。框架本体见 [`harness/`](../harness/README.md)，转化器为 `/sdd` skill（`.claude/skills/sdd/SKILL.md`）。

## 为什么引入 SDD

项目现有 `docs/01-PRD.md`（产品）与 `docs/02-solution-design.md`（架构）质量都很高，但二者与「逐功能、可测试、代码就近」的契约层之间存在缺口：

- PRD 描述**业务意图**（FR/AC/EX/NFR），但不含接口契约、数据模型、状态机等代码必需细节；
- 方案设计描述**系统架构**（ADR/API/DB），但按服务而非按功能组织，难以逐功能核对验收与测试。

SDD 补齐这一层：**每个 Feature 一份 Spec**，把 PRD 的可测验收 + 方案的契约细节 + 代码现状对齐，收敛成「这个功能必须满足什么、怎么验证」的单一事实来源，代码输出以 Spec 为准。

## SDD 在现有文档流中的位置

```
docs/01-PRD.md（产品：业务意图）
   │  /sdd 转化
   ▼
docs/spec/*.spec.md（SDD：逐功能契约层）── 一 Feature 一文件
   │   + SPEC-INDEX.md 索引
   │   + COVERAGE.md 双向追溯矩阵
   ▼
docs/02-solution-design.md（架构：ADR/API/DB/状态机）── 系统级设计，Spec 与它互相引用、不得矛盾
   ▼
代码实现（以 Spec 为验收依据）
   ▼
docs/04-test-plan.md / 05-test-report.md（测试计划与执行，用例追溯到 Spec 编号）
```

**职责划分**：
| 层 | 内容 | 归属 |
| --- | --- | --- |
| PRD | 业务意图、FR/AC/EX/NFR、非目标 | 产品 |
| **Spec** | 逐功能契约：功能/流程/接口/数据/状态/异常/边界/补偿/验收/测试映射 | 研发 + 测试（从 PRD 转化） |
| 方案设计 | 系统架构、跨服务边界、ADR | 架构 |
| 测试计划 | 用例追溯到 Spec §10/§11 | QA |

## 使用方式

```bash
# 1. 产品写完/更新 PRD 后，在 Claude Code 中执行：
/sdd                      # 转化 docs/01-PRD.md 全部功能
/sdd FR-2                 # 只转化 FR-2
/sdd docs/07-PRD-IM.md FR-D1   # 指定 PRD + 指定功能范围

# 2. 转化产出（docs/spec/ 下）：
#    - FR-2-place-order.spec.md   逐功能 Spec
#    - SPEC-INDEX.md              索引（限长）
#    - COVERAGE.md                双向追溯矩阵
# 3. 研发按 Spec 实现，测试按 Spec §10/§11 写用例并追溯到 Spec 编号。
```

## 状态流转与迭代

每份 Spec 走状态机：`DRAFT → REVIEWED → IMPLEMENTED → VERIFIED`。

| 触发 | 动作 |
| --- | --- |
| PRD 变更（prd_updated） | 重跑 `/sdd` 只改受影响章节 + 同步追溯矩阵，Spec 回 `DRAFT` |
| 评审发现缺陷（review_gap） | 修正 Spec 对应章节后复检门禁，回 `REVIEWED` |
| 测试发现未覆盖/失败（test_gap） | 补 Spec §10 验收或修实现，复验后回 `VERIFIED` |
| OPEN 待澄清项未清零 | **Spec 不得进入 REVIEWED**，先回产品澄清 |

## 当前状态

- 框架：`harness/`（管线、映射表、模板、门禁、追溯矩阵）已就绪。
- 示例：`harness/sdd/examples/FR-2-place-order.spec.md` 由 `docs/01-PRD.md` FR-2 转化，验证管线可用（含 2 个 OPEN 待澄清项：EX-6 已实现未回填、库存文案口径）。
- 待办：对 PRD 其余 FR（FR-1/3/4/5/6/7/8/9）逐一跑 `/sdd` 产出 Spec；IM 迭代（docs/07-PRD-IM.md）按需单独转化。
