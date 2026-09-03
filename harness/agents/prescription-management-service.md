# prescription-management-service 模块约束（处方管理 · 占位待实现）

## 1. 定位

**规划端口 8092 · DB `shopdb_prescription_mgmt` · 纯 REST**。职责：处方全生命周期状态机唯一管理者——处方从开处方服务进入流转（待审 → 药师审方 → 生效/驳回 → 被订单消费 → 归档），审方意见留痕、合规审计查询；**处方药购买的唯一合法凭据来源**，是问诊领域向下游订单领域输出的边界服务。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/PrescriptionMgmtController.java`（`/api/prescription-mgmt`） | `GET /{prescriptionNo}`（状态+流转轨迹）· `POST /{prescriptionNo}/audit`（药师审方）· `POST /{prescriptionNo}/consume`（订单凭方消费）· `POST /{prescriptionNo}/restore`（订单取消回补）· `GET /compliance/audit`（合规审计查询） | 全部 JSON 端点 |
| `service/PrescriptionLifecycleService.java` | `submit`（接收开方服务投递）· `audit` · `consume` · `restore` | 状态机编排 |
| `service/AuditTrailService.java` | 流转轨迹与审方意见留痕 | append-only |
| `model/PrescriptionFlow.java` / `AuditOpinion.java` | JPA 实体 | 处方流转单 + 审方意见 |
| `repository/PrescriptionFlowRepository.java` | 单条 CAS UPDATE | `USED` 消费防复用 |

## 3. 本模块必守规则

- **T-5（最重要）**：处方状态机**单向流转**，全部以单条 CAS UPDATE 收口：

  ```
  ISSUED → AUDITING → EFFECTIVE → CONSUMED（终态）
                    ↘ REJECTED（终态，含驳回意见）
  ISSUED → REVOKED（医生撤回，仅审方前）
  EFFECTIVE → EXPIRED（超时未消费，默认 72h，可配置）
  ```

- `consume` 必须带**消费订单号**：仅「被该订单消费」的处方可被该订单 `restore` 回退，防跨订单回补。
- **T-3**：错误体统一 `{"message"}`；重复消费/非法流转 → 409 或 400（见 §4）。
- **T-4**：独立 schema；处方内容以开方服务投递的快照为准，不回查药品库。
- **跨领域边界**：`consume/restore` 是**下游订单领域**的接入点，接口幂等（同一订单号重复消费返回首次结果）。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `POST /{prescriptionNo}/consume`：仅 `EFFECTIVE` 可消费；CAS 成功置 `CONSUMED` 并记录订单号；重复同订单号请求幂等返回成功；其他状态 → 409 `{"message":"处方不可消费（当前状态 X）"}`。
2. `POST /{prescriptionNo}/audit`：仅 `AUDITING` 可审；驳回必须附意见（≤500 字）并置 `REJECTED`；通过置 `EFFECTIVE`。审方意见与操作药师 ID 留痕不可改。
3. 过期任务：`EFFECTIVE` 超过有效期（默认 72h）自动置 `EXPIRED`（@Scheduled 扫描，幂等）。
4. `GET /{prescriptionNo}` 返回状态 + 完整流转轨迹（每个状态变更：操作人/时间/来源）。

## 5. DO / DON'T

- ✅ 状态机 CAS 收口；✅ 消费绑定订单号；✅ 审方留痕 append-only；✅ 跨领域接口幂等。
- ❌ 不允许双向跳转/回退状态；❌ 不允许无订单号的匿名消费；❌ 不允许修改审方意见；❌ 不跨库访问。

## 6. 影响面

- 实现后：consult-service 的 `PrescriptionService.consume/restore` 职责迁入本服务；下游订单领域凭处方号对接（原 order 侧 `ConsultClient.consumePrescription` 语义不变）。
- 回归：处方 CAS 全部用例（复用 consult-service 既有测试语义）+ 跨订单回补拒绝用例。
