# consult-record-service 模块约束（问诊记录 · 占位待实现）

## 1. 定位

**规划端口 8090 · DB `shopdb_consult_record` · 纯 REST，无出站**。职责：问诊全过程留痕的唯一事实源——问诊会话中的每条消息、图片、状态变更（接诊/结束）、开方事件全部落档；记录**只增不改**（append-only），为纠纷仲裁、平台质检、工资结算统计提供取证依据。

## 2. 规划包结构与关键类

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/ConsultRecordController.java`（`/api/consult-records`） | `POST /sessions/{sessionId}/events`（事件落档）· `GET /sessions/{sessionId}`（会话全记录回放，分页）· `GET /users/{userId}/history`（用户问诊历史）· `GET /{recordId}/archive`（归档取证导出） | 全部 JSON 端点 |
| `service/ConsultRecordService.java` | `appendEvent` · `replaySession` · `listUserHistory` | 事件追加 + 查询编排 |
| `model/ConsultRecordEvent.java` | JPA 实体 | 事件：`sessionId / userId / staffId / eventType(MESSAGE\|IMAGE\|STATUS\|PRESCRIPTION) / payload(JSON) / occurredAt` |
| `repository/ConsultRecordEventRepository.java` | 按 sessionId/userId 索引查询 | `(session_id, occurred_at)` 复合索引 |
| `config/RecordProperties.java` | 归档策略配置 | 热数据保留期等 |

## 3. 本模块必守规则

- **T-1/T-4**：独立 schema `shopdb_consult_record`；不跨库访问，跨服务只读走 REST。
- **T-3**：错误体统一 `{"message"}`；`@RestControllerAdvice` 兜底。
- **C-3**：落档校验 fail-closed——sessionId/userId/eventType/payload 缺失即 400 拒收；未知 eventType 拒收。
- **只增不改**：任何端点不得提供更新/删除既有事件的入口；归档导出为只读快照。
- **C-5**：500 不回显内部细节。

## 4. 实现要求（验收口径）

1. `POST /sessions/{sessionId}/events` 幂等键 `eventId`（调用方生成），重复提交返回已落档结果而非重复写入。
2. `GET /sessions/{sessionId}` 按时间正序返回事件流，分页 `page/size`（默认 0/50，size 上限 200）。
3. 事件 `occurredAt` 由服务端统一打点（调用方携带的客户端时间仅存 payload）。
4. 单会话事件量 ≥ 10,000 时回放 P95 < 500ms（索引保障）。

## 5. DO / DON'T

- ✅ append-only；✅ 幂等落档；✅ 服务端时间戳；✅ 错误走 `ApiError`。
- ❌ 不提供事件修改/删除 API；❌ 不吞异常；❌ 不跨库访问；❌ 不在 Controller 写业务规则。

## 6. 影响面

- 实现后：consult-service 的消息落盘（`MessageWriteQueue`）应改为「本地写 + 异步投递本服务」双写，最终以本服务为准。
- 回归：consult-service 全量测试 + GP-IM 链路。
