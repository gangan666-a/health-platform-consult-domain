# 模块/业务域契约（M-x）

> 每个业务域的**职责、端口、DB、包结构、关键类、对外端点、依赖、黄金链路指针**。Spec §2.5 标注本 Feature 触及哪些 M-x；`agents/<module>.md` 提供模块粒度更细的约束与落点指南。
> 本工程为**医疗买药问诊平台的问诊领域**：上游导购领域（问诊线索）、下游订单领域（处方凭据）。已实现模块的入口行号以当前代码为准（`grep` 实测 2026-08-24）；占位服务的端点为**规划契约**，实现时按对应 `agents/<service>.md` 落地。

## M-1 mall-common（共享库）

- **职责**：全部 DTO/枚举、`BusinessException`、`ApiError`、**动态埋点**（`TraceFilter/TraceAspect/TraceContext/TraceAutoConfiguration`）。被所有服务 compile 依赖。
- **包**：`com.example.mall.common.{model,exception,trace}`。
- **注册**：共享 Bean 一律 `@AutoConfiguration` + `AutoConfiguration.imports`（兄弟包不扫描，见 C-2）。
- **黄金链路角色**：埋点贯穿全部链路（X-Trace-Id 传播）。

## M-2 agent-service（多 Agent 编排 + RAG + 报告解读 · 项目主体）

- **端口** 8087 · **DB** `shopdb_agent`（报告记录/指标/健康档案；会话内存态）· REST。
- **端点**（`controller/AgentController.java`，前缀 `/api/agents`）：`POST /chat` :29、`GET /registry` :34。`controller/ReportController.java`（前缀 `/api/agents/report`）：`POST /upload`（multipart 报告上传）、`GET /{userId}/compare?indicator=`（同指标多日期对比）。
- **关键类**：`orchestration/AgentOrchestrator.chat:36`（Safety 前置→Supervisor/Handoff）、`SupervisorOrchestrator`（Manager + `@Tool` 委派）、`HandoffOrchestrator`（Sub Agent 续聊/handoff）、`AgentSessionStore`（内存会话）、`orchestration/tools/*`（各 `@Tool`）、`agent/SubAgentRegistry` + `agent/specialist/*`（Consultation/MedicineQa/ShoppingGuide/Science/Report/Safety 6 个）、`rag/KnowledgeRetrievalService`（ES 检索，失败降级空）、`report/*`（报告解析/对比/健康档案）。
- **编排**：混合策略——Safety 前置 → 有 active Sub Agent 走 Handoff → 否则 Manager Supervisor 路由；无 LLM Key 时关键词启发式路由 + 占位回复。
- **依赖**：→ medicine(:8081)、consult(:8085)；→ 外部 DeepSeek + ES；→ MySQL `shopdb_agent`。
- **注意**：Agent 专属的「导购/问药」Sub Agent 是问诊领域的用户侧入口（上游导购领域的线索可经此汇入），但其商品推荐能力仅限药品目录（medicine-service），不越界到商品领域。

## M-3 consult-service（问诊会话 + 开方 · 存量参考实现）

- **端口** 8085 REST / 8086 Netty WS · **DB** `shopdb_consult` · 自包含（无出站）。
- **端点**（`controller/ConsultationController.java`，前缀 `/api/consult`）：`POST /sessions` :53、`GET /sessions/{id}` :58、`POST /sessions/{id}/accept` :64、`POST /sessions/{id}/end` :72、`GET /sessions/{id}/messages` :81、`POST /sessions/{id}/ws-token` :88、`POST /prescriptions` :94、`POST /prescriptions/consume` :100、`POST /prescriptions/{no}/restore` :106、`GET /prescriptions/{no}` :112。
- **关键类**：`ConsultationSessionService.createOrReuse:53 / accept:89`、`PrescriptionService.prescribe:76 / consume:143`（CAS USED）、`service/SessionLifecycle`、`netty/WsFrameHandler:47`（join/message/prescription/end 分派）、`batch/MessageWriteQueue`（事件循环零阻塞）、`netty/WsNotifier`（广播）。
- **纪律**：WS 事件循环零阻塞；traceId=`-`（预期）；会话令牌经 BFF 签发。
- **演进定位**：目标架构中，「问诊记录」拆至 `consult-record-service`（M-5）、「开处方」拆至 `prescription-service`（M-6）、处方流转交 `prescription-management-service`（M-7）；拆分完成前由本服务承载存量实现并被 agent-service 依赖。

## M-4 medicine-service（药品目录 · 问诊领域支撑）

- **端口** 8081 · **DB** `shopdb_medicine` · 纯 REST。
- **端点**（`controller/MedicineController.java`，前缀 `/api/medicines`）：`GET` :24、`GET /{id}` :29、`POST /{id}/stock/deduct` :34、`POST /{id}/stock/restore` :39。
- **关键类**：`MedicineService.deductStock:33 / restoreStock:47`、`MedicineRepository`（原子 `UPDATE … WHERE stock>=?`）、`model/Medicine`（含 `rxFlag` Rx 处方药标记）、`config/DataInitializer`（10 条 Rx+OTC 种子，按 name 去重幂等）。
- **依赖**：无出站。被 agent-service 调用；处方药语义（`rxFlag=true` 须凭处方购买）是问诊领域→订单领域的关键衔接点。
- **边界**：本服务只管**药品**；非药品商品属商品领域工程，不在本仓库。

---

## 以下为问诊领域占位服务的**规划契约**（实现要求详见 `harness/agents/<service>.md`）

## M-5 consult-record-service（问诊记录 · 核心链路 · 占位）

- **规划端口** 8090 · **DB** `shopdb_consult_record` · 纯 REST。
- **规划端点**（前缀 `/api/consult-records`）：`POST /sessions/{sessionId}/events`（问诊事件落档：消息/图片/状态变更/开方事件）、`GET /sessions/{sessionId}`（会话全记录回放）、`GET /users/{userId}/history`（用户问诊历史，分页）、`GET /{recordId}/archive`（归档取证导出）。
- **职责**：问诊全过程留痕的唯一事实源；记录不可篡改（只增不改）；为纠纷仲裁、平台质检、工资结算统计提供依据。
- **依赖规划**：被 consult-record 上游（问诊会话/IM）写入；被 salary-service、平台质检读取。无出站。

## M-6 prescription-service（开处方 · 核心链路 · 占位）

- **规划端口** 8091 · **DB** `shopdb_prescription` · 纯 REST。
- **规划端点**（前缀 `/api/prescriptions`）：`POST /issue`（医生开方：诊断 + 药品明细 + 用法用量）、`GET /{prescriptionNo}`、`GET /sessions/{sessionId}`（按会话查处方）、`POST /{prescriptionNo}/revoke`（医生撤回未流转处方）。
- **职责**：医生在线开方的唯一入口；开方前置校验（医生在职且已接诊该会话、药品存在且为 Rx、用法用量完整）；开方成功即产出处方号并推入处方管理流转。
- **依赖规划**：→ medicine-service（校验药品）、staff-management-service（校验医生资质）；→ prescription-management-service（处方进入流转）。被问诊会话触发。

## M-7 prescription-management-service（处方管理 · 支撑 · 占位）

- **规划端口** 8092 · **DB** `shopdb_prescription_mgmt` · 纯 REST。
- **规划端点**（前缀 `/api/prescription-mgmt`）：`GET /{prescriptionNo}`（处方状态与流转轨迹）、`POST /{prescriptionNo}/audit`（药师审方：通过/驳回 + 意见）、`POST /{prescriptionNo}/consume`（订单凭方消费，CAS 一次性）、`POST /{prescriptionNo}/restore`（订单取消回补）、`GET /compliance/audit`（合规审计查询）。
- **职责**：处方全生命周期状态机（`ISSUED → AUDITING → EFFECTIVE → CONSUMED / REJECTED / REVOKED / EXPIRED`）唯一管理者；审方意见留痕；处方药购买的唯一合法凭据来源（向下游订单领域输出）。
- **依赖规划**：被 prescription-service 写入、pharmacist-assignment-service 驱动审方、下游订单领域消费；无出站。

## M-8 doctor-assignment-service（分配医生 · 核心链路 · 占位）

- **规划端口** 8093 · **DB** `shopdb_doctor_assign` · 纯 REST。
- **规划端点**（前缀 `/api/doctor-assignments`）：`POST /enqueue`（问诊请求进入分配：科室/主诉/线索来源）、`GET /queues/{department}`（科室排队实况）、`POST /assignments/{id}/accept`（医生接诊）、`POST /assignments/{id}/transfer`（超时/请假转派）、`GET /doctors/{doctorId}/load`（当前负载）。
- **职责**：按「科室匹配 → 排班在线（依赖 scheduling-service）→ 负载均衡」调度；维护排队队列与超时转派；支持指定医生与紧急插队。
- **依赖规划**：→ scheduling-service（在线状态）、staff-management-service（医生主数据）；被问诊会话创建流程调用。

## M-9 pharmacist-assignment-service（分配药师 · 核心链路 · 占位）

- **规划端口** 8094 · **DB** `shopdb_pharmacist_assign` · 纯 REST。
- **规划端点**（前缀 `/api/pharmacist-assignments`）：`POST /enqueue`（处方进入审方分配）、`GET /queues`（审方排队实况）、`POST /assignments/{id}/claim`（药师领取审方）、`POST /assignments/{id}/transfer`（超时转派）、`GET /pharmacists/{pharmacistId}/load`。
- **职责**：处方审方环节的药师调度——按「执业药师资质 → 排班在线 → 审方负载」分配；审方时效统计（喂给工资结算）。
- **依赖规划**：→ scheduling-service、staff-management-service；被 prescription-management-service 审方流转调用。

## M-10 staff-management-service（医生药师管理 · 支撑 · 占位）

- **规划端口** 8095 · **DB** `shopdb_staff` · 纯 REST。
- **规划端点**（前缀 `/api/staffs`）：`POST /applications`（入驻申请：资质证照）、`POST /applications/{id}/review`（平台审核）、`GET /{staffId}`（档案）、`PUT /{staffId}/status`（在职/停诊/离职）、`GET /search?role=&department=&status=`（主数据查询）。
- **职责**：医生与药师的人员主数据（档案/资质/科室/职称/状态生命周期）；入驻审核 fail-closed（无有效执业资质不得上岗）。
- **依赖规划**：无出站。被 doctor/pharmacist-assignment、scheduling、salary、prescription 等问诊领域全部人员相关服务依赖。

## M-11 scheduling-service（排班 · 支撑 · 占位）

- **规划端口** 8096 · **DB** `shopdb_scheduling` · 纯 REST。
- **规划端点**（前缀 `/api/schedules`）：`POST /shift-templates`（班次模板）、`POST /plans`（发布周期排班）、`GET /staffs/{staffId}?date=`（人员班表）、`GET /on-duty?role=&department=&at=`（某时刻在线可调度人员）、`POST /plans/{id}/adjust`（调班/停诊）。
- **职责**：班次规划与发布、可约容量（每班次最大接诊/审方量）、调班停诊；「谁在线可调度」的唯一事实源。
- **依赖规划**：→ staff-management-service（人员主数据）；被 doctor/pharmacist-assignment、salary 依赖。

## M-12 salary-service（工资结算 · 支撑 · 占位）

- **规划端口** 8097 · **DB** `shopdb_salary` · 纯 REST。
- **规划端点**（前缀 `/api/salaries`）：`POST /periods/{period}/calculate`（触发周期结算）、`GET /periods/{period}/statements?staffId=`（结算单查询）、`POST /statements/{id}/confirm`（确认）、`GET /rules`（计费规则配置）。
- **职责**：按接诊量/审方量/处方量/排班出勤结算医生药师报酬；结算单状态机（`GENERATED → CONFIRMED → PAID`）；对账留痕。
- **依赖规划**：读 consult-record、prescription-mgmt、scheduling 的统计接口；无写出站。

## 服务依赖矩阵（REST / WS，含规划）

| 上游 \ 下游 | medicine | consult | agent | consult-record | prescription | presc-mgmt | doctor-assign | pharmacist-assign | staff | scheduling | salary | DeepSeek/ES(外部) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **导购领域（上游，跨工程）** | — | — | ✅ 问诊线索汇入 | — | — | — | ✅ 线索触发的排队 | — | — | — | — | — |
| **agent-service** | ✅ | ✅ | — | — | — | — | — | — | — | — | — | ✅ |
| **consult-service** | — | — | — | ✅（规划：事件落档） | ✅（规划：开方拆出） | ✅（规划：流转交管） | ✅（规划：创建会话时排队） | — | — | — | — | — |
| **prescription-service** | ✅ 校验药品 | — | — | — | — | ✅ 进入流转 | — | — | ✅ 校验医生 | — | — | — |
| **prescription-mgmt** | — | — | — | — | — | — | — | ✅ 驱动审方分配 | — | — | — | — |
| **doctor-assignment** | — | — | — | — | — | — | — | — | ✅ 主数据 | ✅ 在线状态 | — | — |
| **pharmacist-assignment** | — | — | — | — | — | — | — | — | ✅ 主数据 | ✅ 在线状态 | — | — |
| **scheduling** | — | — | — | — | — | — | — | — | ✅ 主数据 | — | — | — |
| **salary** | — | — | — | ✅ 统计 | — | ✅ 统计 | — | — | ✅ 主数据 | ✅ 出勤 | — | — |
| **订单领域（下游，跨工程）** | — | — | — | — | — | ✅ 凭方消费 | — | — | — | — | — | — |

> 要点：问诊领域对外仅两个跨领域接口——**上游问诊线索接收**（agent / doctor-assignment）与**下游处方凭据输出**（prescription-mgmt 的 consume/restore）；领域内人员相关服务共享 staff（主数据）与 scheduling（在线状态）。

## 黄金链路（改造定位必读证据）

| 链路 | 路径 | 状态 | 一句话 |
| --- | --- | --- | --- |
| GP-A 问诊主链路（目标） | 导购线索 → doctor-assignment 排队接诊 → consult 会话/IM → consult-record 留痕 → prescription 开方 → pharmacist-assignment 审方 → presc-mgmt 生效 →（下游订单凭方购药） | 🚧 规划 | 问诊领域端到端核心链路 |
| GP-B 结算链路（目标） | consult-record / presc-mgmt / scheduling 统计 → salary 周期结算 | 🚧 规划 | 2B 侧闭环 |
| GP-IM 问诊+处方购药（存量） | order→consult 会话/开方/处方消费（原 GP4，order 已移出本工程，仅 consult 侧保留实现） | ✅ 存量参考 | consult-service 现有实现验证 |
