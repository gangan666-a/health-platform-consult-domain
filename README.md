# 医疗买药问诊平台 · 问诊领域（consult-domain）

## 项目背景

「医疗买药问诊平台」是一个连接**患者、医生、药师**的互联网医疗服务系统。用户身体不适或需要购药时，从导购咨询出发，必要时进入在线问诊，由医生开具处方、药师审核处方，最终凭处方完成购药。

平台按业务边界划分为**四大领域**，各自独立工程、独立演进：

| 领域 | 职责 | 与本项目关系 |
| --- | --- | --- |
| 商品领域 | 非药品类商品的目录、库存与价格 | 独立工程 |
| 导购领域 | 用户意图理解、用药咨询引导；把「想买药」的用户转化为「需要问诊」的线索 | **上游**：向本项目推送问诊线索（用户 + 主诉 + 意向药品） |
| **问诊领域（本项目）** | 从接诊到开方的完整医疗服务闭环 | 本仓库 |
| 订单领域 | 购药订单、支付与履约 | **下游**：凭本项目产出的处方承接处方药订单 |

> 一句话：**用户经导购而来，带处方向订单而去；问诊领域是平台的「医疗服务」唯一责任方。**

## 本项目（问诊领域）的构成

问诊领域内部再分两层：

- **核心链路（领域直接负责）**：分配医生 → 问诊记录 → 开处方 → 分配药师（审方）
- **支撑服务（2B 管理）**：医生药师管理、排班、药品服务、工资结算、处方管理

### 模块清单

| 模块 | 服务 | 层 | 端口 | 数据库 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `agent-service` | 多 Agent 智能体（编排 + RAG + 报告解读） | **项目主体** | 8087 | `shopdb_agent` | ✅ 已实现 |
| `consult-service` | 问诊会话 / IM / 开方（存量参考实现） | 核心链路 | 8085 / WS 8086 | `shopdb_consult` | ✅ 已实现 |
| `medicine-service` | 药品目录（Rx/OTC、原子库存） | 支撑 | 8081 | `shopdb_medicine` | ✅ 已实现 |
| `consult-record-service` | 问诊记录（全过程留痕、取证） | 核心链路 | 8090 | `shopdb_consult_record` | 🚧 占位 |
| `prescription-service` | 开处方（医生在线开方） | 核心链路 | 8091 | `shopdb_prescription` | 🚧 占位 |
| `prescription-management-service` | 处方管理（全生命周期状态机、审方流转） | 支撑 | 8092 | `shopdb_prescription_mgmt` | 🚧 占位 |
| `doctor-assignment-service` | 分配医生（排队、调度、转派） | 核心链路 | 8093 | `shopdb_doctor_assign` | 🚧 占位 |
| `pharmacist-assignment-service` | 分配药师（审方调度、时效统计） | 核心链路 | 8094 | `shopdb_pharmacist_assign` | 🚧 占位 |
| `staff-management-service` | 医生药师管理（档案、资质、入驻审核） | 支撑 | 8095 | `shopdb_staff` | 🚧 占位 |
| `scheduling-service` | 排班（班次、容量、在线判定） | 支撑 | 8096 | `shopdb_scheduling` | 🚧 占位 |
| `salary-service` | 工资结算（接诊/审方/处方/出勤计费） | 支撑 | 8097 | `shopdb_salary` | 🚧 占位 |
| `mall-common` | 共享库（DTO/枚举/异常/埋点） | 基础 | — | — | ✅ 已实现 |

> 占位服务的**具体实现要求**见 [`harness/agents/`](harness/agents/AGENTS.md)：每个服务一份模块约束（规划端点、必守规则、验收口径、DO/DON'T、影响面），实现前必读。

## 领域架构

```
┌─────────────┐   问诊线索    ┌─────────────────────────── 问诊领域（本工程）───────────────────────────┐   处方凭据   ┌──────────┐
│  导购领域    │ ───────────▶ │                                                                  │ ──────────▶ │ 订单领域  │
│ (上游工程)   │  用户+主诉    │  agent-service(:8087)  ← 项目主体：多 Agent 编排，用户侧统一入口          │  处方号+状态  │ (下游工程) │
└─────────────┘  +意向药品    │        │ 汇入问诊线索                                               │  +药品快照   └──────────┘
                              │        ▼                                                         │
                              │  doctor-assignment(:8093) 分配医生 ── 依赖 ──▶ scheduling(:8096) 排班  │
                              │        │ 接诊                                                      │
                              │        ▼                                                         │
                              │  consult-service(:8085/8086) 问诊会话/IM  ──▶ consult-record(:8090)    │
                              │        │ 问诊结束，医生开方                                          │      问诊记录 │
                              │        ▼                                                         │
                              │  prescription(:8091) 开处方 ──校验药品──▶ medicine(:8081) 药品服务      │
                              │        │ 处方进入流转                                               │
                              │        ▼                                                         │
                              │  prescription-management(:8092) 处方管理（状态机）                      │
                              │        │ 审方分配                                                  │
                              │        ▼                                                         │
                              │  pharmacist-assignment(:8094) 分配药师 ── 审方 ──▶ 处方生效           │
                              │                                                                  │
                              │  staff-management(:8095) 医生药师主数据 ◀── 全部人员相关服务依赖      │
                              │  salary(:8097) 工资结算 ◀── 问诊记录/处方/排班 统计                    │
                              └──────────────────────────────────────────────────────────────────┘
```

## 技术栈

- **Spring Boot 3.5.16** · Spring MVC · Spring Data JPA · MySQL 8.4 · Java 25 · Spring AI 1.0.0（DeepSeek + ES RAG）
- **服务间通信**：REST（Spring `RestClient`），错误体统一 `{"message":"..."}`
- **数据库**：每服务独立 schema（微服务最佳实践），JPA `ddl-auto=update` 自动建表

## 如何运行

依赖软件：JDK 25（`D:\jdk25\jdk-25.0.2`）、MySQL 8.4.5（用户 `shop` / 密码 `shop123456`）。

```bash
# 一键启动（MySQL → 建库 → 打包 → 启动三个已实现服务 → 打开浏览器）
双击 run.cmd

# 或手动
D:\MySQL\start-mysql.cmd
mysql -uroot -p < init.sql        # 首次：创建问诊领域全部库并授权
set JAVA_HOME=D:\jdk25\jdk-25.0.2
mvnw.cmd -DskipTests clean package
start "medicine" java -jar medicine-service\target\medicine-service-0.0.1-SNAPSHOT.jar
start "consult"  java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar
start "agent"    java -jar agent-service\target\agent-service-0.0.1-SNAPSHOT.jar
```

> 注意：本机默认 `JAVA_HOME` 是 JDK 1.8，编译/运行必须显式指定 JDK 25（run.cmd 已处理）。
> 占位服务（8090–8097）尚无实现，暂不启动；实现要求见 `harness/agents/`。

## 跨领域边界（红线）

- ❌ 本仓库不实现订单/支付/商品/导购逻辑——分属订单、商品、导购领域的独立工程。
- ✅ 与上游导购领域：只通过**问诊线索接收**（用户 + 主诉 + 意向药品，fail-closed 不完整即拒收）。
- ✅ 与下游订单领域：只通过**处方凭据输出**（处方号 + 状态 + 药品明细快照；处方一次性消费 CAS）。

## 🛠 SDD Harness（规格驱动开发）

产品写 PRD → `/sdd` 转化为逐功能、可测试、可追溯的 **Spec**（`docs/spec/`）→ 代码以 Spec 为验收依据，形成 SDD 闭环。

> 背景：在 vibe coding 提效（30%–50%）背景下，治理**代码质量漂移 / 沟通熵增 / 方案失真**三处暗礁——目标：保留提效收益的同时质量 0 回退、新增代码 100% 带埋点可追溯，让速度和质量重新对齐。详见 [`harness/README.md`](harness/README.md)「项目背景与目的」。

- 框架（管线/映射表/模板/质量门禁/追溯矩阵）：[`harness/`](harness/README.md)
- 模块约束（含 8 个占位服务的实现要求）：[`harness/agents/AGENTS.md`](harness/agents/AGENTS.md)
- 接入说明（文档流位置/使用方式）：[`docs/14-sdd-workflow.md`](docs/14-sdd-workflow.md)
- 真实转化示例：`harness/assets/examples/FR-2-place-order.spec.md`
- 用法：在 Claude Code 中执行 `/sdd`（默认转化 `docs/01-PRD.md`）或 `/sdd FR-2`

## 目录结构

```
0802-4/
├── pom.xml                        # 父聚合 pom（问诊领域全部模块）
├── init.sql                       # 建库脚本（问诊领域 11 个 schema + 授权）
├── run.cmd / start-services.cmd   # 一键启动 / 仅重启已实现服务
├── start-agent.cmd                # 单独启动 agent-service
├── mall-common/                   # 共享库：DTO / BusinessException / 埋点
├── agent-service/                 # 多 Agent 智能体（项目主体，:8087）
├── consult-service/               # 问诊会话/IM/开方 参考实现（:8085/:8086）
├── medicine-service/              # 药品服务（:8081）
├── consult-record-service/        # 问诊记录（占位，:8090）
├── prescription-service/          # 开处方（占位，:8091）
├── prescription-management-service/ # 处方管理（占位，:8092）
├── doctor-assignment-service/     # 分配医生（占位，:8093）
├── pharmacist-assignment-service/ # 分配药师（占位，:8094）
├── staff-management-service/      # 医生药师管理（占位，:8095）
├── scheduling-service/            # 排班（占位，:8096）
├── salary-service/                # 工资结算（占位，:8097）
├── harness/                       # SDD 框架 + 各服务实现要求（agents/）
└── docs/                          # PRD / 设计 / 测试 / trace 文档
```

## 演进路线

1. **当前**：agent-service（主体）+ consult/medicine 存量实现可用；8 个占位服务已完成领域建模与实现要求（harness/agents/）。
2. **第一步**：实现 staff-management 与 scheduling（人员主数据 + 在线判定，其余服务的前置）。
3. **第二步**：实现 doctor-assignment / pharmacist-assignment，接管调度。
4. **第三步**：实现 prescription / prescription-management / consult-record，把 consult-service 的开方与处方消费职责拆出。
5. **第四步**：实现 salary，完成 2B 侧闭环；consult-service 收敛为纯问诊会话/IM。
