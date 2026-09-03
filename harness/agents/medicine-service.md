# medicine-service 模块约束（药品目录）

## 1. 定位

**端口 8081 · DB `shopdb_medicine` · 纯 REST**。职责：药品列表/详情/原子扣减/回补库存，10 条 Rx+OTC 种子数据（**Rx 示例 id=32「阿莫西林胶囊」**）。本服务是问诊领域九大服务中「药品服务」的已实现载体；通用商品（非药品）属商品领域工程，已随本仓库聚焦问诊领域而移除（原 product-service）。

## 2. 包结构与关键类（入口 file:line，实测 2026-08-24）

| 类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/MedicineController.java`（`/api/medicines`） | `GET` :24 · `GET /{id}` :29 · `POST /{id}/stock/deduct` :34 · `POST /{id}/stock/restore` :39 | 纯 REST |
| `service/MedicineService.java` | `deductStock:33` · `restoreStock:47` | 业务校验 + 仓储 |
| `repository/MedicineRepository.java` | 原子 `UPDATE … WHERE stock>=?` | **并发正确性唯一边界** |
| `model/Medicine.java` | JPA 实体，含 `rxFlag` | `rxFlag=true` = 处方药，须处方购药 |
| `config/DataInitializer.java` | CommandLineRunner 10 条，按 name 去重幂等 | 药品品类种子 |

## 3. 本模块必守规则

- T-1/T-4：独立 schema `shopdb_medicine`；无出站调用。
- T-3：错误体统一 `{"message"}`；`@RestControllerAdvice` 兜底。
- T-5：扣减单条原子 UPDATE，失败→400「库存不足」。
- C-3：校验 fail-closed（数量边界、药品存在、价格非空）；Rx 药品语义由下游订单领域凭处方消费（无处方号拒购，处方凭据来自本领域 prescription-management-service）。
- C-5：500 不回显内部。

## 4. 新功能落点指南（扩展点）

| 新增类型 | 落点 |
| --- | --- |
| 新药品/品类 | `model/Medicine.java`（字段，如 `category`）+ DTO + `DataInitializer` 种子 |
| 新库存动作 | `MedicineService` + `MedicineRepository`（原子 SQL）+ Controller 端点 |
| 药品维度查询 | `MedicineController` 加端点（如按品类/是否 Rx） |
| 处方药规则 | 由下游订单领域凭处方消费（fail-closed：无处方号拒购；处方管理服务实现后由其统一供凭据） |

## 5. DO / DON'T

- ✅ 扣减必须原子 SQL；✅ 错误走 `ApiError`；✅ 种子数据幂等。
- ❌ 不要「查再改」两步扣减；❌ 不要跨库访问；❌ 不要在 Controller 写业务规则。

## 6. 影响面

- 测试：`medicine-service/src/test/...`（含扣减/回补用例）。
- 回归：全量 `clean test` 全绿；起服务后走药品列表/详情/下单 GP1、Rx GP4。
- 注意：本模块 4 个端点是问诊领域内的药品契约；prescription-service（开方校验药品）、agent-service、下游订单领域均按此对接，改路径必须同步各调用方。
