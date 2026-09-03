# prescription-service 模块约束（开处方 · 占位待实现）

## 1. 定位

**规划端口 8091 · DB `shopdb_prescription` · 纯 REST**。职责：医生在线开方的唯一入口——医生在问诊会话中提交处方（诊断结论 + 药品明细 + 用法用量），服务做前置校验后产出处方号并推入处方管理（prescription-management-service）流转。从 consult-service 的 `PrescriptionService` 演化拆出。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/PrescriptionIssueController.java`（`/api/prescriptions`） | `POST /issue`（开方）· `GET /{prescriptionNo}` · `GET /sessions/{sessionId}`（按会话查）· `POST /{prescriptionNo}/revoke`（撤回未流转处方） | 全部 JSON 端点 |
| `service/PrescriptionIssueService.java` | `issue` · `revoke` | 开方编排：校验 → 落库 → 通知处方管理 |
| `client/MedicineClient.java` | `getMedicine` | 校验药品存在且 `rxFlag=true` |
| `client/StaffClient.java` | `getStaff` | 校验医生在职且有执业资质 |
| `client/PrescriptionMgmtClient.java` | `submit` | 处方进入流转（审方） |
| `model/Prescription.java` / `PrescriptionItem.java` | JPA 实体 | 处方头（`prescriptionNo`（UK）/`sessionId`/`doctorId`/`diagnosis`/`status`）+ 明细（`medicineId`/`medicineName` 快照/`quantity`/`dosage` 用法用量） |

## 3. 本模块必守规则

- **T-2/T-3**：跨服务调用统一错误体 `{"message"}`，下游不可用时 fail-closed 拒绝开方（不得跳过校验降级开方）。
- **T-4**：独立 schema；药品信息以**快照**存入处方明细（名称/价格/规格），不跨库 JOIN。
- **C-3**：开方前置校验 fail-closed——① 医生在职且有执业资质（staff-management）；② 处方绑定会话且医生已接诊该会话（consult 会话状态 `ONGOING`）；③ 每条明细药品存在、`rxFlag=true`、`quantity ∈ [1,999]`、用法用量非空。任一不满足 → 400。
- **T-5**：开方与「通知处方管理」非同一事务：本地落库成功后投递流转；投递失败处方置 `PENDING_SUBMIT`，由补偿任务重试（best-effort，不掩盖主因）。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `prescriptionNo` 全局唯一（建议 `RX` + yyyyMMdd + 序号/雪花）；同一会话可开多张处方。
2. 撤回仅允许处方尚处 `ISSUED`（未进入审方流转）时发起；撤回后处方号作废不可复用。
3. 处方明细数量 1~20 条；诊断结论 ≤ 500 字。
4. 下游校验（medicine/staff）失败的具体原因回传给医生（错误 message 指明哪条明细/哪项资质不合规）。

## 5. DO / DON'T

- ✅ 前置校验 fail-closed；✅ 药品明细快照；✅ 处方号 UK；✅ 错误走 `ApiError`。
- ❌ 不允许未接诊开方；❌ 不允许 OTC 药品混入 Rx 处方；❌ 不跳过资质校验降级；❌ 不跨库访问。

## 6. 影响面

- 实现后：consult-service 的 `PrescriptionService.prescribe/consume/restore` 开方职责迁入本服务，处方消费/回补职责归 prescription-management-service；consult 侧保留兼容转发期。
- 回归：consult-service 处方相关测试（116 用例中的处方 CAS 组）+ GP-IM 链路。
