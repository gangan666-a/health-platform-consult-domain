# 测试计划与报告（IM 问诊 + 处方购药）

## 测试计划（M2 增量）

分层（沿用 M1 基建：JUnit5+Mockito、MockMvc、@SpringBootTest+H2(MySQL mode)、真实 Netty 集成）：

| 层级 | 目标 |
| --- | --- |
| 单元测试 | 会话状态机 CAS、处方校验链/一次性消费/恢复、批量写队列、WsSessionRegistry 隔离/接管/清理、结束判定调度器 |
| 接口测试 | REST 契约（会话/消息/处方）、错误体 `{"message":...}`、fail-closed 参数校验 |
| Netty 集成测试 | **真实启动 Netty（动态高位端口）+ JDK HttpClient WebSocket**：双端聊天、会话隔离、接管重连、结束判定、并发不乱序、并发首接、处方 WS→REST 消费→恢复 |
| order/product 增量 | Rx 下单补偿、页面入口/校验、药品种子 rxFlag |

关键测试配置：H2 覆盖 MySQL；`consult.ws-port`、断线缓冲/空闲超时阈值调小保证确定性；并发断言用严格 seq 递增。

## 测试执行结果

| 模块 | 用例 | 结果 |
| --- | --- | --- |
| product-service | 45 | 0/0（含药品 8 个种子 Rx4/OTC4、rxFlag DTO 透传、老商品默认 OTC） |
| order-service | 105 | 0/0（Rx 补偿三分支、页面入口/校验、转发接口、问诊页渲染） |
| payment-service | 37 | 0/0（未改动） |
| recommend-service | 53 | 0/0（未改动） |
| consult-service | 116 | 0/0（单元 85 + 接口 22 + Netty 集成 9） |
| **合计** | **356** | **0 失败 / 0 错误，BUILD SUCCESS** |

`mvnw.cmd clean test` → `Tests run: 356, Failures: 0, Errors: 0` → **BUILD SUCCESS**

## 测试发现并闭环的 Bug（本迭代）

| 编号 | 级别 | 模块 | 问题 | 修复 |
| --- | --- | --- | --- | --- |
| IM-BUG-1 | **P1** | order/product.html | Rx 药品详情页「立即购买」buy-form 无 `th:if` 守卫，Rx 页同时出现「立即购买」+「在线问诊购药」双入口，违反 AC-D1-3 | buy-form 加 `th:if="${!product.rxFlag}"`，Rx 仅走问诊购药 |
| IM-BUG-2 | **P1** | consult/MessageService | 跨连接消息乱序：seq 在会话锁内分配、`offer()` 在锁外执行，双端并发发送入队顺序偏离 seq，客户端收乱序 | offer 移入锁内 + drain/flush 加 per-session drainLock + insertBatch 按 seq 排序兜底；严格并发断言恢复 |
| IM-BUG-3 | **P2** | consult/配置 | `application.properties` 点分键 `consult.ws.port`/`consult.session.*` 与 `@ConfigurationProperties` 字段绑定不一致，配置为死配置、改值被静默忽略 | 统一平铺 kebab 键（`consult.ws-port` 等），`ConsultPropertiesBindingTest` 改为断言非默认值真实绑定 |
| IM-BUG-4 | **P0** | consult（评审发现，实现已落地） | 静默断线下「每身份在线 1」与「断线 60s 重连」冲突，缓冲期重连被拒（AC-C4-2） | join 接管语义：同身份重复 join 关旧绑新 |
| IM-BUG-5 | **P1** | consult（评审发现，实现已落地） | 处方消费成功但扣库存失败未恢复处方（EX-IM-22） | order 下单补偿先 restore 处方再回补库存（CAS 归属校验，幂等） |
| IM-BUG-6 | **P1** | consult（评审发现） | consume 请求缺 orderNo，与 CAS 契约不符 | ConsumePrescriptionRequest 强制 orderNo |
| IM-BUG-7 | **P1** | consult（评审发现） | 会话注册表空 key 无清理 → 内存无界增长 | 终态 purge 清槽 + 吊销令牌；channel 关闭移反向映射 |
| IM-BUG-8 | **P1** | consult（评审发现） | 批量 INSERT 单行唯一键冲突拖垮整批 + 重试死循环 | INSERT IGNORE 逐行容错，重复 clientMsgId 视为幂等成功 |

## 验收要点验证结论

- **Rx 无处方购买被拒**（fail-closed，缺单号拒绝并回补库存）✅
- **处方一次性消费**（CAS `USED WHERE ACTIVE`，并发两单仅一胜）✅
- **处方过期/药品不匹配/已使用** 均拒绝 ✅
- **会话隔离**（错误 token 拒绝、A 会话消息不进 B、未 join 不收消息）✅
- **会话结束判定**（主动结束 / 断线缓冲 / 空闲超时 / 开方完成；WAITING 不因医生未接入自动结束）✅
- **并发不乱序**（严格 seq 递增）✅
- **P0-01 接管 / P1-01~04** 全部回归通过 ✅

## 遗留已知决策（非阻断）

| 项 | 说明 |
| --- | --- |
| 演示医生身份无鉴权 | 医生控制台 `/doctor` 用固定演示身份，生产需真实医师账号/权限（评审 R-13 裁定演示可接受） |
| WS 跨端口直连 :8086 | 浏览器经 WS 直连 consult-service，REST 走 order-service 转发；生产需网关统一入口 + WSS + Origin 校验 |
| restore 接口无调用方鉴权 | 内部接口，生产需服务间鉴权（评审 R-17 裁定演示可接受，建议收紧） |
| consult 不可用 | Rx 购药 fail-closed 拒绝（不绕过），符合药品合规语义 |
