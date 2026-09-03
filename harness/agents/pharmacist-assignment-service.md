# pharmacist-assignment-service 模块约束（分配药师 · 占位待实现）

## 1. 定位

**规划端口 8094 · DB `shopdb_pharmacist_assign` · 纯 REST**。职责：处方审方环节的药师调度——处方开具后按「执业药师资质 → 排班在线（scheduling-service）→ 审方负载」把处方分配给合适药师审核；维护审方排队、超时转派与审方时效统计（喂给工资结算）。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/PharmacistAssignmentController.java`（`/api/pharmacist-assignments`） | `POST /enqueue`（处方进入审方分配）· `GET /queues`（审方排队实况）· `POST /assignments/{id}/claim`（药师领取审方）· `POST /assignments/{id}/transfer`（超时转派）· `GET /pharmacists/{pharmacistId}/load` | 全部 JSON 端点 |
| `service/AuditAssignmentService.java` | `enqueue` · `claim` · `transfer` | 调度编排 |
| `client/SchedulingClient.java` / `client/StaffClient.java` | 在线状态 / 药师主数据 | 资质+在线过滤 |
| `model/AuditAssignment.java` | JPA 实体 | 审方分配单：`prescriptionNo`（UK）/`assignedPharmacistId`/`status(QUEUED\|ASSIGNED\|CLAIMED\|TIMEOUT_TRANSFERRED\|DONE)`/`assignedAt`/`claimedAt`/`completedAt` |

## 3. 本模块必守规则

- **T-2**：`claim` 用 CAS（`ASSIGNED → CLAIMED`），两名药师并发领取同一分配单只允许一人成功。
- **T-3**：跨服务统一错误体；scheduling/staff 不可用时处方保持 `QUEUED` 延迟调度（**不得丢处方**——审方是处方生效的法定环节）。
- **T-4**：独立 schema；本服务只存分配关系，处方内容留在 prescription-management，按 `prescriptionNo` 引用。
- **C-3**：入队校验 fail-closed——`prescriptionNo` 必填且在处方管理侧处于 `AUDITING`；否则 400。
- 调度过滤：仅「执业药师资质有效 + 排班在线 + 在职 + 当前审方中 < 上限（默认 10 张）」。
- **超时转派**：`ASSIGNED` 超过阈值（默认 120s）未 claim → 转派下一位；循环 3 轮无人可派 → 分配单置 `QUEUED` 并告警（不丢弃）。
- 审方时效：`claimedAt → completedAt` 计入药师审方时长统计，供 salary-service 查询。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `POST /enqueue` 幂等（同一 `prescriptionNo` 重复入队返回既有分配单）。
2. 分派策略：在线药师中「当前审方负载最少」优先；负载相同按药师 ID 稳定排序（结果可复现）。
3. `claim` CAS：并发领取恰好一人成功，另一人 409。
4. `GET /queues` 返回队列长度、平均审方时长、超时转派次数。
5. 完成回调：药师在 prescription-management 提交审方结论后，分配单置 `DONE`（事件或回调均可，幂等）。

## 5. DO / DON'T

- ✅ 处方不丢；✅ claim CAS；✅ 资质+在线双过滤；✅ 审方时效可统计。
- ❌ 不允许无资质人员被分配；❌ 不允许跳过排班分配离线药师；❌ 不吞异常；❌ 不跨库访问。

## 6. 影响面

- 实现后：prescription-management 的 `AUDITING` 状态由本服务驱动完成分配，审方动作仍提交给 prescription-management（本服务只管「谁来审」不管「审成什么」）。
- 回归：处方状态机用例 + 审方调度专项测试（含超时转派、无人可派告警）。
