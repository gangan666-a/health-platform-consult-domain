# 代码架构规则（C-x）

> 全仓统一的**代码架构 rules**：分层、包结构、Bean 注册、校验/补偿/幂等模式、新服务落地。Spec §13 研发方案逐项引用 C-x；agents/<模块>.md 的「必守规则」也引用这里的编号，**不各自发明模式**。事实来源：`docs/13-code-guide.md`、`docs/trace/02`、当前代码。

## C-1 分层与职责

| 层 | 包（order-service 示例） | 职责 | 纪律 |
| --- | --- | --- | --- |
| 页面控制器 | `web/` | Thymeleaf 页面 + 表单回填 + 302 跳转 | 不写业务逻辑，转发到 Service/Client |
| REST API | `api/` | JSON 端点（`@RestController`） | 统一 `@RequestMapping("/api/…")`；错误经 `@RestControllerAdvice` 转 `ApiError` |
| 客户端 | `client/` | 服务间 RestClient 封装 | 挂 `TraceContext.interceptor()`；错误经 `ClientSupport.toBusinessException` 转换 |
| 业务服务 | `service/` | 编排/事务/补偿 | `@Service`；跨服务动作在此做「先扣后补」与幂等 |
| 仓储 | `repository/` | JPA 数据访问 | 只读/原子 UPDATE 语句集中于此 |
| 模型/表单 | `model/`、`form/` | JPA 实体、表单校验对象 | 实体不直接对外暴露，用 DTO |

- 一个 Controller 职责单一；页面（web）与 JSON（api）分开，不要混在一个类里。

## C-2 包结构与 Bean 注册

- 根包 `com.example.mall.<module>`（如 `orderservice`、`consultservice`）。
- **mall-common 的根包 `com.example.mall.common` 是各服务扫描根包的兄弟包，默认不被组件扫描拾取** → 新增共享 Bean **必须用 `@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册**（不要用 `@Component`）。
- 服务自身 Bean 用 `@Component`/`@Service`/`@Controller` 常规注册。

## C-3 校验（fail-closed）

- **服务端是最终校验**：页面/前端校验只是体验，正确性边界在服务端。
- 表单/请求 DTO 用 Bean Validation（`@Valid` + `@NotBlank`/`@Min`/`@Max`…）；服务端再补显式 `BusinessException` 分支（如价格异常、库存不足）。
- 数值/枚举边界取**最严口径**（如 `quantity ∈ [1,999]`、金额 `BigDecimal` 禁浮点），越界即拒绝。
- 未知输入不猜默认：fail-closed，宁可拒绝也不放行。

## C-4 补偿与幂等模式

| 模式 | 实现 | 示例 |
| --- | --- | --- |
| 幂等键 | 业务唯一键 + DB 唯一索引兜底 | `shop_order.order_no`(UK)，碰撞→补偿+重试提示 |
| 幂等回调 | 状态 CAS 单向流转 | `OrderService.confirmPayment`：仅 `CREATED→PAID` |
| 一次性消费 | 单条 CAS UPDATE（`USED`），防复用 | `PrescriptionService.consume` |
| 失败补偿 | best-effort 回补，不掩盖主因 | `OrderService.restoreStockQuietly` / `ConsultClient.restorePrescription` |

## C-5 错误处理

- 统一错误体 `ApiError {"message":"…"}`（JSON）；API 层 400=业务异常、500=内部错误，**不回显内部信息**。
- 页面层（web）`GlobalExceptionHandler` 渲染 `error.html`。
- 埋点纪律：`TraceAspect` 异常记录后**原样重抛**，业务代码不得吞异常掩盖。

## C-6 新服务/新模块落地 checklist

新增一个服务时，以下全部必须配套，缺一不算完成：

- [ ] 根 `pom.xml` 注册 module；`init.sql` 增加建库 + 授权 `shop` 用户；
- [ ] `application.properties`（端口、数据源 `shopdb_<service>`、`ddl-auto=update`、连接 mall-common）；
- [ ] `@RestControllerAdvice`（JSON 错误体）+ 至少一个真实业务端点（健康检查用）；
- [ ] 若需共享 Bean（DTO/枚举/埋点）→ 放 mall-common 并用 `@AutoConfiguration` 注册；
- [ ] 页面/导航：新入口挂 `order-service/templates/fragments/layout.html`（BFF 唯一入口原则）；
- [ ] 启动脚本：`run.cmd` / `start-services.cmd` / `start-agent.cmd` 纳入新服务；
- [ ] 回归：`./mvnw.cmd clean test` 全绿（clean 失败先结束占用 jar 的 java 进程）。
