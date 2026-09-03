# Agents 约束目录（顶层索引）

> 「agents.md 文件夹」——按模块细分的研发约束。模拟 Claude Code 的上下文加载哲学：**改哪个模块，就加载哪份约束**，避免一次把所有模块规则塞进上下文。本目录是**单一事实源**；Spec §13 研发方案按改动模块引用对应文件。

## 项目定位（先读这个）

本项目是**医疗买药问诊平台的问诊领域工程**。平台四大领域与本项目边界：

| 领域 | 职责一句话 | 与本项目关系 |
| --- | --- | --- |
| 商品领域 | 非药品类商品的目录、库存与价格 | 独立工程，不在本仓库 |
| 导购领域 | 用户意图理解、用药咨询引导、把「想买药」转化为「需要问诊」的线索 | **上游**：向本项目推送问诊线索（用户、主诉、意向药品） |
| **问诊领域（本项目）** | 从接诊到开方的完整医疗服务闭环 | 本仓库 |
| 订单领域 | 购药订单、支付、履约 | **下游**：凭本项目产出的处方承接处方药订单 |

问诊领域内部再分两层（与领域架构图对齐）：

- **问诊领域核心链路（领域负责）**：分配医生 → 问诊记录 → 开处方 → 分配药师（审方）
- **问诊领域支撑服务（2B 管理）**：医生药师管理、排班、药品服务、工资结算、处方管理

## 模块清单与实现状态

| 模块 | 服务 | 层 | 状态 | 何时必须读 |
| --- | --- | --- | --- | --- |
| [`mall-common.md`](mall-common.md) | 共享库（DTO/枚举/异常/埋点） | 基础 | ✅ 已实现 | 改 DTO/枚举/错误体/埋点，或新增共享 Bean |
| [`agent-service.md`](agent-service.md) | 多 Agent 编排 + RAG | **项目主体** | ✅ 已实现 | 改 Agent 路由/工具/知识检索 |
| [`consult-service.md`](consult-service.md) | 问诊会话 + 开方（存量参考实现） | 核心链路 | ✅ 已实现 | 改会话/WS/开方/处方消费 |
| [`medicine-service.md`](medicine-service.md) | 药品目录 | 支撑 | ✅ 已实现 | 改药品/库存/种子数据 |
| [`consult-record-service.md`](consult-record-service.md) | 问诊记录 | 核心链路 | 🚧 占位待实现 | 实现问诊记录服务前必读 |
| [`prescription-service.md`](prescription-service.md) | 开处方 | 核心链路 | 🚧 占位待实现 | 实现开处方服务前必读 |
| [`doctor-assignment-service.md`](doctor-assignment-service.md) | 分配医生 | 核心链路 | 🚧 占位待实现 | 实现分配医生服务前必读 |
| [`pharmacist-assignment-service.md`](pharmacist-assignment-service.md) | 分配药师 | 核心链路 | 🚧 占位待实现 | 实现分配药师服务前必读 |
| [`prescription-management-service.md`](prescription-management-service.md) | 处方管理 | 支撑 | 🚧 占位待实现 | 实现处方管理服务前必读 |
| [`staff-management-service.md`](staff-management-service.md) | 医生药师管理 | 支撑 | 🚧 占位待实现 | 实现医生药师管理服务前必读 |
| [`scheduling-service.md`](scheduling-service.md) | 排班 | 支撑 | 🚧 占位待实现 | 实现排班服务前必读 |
| [`salary-service.md`](salary-service.md) | 工资结算 | 支撑 | 🚧 占位待实现 | 实现工资结算服务前必读 |

> 端口规划：medicine 8081 / consult 8085+8086 / agent 8087（已实现）；consult-record 8090 / prescription 8091 / prescription-mgmt 8092 / doctor-assign 8093 / pharmacist-assign 8094 / staff 8095 / scheduling 8096 / salary 8097（占位待实现）。

## 角色分工（研发方案设计阶段的参与者）

| 角色 | 产出 | 依据 |
| --- | --- | --- |
| 产品 | `docs/0x-PRD.md`：FR/AC/EX/NFR/术语/非目标 | 业务意图 |
| 架构 | `docs/0x-design.md` + `harness/references/*`（T-x/C-x/M-x） | 系统级设计 + 统一规则 |
| **研发（/sdd 转化者）** | `docs/spec/*.spec.md`：§0 术语 / §2.5 业务域 / §3.5 改造定位 / §13 研发方案 | 深读 PRD + 知识层 + gitnexus/trace 证据 |
| 测试 | 测试计划/报告，用例追溯到 Spec §10/§11 | Spec 验收 |
| Claude 会话 | 执行 `/sdd`、按 Spec 实现、按 agents 约束落地 | 本目录 |

## 加载规则

1. **先定业务域**：PRD 功能触及哪些模块 → 读 `harness/references/module-contracts.md`（M-x）。
2. **读模块约束**：涉及哪个模块就加载 `harness/agents/<module>.md`（例如改开方 → `prescription-service.md` + `consult-service.md`）。
3. **读全局规则**：`harness/references/tech-rules.md`（T-x）+ `code-rules.md`（C-x）按需引用，不整篇硬塞。
4. **查术语**：Spec §0 用词查 `harness/references/glossary.md`，查不到先登记「待确认」。
5. **改造定位证据**：静态调用链 `docs/trace/01` §6（file:line）+ 动态 trace `docs/trace/03`（traceId 串联），定位新增/修改的类与方法。

## 与 harness 其他目录的关系

```
harness/
├── references/  ← 全局事实底座：术语 / 技术规则(T-x) / 代码规则(C-x) / 模块契约(M-x)
├── agents/      ← 本目录：按模块的研发约束（谁改谁加载）
├── assets/      ← 模板与示例：spec-template / coverage-matrix / examples
├── scripts/     ← 工具接口：gitnexus/grep/hook/maven 精确命令与降级
└── commands/    ← 管线：把 references + agents 的约束织进 Spec（/sdd skill 执行）
```

## 跨领域边界纪律（本领域工程的红线）

- ❌ 不得在本仓库实现订单/支付/商品/导购逻辑——它们分属下游订单领域、商品领域与上游导购领域的独立工程。
- ✅ 与上游导购领域的交互只通过**问诊线索接收接口**（用户信息 + 主诉 + 意向药品，fail-closed：线索不完整即拒收）。
- ✅ 与下游订单领域的交互只通过**处方凭据输出**（处方号 + 处方状态 + 药品明细快照）；订单凭处方号消费处方，处方一次性消费（CAS）。
- ✅ 平台用户从导购进入问诊、凭处方去订单购药，本领域是「医疗服务」唯一责任方。
