# 测试计划（Test Plan）

> 依据《01-PRD.md》验收标准与《02-solution-design.md》测试策略制定。
> 原则：**测试防止 happy path** —— 每个正常用例必配边界/异常/并发/幂等/降级用例。

## 测试分层

| 层级 | 技术 | 目标 |
| --- | --- | --- |
| 单元测试 | JUnit5 + Mockito（MockitoExtension，JDK25 用 mock-maker-subclass） | Service 层业务逻辑、状态机、金额精度、幂等 |
| 接口测试 | Spring MockMvc（@WebMvcTest + @MockBean） | REST 契约、错误体格式、参数校验、状态码 |
| 功能测试 | @SpringBootTest + MockMvc + H2（MySQL 兼容模式）+ @MockBean 下游客户端 | 跨层完整链路、降级、并发、幂等 |

## 测试基础设施（测试专用，非生产）

- 各服务 `src/test/resources/application.properties`：H2 内存库覆盖 MySQL（`MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`、H2Dialect、`ddl-auto=create-drop`）
- `pom.xml` 增加 test 依赖：`spring-boot-starter-test`、`com.h2database:h2`
- `order-service` 下游客户端（Product/Payment/RecommendClient）在功能测试用 `@MockBean` 替换，避免真实网络
- `recommend-service` surefire 增加 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`（跨盘符 classpath 兼容）

## 各模块用例规模与覆盖重点

| 模块 | 文件 | 用例数 | 非 happy-path 重点 |
| --- | --- | --- | --- |
| product | ProductServiceTest / ProductControllerTest / ProductFunctionalTest | 39 | 库存不足、quantity≤0/非数字、价格≤0、并发扣减到 0、缺参、不存在 |
| order | OrderServiceTest / OrderApiControllerTest / ShopControllerTest / OrderFunctionalTest | 70 | 金额精度 0.1×3、库存不足补偿回补、取消回补、幂等回调、非法状态流转、推荐超时降级、表单校验、非 PAID 访问支付页 |
| payment | PaymentServiceTest / PaymentControllerTest / PaymentFunctionalTest | 36 | **FAILED 重试复用行**、已支付重复 400、并发首付单成功、卡号脱敏、金额≤0/null、非法流转、回调失败→FAILED |
| recommend | RecommendationServiceTest / RuleRecommendationServiceTest / AiRecommendationServiceTest / RecommendationControllerTest | 53 | AI 幻觉过滤链（目录外/已购/重复/异品类）、非法 JSON/空 content、无 Key/超时、topN 截断、降级回退、规则补足去重 |

合计：约 198 用例。

## 测试执行与缺陷闭环

1. 全量执行：`mvnw.cmd clean test`
2. 失败用例按「测试自身错误 / 真实生产 bug」归类
3. 真实生产 bug → 记录到 `05-test-report.md` → 分配给对应模块开发修复 → 回归重跑
4. 全部测试通过 → 上线放行（Phase 7）

## 已知问题登记（待修复）

见《05-test-report.md》Bug 清单。
