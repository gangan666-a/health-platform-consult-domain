# scheduling-service 模块约束（排班 · 占位待实现）

## 1. 定位

**规划端口 8096 · DB `shopdb_scheduling` · 纯 REST**。职责：医生与药师的班次规划与发布（班次模板、周期排班、节假日特殊班）、可约容量计算（每班次最大接诊/审方量）、调班与停诊管理；是问诊领域「**谁在线可调度**」的唯一事实源，被分配医生/分配药师/工资结算依赖。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/ScheduleController.java`（`/api/schedules`） | `POST /shift-templates`（班次模板）· `POST /plans`（发布周期排班）· `GET /staffs/{staffId}?date=`（个人班表）· `GET /on-duty?role=&department=&at=`（某时刻在线人员）· `POST /plans/{id}/adjust`（调班/停诊） | 全部 JSON 端点 |
| `service/SchedulePlanService.java` | `publishPlan` · `adjust` · `getStaffSchedule` | 排班发布与调整 |
| `service/OnDutyQueryService.java` | `getOnDuty` | 在线判定（含缓冲期，见 §3） |
| `service/CapacityService.java` | 班次容量与余量计算 | 接诊/审方容量 |
| `client/StaffClient.java` | `getStaff` | 校验人员在职 |
| `model/ShiftTemplate.java` / `SchedulePlan.java` / `ScheduleEntry.java` | JPA 实体 | 模板（`name`/`startTime`/`endTime`）+ 周期计划 + 排班条目（`staffId`/`date`/`shiftTemplateId`/`capacity`/`status(NORMAL\|ADJUSTED\|CANCELLED)`） |

## 3. 本模块必守规则

- **唯一约束**：同一 `staffId + date + shiftTemplateId` 只能有一条有效排班（UK）；同一人同日两个班次时间重叠 → 400。
- **T-3**：跨服务统一错误体；staff 不可用时排班发布 fail-closed 拒绝（不得为未知/离职人员排班）。
- **T-4**：独立 schema；不跨库访问。
- **C-3**：发布校验 fail-closed——班次模板存在、人员在职（staff-management）、日期为未来、容量 ∈ [1,50]；任一不满足 → 400。
- **在线判定含缓冲**：`on-duty` 判定 = 当前时刻 ∈ [startTime, endTime)，并支持班次前后缓冲（默认 15min，可配置）供交接；停诊条目（`CANCELLED`）即时生效并从在线集合剔除。
- **可约容量**：班次容量 − 已占用（由分配服务回调上报），余量为负按 0 返回。
- 调班留痕：每次 adjust 记录变更前后（操作人/时间/原因），append-only。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `POST /plans` 一次发布一个周期（≤ 31 天）内多名人员的批量条目；单条非法**整批拒绝**（事务回滚），错误指明首条非法条目。
2. `GET /on-duty?role=&department=&at=` 返回该时刻在线人员集合（含 staffId/剩余容量），P95 < 100ms（索引：date + status）。
3. 停诊即时生效：`adjust` 为 CANCELLED 后，`on-duty` 立即不含该人员（无需等待缓存过期，若引入缓存须主动失效）。
4. 个人班表按日期范围查询，返回模板时间与状态。
5. 历史排班不可修改（只能新增调整条目），保证工资结算出勤统计可回溯。

## 5. DO / DON'T

- ✅ 在线判定唯一事实源；✅ 批量发布事务性；✅ 调班留痕；✅ 停诊即时生效。
- ❌ 不允许为离职人员排班；❌ 不允许同日重叠班次；❌ 不允许修改历史排班；❌ 不跨库访问。

## 6. 影响面

- 实现后：doctor-assignment / pharmacist-assignment 的「在线过滤」统一走本服务 `on-duty`；salary-service 出勤口径以本服务历史排班为准。
- 回归：排班发布/调整用例 + 在线判定性能用例。
