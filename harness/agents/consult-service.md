# consult-service 模块约束（问诊会话 + 开方 · 存量参考实现）

## 1. 定位

**端口 8085 REST / 8086 Netty WS · DB `shopdb_consult` · 自包含（无出站）**。职责：问诊会话（创建/接诊/结束/消息）、医生开方、处方一次性消费/回补、WebSocket 实时聊天。**WS 事件循环零阻塞**是本模块生命线。目标架构中「问诊记录/开处方/处方管理」将拆分至独立服务（见 `consult-record-service.md` / `prescription-service.md` / `prescription-management-service.md`），拆分完成前由本服务承载存量实现并被 agent-service 依赖。

## 2. 包结构与关键类（入口 file:line，实测 2026-08-24）

| 包/类 | 关键端点/方法 | 说明 |
| --- | --- | --- |
| `controller/ConsultationController.java`（`/api/consult`） | `POST /sessions` :53 · `GET /sessions/{id}` :58 · `POST accept` :64 · `POST end` :72 · `GET messages` :81 · `POST ws-token` :88 · `POST /prescriptions` :94 · `POST /prescriptions/consume` :100 · `POST /prescriptions/{no}/restore` :106 · `GET /prescriptions/{no}` :112 | 全部问诊/处方 JSON 端点 |
| `service/ConsultationSessionService.java` | `createOrReuse:53` · `accept:89` | 同药品会话复用；接诊 CAS `WAITING→ONGOING` |
| `service/PrescriptionService.java` | `prescribe:76` · `consume:143` | 开方同事务置 `PRESCRIPTION_ISSUED`；消费 CAS `USED` 防复用 |
| `service/MessageService.java` | `sendTextMessage:54` | 事件循环内入写队列即返回 |
| `service/SessionLifecycle.java` | `onDisconnect` / `finalizeTerminal` | 会话终态收尾 |
| `netty/WsFrameHandler.java` | `channelRead0`（join/message/prescription/end 分派） | WS 消息入口（traceId=`-` 预期） |
| `batch/MessageWriteQueue.java` | 批量写队列 | 高频落盘合并（事件循环零阻塞关键） |
| `netty/WsNotifier.java` / `WsSessionRegistry.java` | 广播 / 会话注册 | — |
| `netty/OriginCheckHandler.java` | 跨域校验 | — |

## 3. 本模块必守规则

- **T-2（最重要）**：WS 事件循环内**零阻塞**——耗时操作（写库/广播）经 `MessageWriteQueue`/独立执行器，禁止在 `channelRead0` 同步做重 IO。
- T-5：处方 `consume` 单条 CAS UPDATE（`USED`），restore 仅「被该订单消费」可回退；会话状态单向流转。
- T-6：WS 线程无 MDC，traceId=`-` 是设计预期非 bug；REST 段经 TraceFilter 正常带 traceId。
- C-1：Controller 薄，编排在 Service；状态机收尾统一走 `SessionLifecycle.finalizeTerminal`。
- 会话令牌：user/doctor WS token 由网关（原 order BFF，已随订单领域移出本工程）签发，本模块校验后放行；领域工程内调试可直接用 REST 签发端点。

## 4. 新功能落点指南（扩展点）

| 新增类型 | 落点 |
| --- | --- |
| 新 WS 帧类型 | `WsFrameHandler.channelRead0` 分派 + 对应 Service |
| 会话状态扩展 | `SessionLifecycle` + 会话状态枚举（mall-common） |
| 新处方动作 | `PrescriptionService`（同事务 + 终态收尾） |
| 消息能力 | `MessageService`（入队零阻塞）+ `MessageWriteQueue` |
| 聊天增强（表情/转诊） | WS 帧 + 广播 `WsNotifier` |

## 5. DO / DON'T

- ✅ 事件循环零阻塞；✅ 处方消费/接诊用 CAS；✅ 会话终态统一收尾；✅ 错误走 `ApiError`。
- ❌ 不要在 WS 事件循环做同步 DB 写/长循环；❌ 不要吞异常；❌ 不要允许处方重复消费；❌ 不要跨库访问。

## 6. 影响面

- 测试：`consult-service/src/test/...`（116 用例，含会话状态机/处方 CAS/WS）。
- 回归：全量 `clean test` 全绿；起服务后走 GP4（建会话 → WS 聊天 → 接诊 → 开方 → 凭方下单，`X-Trace-Id: gp4-consult`）。
- WS 验证：Node 临时脚本连 `ws://localhost:8086/ws` join/message/ack。
