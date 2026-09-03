# doctor-assignment-service 模块约束（分配医生 · 占位待实现）

## 1. 定位

**规划端口 8093 · DB `shopdb_doctor_assign` · 纯 REST**。职责：问诊请求的医生调度——按「科室匹配 → 排班在线（scheduling-service）→ 负载均衡」把问诊请求分配给合适医生；维护排队队列、超时转派、指定医生与紧急插队。**问诊领域核心链路的第一个环节**（上游导购领域的问诊线索经此进入医疗服务）。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/DoctorAssignmentController.java`（`/api/doctor-assignments`） | `POST /enqueue`（问诊请求入队）· `GET /queues/{department}`（排队实况）· `POST /assignments/{id}/accept`（医生接诊）· `POST /assignments/{id}/transfer`（转派）· `GET /doctors/{doctorId}/load`（负载） | 全部 JSON 端点 |
| `service/AssignmentService.java` | `enqueue` · `assign`（调度核心）· `accept` · `transfer` | 调度策略编排 |
| `service/LoadBalancer.java` | 科室内在线医生负载排序 | 最少会话优先 |
| `client/SchedulingClient.java` | `getOnDuty(department, at)` | 在线可调度医生 |
| `client/StaffClient.java` | `getStaff` | 医生主数据与科室 |
| `model/AssignmentOrder.java` | JPA 实体 | 分配单：`queueNo`（UK）/`department`/`patientId`/`chiefComplaint`（主诉）/`assignedDoctorId`/`status`/`source`（线索来源：GUIDE \| SELF） |

## 3. 本模块必守规则

- **T-2**：接诊 `accept` 用 CAS（`ASSIGNED → ACCEPTED`），并发抢同一分配单只允许一人成功。
- **T-3**：跨服务调用统一错误体；scheduling/staff 不可用时**入队成功但标记 `PENDING_SCHEDULE`**，调度延迟重试（不得丢线索）。
- **T-4**：独立 schema；不跨库访问。
- **C-3**：入队校验 fail-closed——`patientId`、`department`、`chiefComplaint` 必填；上游导购线索缺失主诉 → 400 拒收并回传缺失字段（供上游补齐后重推）。
- 排队公平：同科室 FIFO；紧急标记（`urgent=true`）插队到队首；指定医生（`preferredDoctorId`）走该医生专属队列，医生停诊时降级普通队列并提示。
- **超时转派**：分配单 `ASSIGNED` 后超过阈值（默认 60s，可配置）未被接诊 → 自动 `transfer` 重新调度（重试 3 次后置 `FAILED` 通知患者）。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `POST /enqueue` 返回 `queueNo` 与预计等待人数；同患者同科室存在未关闭分配单时复用（幂等键 `patientId+department`）。
2. 调度只选「排班在线 + 在职 + 当前会话数 < 上限（默认 5）」的医生；无可调度医生 → 保持排队，不报错。
3. 接诊 CAS：两个医生并发 accept 同一分配单，恰好一人成功、另一人 409。
4. `GET /queues/{department}` 返回队列长度、各状态计数、平均等待时长（滑动窗口）。
5. 超时转派任务幂等（@Scheduled 扫描，重复触发不产生双重分配）。

## 5. DO / DON'T

- ✅ 线索不丢（下游不可用降级入队）；✅ 接诊 CAS；✅ FIFO+紧急插队；✅ 错误走 `ApiError`。
- ❌ 不允许无主诉入队；❌ 不允许跨过排班直接分配离线医生；❌ 不吞调度异常；❌ 不跨库访问。

## 6. 影响面

- 实现后：consult-service 的 `ConsultationSessionService.createOrReuse` 创建会话前先经本服务排队分配（原「建会话即等待医生」语义升级为显式调度）；上游导购领域线索接入点由本服务 `enqueue` 承载。
- 回归：consult-service 会话状态机用例 + 排队/转派专项测试。
