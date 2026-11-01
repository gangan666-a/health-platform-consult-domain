# Harness 工具接口（Tool Interface）

> 定位：harness 在「分析 → 设计 → 实现」各阶段调用哪些外部工具、精确命令、输出 → 去向与降级。单一事实源——`/sdd` 与 `/implement` 都按此文档执行。
> 对齐范式：`harness-tool-system`（工具=纯声明+执行器）+ `harness-code-quality`（证据可追溯、fail-closed）。

## 1. 工具清单

| 工具 | 精确命令 | 阶段 | 输出 → 去向 | 不可用/未命中降级 |
| --- | --- | --- | --- | --- |
| GitNexus | `gitnexus status`（索引状态）<br>`gitnexus analyze --index-only --skip-agents-md`（重建索引，见 docs/trace/01 §7）<br>`gitnexus context <裸符号名>`（360° 视图，JSON）<br>`gitnexus query "<概念>"`（概念搜，FTS 降级） | /sdd Step 0/3a；/implement Step 0/2 | `symbol.filePath/startLine` + `incoming/outgoing.calls[].filePath` → §3.5 现状调用链 + 待改造清单「证据来源」列 | 未装/索引过期/not found → grep/Glob 实测，证据标「代码阅读（grep 实测）」 |
| grep / Glob | `grep -rn "<符号>" <module>/src` | 改造定位；/implement 复验 | 精确 file:line | 基座，无降级 |
| python（埋点 hook） | `python .claude/hooks/check-instrumentation.py` | 代码生成门禁（PostToolUse，matcher Write\|Edit\|MultiEdit） | T-6 合规校验；exit 0=pass / 2=block（fail-closed，stdout 回喂模型自纠） | hook 基建故障 → exit 0 不阻断会话；手工复跑：`--check <file>` |
| python（模型同步 hook） | `python .claude/hooks/sync-llm-model.py`（+ `--check` / `--render`） | 模型配置门禁 + 渲染（PostToolUse 命中 `.claude/llm-switch.json`；SessionStart 对齐） | T-8 合规校验 + 幂等渲染 `agent-service/src/main/resources/llm-models.properties`；exit 0=pass / 2=block | 校验失败 exit 2 阻断（fail-closed）；SessionStart 仅对齐不阻断；手工复跑：`--check` |
| Maven | `./mvnw.cmd -pl <module> -am compile`<br>`./mvnw.cmd -pl <module> test -Dtest=<Class>` | /implement Step 5 | 编译/单测结果 | 不起微服务；`clean` 前先结束占用 jar 的 java 进程 |
| curl / python（动态 trace） | 见 docs/trace/03 §1/§2：`-H "X-Trace-Id: <id>"` | /implement 可选验证；测试阶段 | traceId 串联日志 | 需起服务；默认留到测试阶段 |

## 2. gitnexus context 输出解析（JSON）

实测字段（`gitnexus context OrderService`，2026-08-24）：

```json
{
  "status": "found",
  "symbol": {
    "uid": "Class:order-service/src/main/java/com/example/mall/orderservice/service/OrderService.java:OrderService",
    "name": "OrderService",
    "kind": "Class",
    "filePath": "order-service/src/main/java/com/example/mall/orderservice/service/OrderService.java",
    "startLine": 33,
    "endLine": 327
  },
  "incoming": {
    "calls": [ { "uid": "Method:.../OrderServiceTest.java:OrderServiceTest.setUp#0", "name": "setUp", "filePath": ".../OrderServiceTest.java" } ],
    "imports": [ { "uid": "File:.../ShopControllerTest.java", "name": "ShopControllerTest.java", "filePath": "..." } ]
  }
}
```

- `status`：`found`（唯一命中）/ `ambiguous`（多候选，按 uid/kind 消歧）/ `error`（含 `{"error":"Symbol not found"}`）。
- 规则：**全限定名常 not found**（索引可能过期，如 2026-08-12 重建）→ 用裸符号名 + 按 uid/kind 消歧；`status:error` → 走降级。
- 重建索引：`GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 GITNEXUS_LBUG_EXTENSION_INSTALL=load-only gitnexus analyze --index-only --skip-agents-md`（docs/trace/01 §7，本地可重建，`.gitnexus/` 已 gitignore）。

## 3. 证据引用规范（Spec §3.5 引用格式）

每条改动点「证据来源」标：`gitnexus context <Class.method>` / `grep <符号> <file>:<line>` / `docs/trace/01 §6.x` / `docs/trace/03 §GPx (traceId=…)`。

优先级：**gitnexus/grep 实测 > trace 文档**（trace 行号可能过期，以实测为准）。

## 4. fail-closed 纪律

拿不出落点（无 file:line）→ 记 OPEN / 回代码核证，禁止凭印象写（与 `/sdd` 一致）。hook 判定只有 `RestClient.builder()/create()` 且无 `TraceContext.interceptor()` 才阻断；只注入/消费 Builder Bean 的客户端、Elasticsearch 客户端（`org.elasticsearch.client.RestClient` / `co.elastic.clients`）均豁免。
