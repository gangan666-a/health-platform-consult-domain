# 上线就绪报告（Release：药品品类 + IM 问诊 + 处方购药）

## 结论

**可上线（Go-Live Ready）** —— 按大厂流程完整走完：PRD → 方案设计 → 方案评审（有条件通过）→ 实现 → 测试（含真实 Netty 集成）→ Bug 闭环 → **356 用例全绿**。

- 全量测试：`mvnw.cmd clean test` → **356 / 0 失败 / 0 错误，BUILD SUCCESS**
- 评审 P0-01 + P1-01~04 已在实现落地；测试又抓出并修复 3 个真实 bug（IM-BUG-1~3）
- 无未决 P0/P1

## 本迭代交付

| 阶段 | 产出 | 位置 |
| --- | --- | --- |
| PRD | 药品 Rx 标记、IM 问诊、处方购药、会话结束判定；52 验收 + 28 异常 | `docs/07-PRD-IM.md` |
| 方案设计 | consult-service 架构、会话隔离、并发控制、会话结束判定、处方安全 | `docs/08-design-IM.md` |
| 方案评审 | 有条件通过：1 P0 + 4 P1 + P2 | `docs/09-review-IM.md` |
| 实现 | 新 consult-service + order/product 集成 + mall-common | 各服务 src/main |
| 测试 | 新增 143 用例（consult 116 + order/product 26），累计 356 | 各服务 src/test |
| 测试报告 | Bug 闭环 + 验收验证 | `docs/10-testplan-IM.md` |

## 新增能力

- **药品品类**：商品 `rxFlag`（处方药标记，fail-closed 默认 OTC）；种子 8 个药品（Rx：阿莫西林胶囊/盐酸左氧氟沙星片/硝苯地平缓释片/布洛芬缓释胶囊；OTC：感冒灵颗粒/维生素C片/蒙脱石散/板蓝根颗粒）
- **consult-service（:8085 REST / :8086 Netty WS，shopdb_consult）**：
  - Netty 嵌入 WebSocket：NioEventLoopGroup + HttpServerCodec→Aggregator→WebSocketServerProtocolHandler→IdleStateHandler→自定义 FrameHandler
  - **会话隔离**：token 签发校验 + 三张 ConcurrentHashMap 注册表 + 每会话 2 连接 + fail-closed 入站校验；join 接管语义（P0-01）
  - **并发控制**：事件循环零阻塞、消息 seq + clientMsgId 幂等、状态 CAS、批量写队列（drain 合并 INSERT + INSERT IGNORE 逐行容错 + flush 背压）
  - **会话结束判定**：主动结束 / 断线 60s 缓冲 / 空闲 30min 超时 / 开方完成，调度器兜底，WAITING 不误判
  - **处方**：医生开方（越权拒绝）→ ACTIVE → USED/EXPIRED/REVOKED；消费 fail-closed 链 + CAS 一次性 + 下单失败 restore 补偿
- **order-service**：问诊聊天页（WS 直连 :8086 + 断线重连 + 心跳）、医生控制台 `/doctor`（接单/回复/开方）、Rx 详情页仅问诊购药入口、确认订单处方号必填校验、下单 Rx 补偿时序（扣库存→消费处方→落单；失败先 restore 处方再回补库存）
- **订单流程**：Rx 药品无处方不可下单（fail-closed）；处方一次性消费；OTC 走原流程

## 上线检查清单

- [x] 全量编译通过
- [x] 全量测试通过（356/356）
- [x] 评审阻断项关闭（P0-01 接管、P1-01 处方恢复、P1-02 orderNo、P1-03 注册表清理、P1-04 批量容错）
- [x] 测试抓出 bug 全部闭环（Rx 双入口、跨连接乱序、配置键）
- [x] 会话隔离/并发/结束判定/处方一次性 均有测试固化
- [ ] 启动顺序：新增 consult-service（REST :8085 + WS :8086）；浏览器从 order-service :8082 进入，问诊页 WS 直连 :8086
- [ ] `init.sql` 已含 shopdb_consult 建库授权（首次需执行或 DDL 自动建表）
- [ ] 演示医生固定身份；生产需真实医师账号 + WSS + 网关统一入口 + 服务间鉴权（restore 接口）

## 遗留风险（生产已知，非本期阻断）

1. **演示身份无鉴权**：医生控制台与内部接口（restore）无鉴权，生产必须收敛（评审 R-13/R-17）。
2. **WS 跨端口直连**：生产建议网关统一 WSS + Origin 校验 + 单域名收敛。
3. **分布式事务**：处方消费与订单落库跨服务，靠「消费 + 补偿 restore + 幂等 CAS」最终一致；生产如需严格一致需 Saga/Outbox。
4. **消息可靠性**：当前为内存 seq + 批量落库，极端崩溃可能丢最后未 flush 消息；生产建议引入可靠消息/已读回执。
5. **性能预期**：单会话消息写路径批量合并（阈值 20 条/1s），事件循环不落库；高并发（万级在线）需压测连接数与 GC。

## 发布建议

- 先发 product（种子药品）→ consult（新服务）→ order（页面/流程）→ payment/recommend 不受影响。
- CI 先 `clean` 再 `test`。
- 上线监控：问诊会话创建/转化率、处方消费成功率、处方复用率（目标 0）、WS 断线重连率、消息延迟。
