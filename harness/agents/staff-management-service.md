# staff-management-service 模块约束（医生药师管理 · 占位待实现）

## 1. 定位

**规划端口 8095 · DB `shopdb_staff` · 纯 REST，无出站**。职责：医生与药师的**人员主数据**——执业资质证照、科室与职称、入驻审核流程、在职状态生命周期管理；被问诊领域全部人员相关服务（分配医生/分配药师/排班/工资结算/开处方）依赖。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/StaffController.java`（`/api/staffs`） | `POST /applications`（入驻申请）· `POST /applications/{id}/review`（平台审核）· `GET /{staffId}`（档案）· `PUT /{staffId}/status`（状态变更）· `GET /search?role=&department=&status=` | 全部 JSON 端点 |
| `service/StaffApplicationService.java` | `apply` · `review` | 入驻审核流 |
| `service/StaffProfileService.java` | `getProfile` · `changeStatus` · `search` | 档案与状态 |
| `model/Staff.java` / `StaffApplication.java` / `Qualification.java` | JPA 实体 | 人员档案（`staffId`（UK）/`role(DOCTOR\|PHARMACIST)`/`name`/`department`/`title`/`status`）+ 申请单 + 资质证照（`type`/`licenseNo`（UK）/`validUntil`） |
| `repository/StaffRepository.java` | 多条件查询 | role/department/status 索引 |

## 3. 本模块必守规则

- **T-1/T-4**：独立 schema `shopdb_staff`；无出站调用。
- **T-3**：错误体统一 `{"message"}`。
- **C-3 fail-closed（本模块生命线）**：
  - 入驻申请必须含有效执业资质（医师执业证/药师资格证），证照编号、有效期缺失 → 400 拒收；
  - 审核通过才创建 `staffId`（`status=ACTIVE`）；驳回必须附意见；
  - 资质过期的在职人员自动置 `SUSPENDED`（每日扫描），恢复需换证重审。
- 状态机单向：`PENDING_REVIEW → ACTIVE ⇄ SUSPENDED → RESIGNED`（离职终态，不可复活，重入职走新档案）。
- 主数据一致性：`licenseNo` 全局唯一（UK）；同一人不可同时是医生与药师（`role` 唯一）。
- **C-5**：500 不回显内部；证照原件信息只存编号与有效期，不存图片二进制（存对象存储引用，本服务只管 URL）。

## 4. 实现要求（验收口径）

1. `POST /applications` 幂等（同一 `licenseNo` 未审结的申请重复提交返回既有申请单）。
2. `review` 仅平台管理员角色可调（服务端校验调用方身份头，缺省拒绝）；通过/驳回均留痕（审核人、时间、意见）。
3. `PUT /{staffId}/status` 仅允许合法流转（见 §3 状态机），非法流转 → 409。
4. `GET /search` 支持 role/department/status 组合过滤，分页（默认 0/20，上限 100）。
5. 每日资质扫描幂等：重复执行不产生重复状态变更记录。

## 5. DO / DON'T

- ✅ 无资质不上岗（fail-closed）；✅ 证照号 UK；✅ 状态机单向；✅ 审核留痕。
- ❌ 不允许跳过审核直接创建在职人员；❌ 不允许复活离职档案；❌ 不在库里存证照图片；❌ 不跨库访问。

## 6. 影响面

- 实现后：doctor-assignment / pharmacist-assignment / scheduling / salary / prescription 均以本服务 `staffId` 为人员唯一标识；consult-service 医生身份校验切换到本服务（原内置医生表迁移）。
- 回归：主数据查询接口 + 状态机用例 + 资质过期扫描用例。
