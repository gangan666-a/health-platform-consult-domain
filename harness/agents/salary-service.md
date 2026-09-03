# salary-service 模块约束（工资结算 · 占位待实现）

## 1. 定位

**规划端口 8097 · DB `shopdb_salary` · 纯 REST**。职责：按周期结算医生与药师报酬——计费口径（接诊量、审方量、处方量、排班出勤）汇总、结算规则配置、出账与对账、结算单状态管理；数据全部来自问诊领域上游服务的统计接口，本服务**只算钱不管人**。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/SalaryController.java`（`/api/salaries`） | `POST /periods/{period}/calculate`（触发结算）· `GET /periods/{period}/statements?staffId=`（结算单查询）· `POST /statements/{id}/confirm`（确认）· `GET /rules`（规则配置）· `PUT /rules` | 全部 JSON 端点 |
| `service/SettlementService.java` | `calculate` · `confirm` | 结算编排 |
| `service/SettlementRuleEngine.java` | 计费规则求值 | 单价×量 + 出勤系数 |
| `client/ConsultRecordStatClient.java` | 接诊量统计 | ← consult-record-service |
| `client/PrescriptionStatClient.java` | 审方量/处方量统计 | ← prescription-management-service |
| `client/ScheduleStatClient.java` | 出勤统计 | ← scheduling-service |
| `client/StaffClient.java` | 在职人员名单 | ← staff-management-service |
| `model/SalaryStatement.java` / `SalaryStatementLine.java` / `SettlementRule.java` | JPA 实体 | 结算单头（`period`（格式 yyyyMM）/`staffId`/`totalAmount`/`status(GENERATED\|CONFIRMED\|PAID)`）+ 明细行（计费项/数量/单价/小计）+ 规则 |

## 3. 本模块必守规则

- **T-2/T-3**：跨服务统计接口不可用时结算**整体失败**（fail-closed，不得用部分数据出账）；错误体统一 `{"message"}`。
- **T-4**：独立 schema；统计数不入库（每次结算实时拉取），结算单落库的是**快照**（明细行），可复算对账。
- **金额纪律**：全程 `BigDecimal`（12 位精度、2 位小数，HALF_UP）；禁止 double。
- **幂等**：同一 `period + staffId` 只生成一张结算单；重复 `calculate` 返回既有单（已 CONFIRMED/PAID 的周期禁止重算）。
- 状态机单向：`GENERATED → CONFIRMED → PAID`；CONFIRMED 后金额不可变（有误走冲正单，本期不实现，留 OPEN）。
- **C-3**：period 格式校验 `^\d{6}$` 且为已完结周期（未来/当期不可结算）；staffId 必须在职或近一年内离职（离职人员的最后一期仍需结算）。
- **C-5**：500 不回显内部。

## 4. 实现要求（验收口径）

1. `POST /periods/{period}/calculate`：拉取全员统计 → 逐人生成结算单（明细行含计费项、数量、单价、小计）→ 落库；任一统计源失败则整个事务回滚并返回失败来源。
2. 单价规则默认：接诊 ¥15/单、审方 ¥8/张、处方 ¥10/张、出勤系数（出勤率 <80% 总额 ×0.8）；规则可经 `PUT /rules` 配置，改动仅影响后续周期。
3. `confirm` 仅 `GENERATED` 可确认；操作人留痕。
4. `GET /periods/{period}/statements` 支持按 staffId 过滤，分页（默认 0/20）。
5. 对账：结算单金额 = Σ明细行小计（精确校验，分毫不差）。

## 5. DO / DON'T

- ✅ 统计快照落库；✅ BigDecimal；✅ 周期幂等；✅ 状态机单向；✅ fail-closed 出账。
- ❌ 不允许部分数据出账；❌ 不允许改已确认金额；❌ 不允许 double 计价；❌ 不跨库访问。

## 6. 影响面

- 实现后：依赖 consult-record / prescription-mgmt / scheduling / staff 四个上游统计接口（各服务需在实现时同步提供轻量统计端点，接口契约见 module-contracts M-12 依赖规划）。
- 回归：结算幂等用例 + 金额精度用例 + 统计源失败回滚用例。
