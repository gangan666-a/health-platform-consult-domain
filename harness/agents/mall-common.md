# mall-common 模块约束（共享库）

## 1. 定位

**无端口 · 无 DB · 被全部服务 compile 依赖**。职责：DTO/枚举、`BusinessException`、`ApiError`、**动态埋点**（TraceFilter/TraceAspect/TraceContext/TraceAutoConfiguration）。改这里 = 影响所有服务，改动前必须评估波及面。

## 2. 包结构与关键类

| 包/类 | 说明 |
| --- | --- |
| `common/model/` | 全部 DTO 与枚举（仅问诊领域 + Agent）：`AgentChatRequest`（sessionId/message/targetAgent）、`AgentChatResponse`（sessionId/activeAgent/content/orchestrationMode/handoff*/agentTrace/blocked）、`AgentType`（MANAGER + 6 专业，`isSpecialist()`）、`MedicineDTO`（含 rxFlag）、问诊/处方相关 `ConsultSessionDTO`、`ChatMessageDTO`、`PrescriptionDTO`/`PrescriptionItem`/`WritePrescriptionRequest`/`ConsumePrescriptionRequest`/`RestorePrescriptionRequest`、状态枚举 `ConsultationStatus`/`PrescriptionStatus`/`MessageSenderType` |
| `common/exception/BusinessException.java` | 业务异常（仅 RuntimeException + message） |
| `common/model/ApiError.java` | 统一错误体 `{"message":"…"}` |
| `common/trace/TraceContext.java` | MDC 助手 + `interceptor()` 出站传播 `X-Trace-Id` |
| `common/trace/TraceFilter.java` | 入站 `OncePerRequestFilter`：解析 `X-Trace-Id` → MDC |
| `common/trace/TraceAspect.java` | AOP 切 `@RestController`/`@Service`，`[TRACE]` ENTER/EXIT/ERROR，异常原样重抛 |
| `common/trace/TraceAutoConfiguration.java` | `@AutoConfiguration` 注册 Filter + Aspect |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 一行 FQN，靠它被各服务加载 |

## 3. 本模块必守规则

- **C-2（最重要）**：mall-common 根包 `com.example.mall.common` 是各服务扫描根包的**兄弟包**，默认不被组件扫描拾取 → 新增共享 Bean **必须 `@AutoConfiguration` + imports 注册**，禁止 `@Component`。
- T-6：埋点零侵入——改动 TraceAspect 不得吞业务异常；WS 线程 traceId=`-` 是预期。
- 枚举/常量语义（`AgentType`、状态机）在此定义，服务只引用不改写。

## 4. 新功能落点指南（扩展点）

| 新增类型 | 落点 |
| --- | --- |
| 新 DTO | `common/model/` 加类；服务间传输用 DTO，不暴露 JPA 实体 |
| 新枚举 | `common/model/`（如状态机枚举） |
| 新共享能力 | 建包 + `@AutoConfiguration` + imports 注册；不要用 `@Component` |
| 新异常/错误体 | 扩 `BusinessException`/`ApiError`（保持 `{"message"}` 契约） |

## 5. DO / DON'T

- ✅ 跨服务共享模型放这里；✅ 改 DTO 前 grep 所有引用方（consult/medicine/agent 及问诊领域各服务）；✅ 新共享 Bean 走 AutoConfiguration。
- ❌ 不要 `@Component`（不生效）；❌ 不要在埋点切面吞异常；❌ 不要放业务逻辑/事务（无数据库）。

## 6. 影响面

- 波及全部服务（compile 依赖）；DTO 字段变更会触发所有下游编译。
- 测试：`mall-common/src/test/.../trace/TraceContextTest`、`TraceFilterTest`（8 用例）。
- 回归：`./mvnw.cmd clean test` 全绿；埋点为纯日志增强，不应改变任何业务行为。
