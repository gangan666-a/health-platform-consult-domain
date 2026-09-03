# Feature Spec 模板（14 章节）

> 用法：复制本文件为 `docs/spec/<FR>-<slug>.spec.md`，按注释替换内容。`（ref: ...）` 必须保留可追溯引用。模板随框架维护，改动需同步 `prd-to-spec.md` 映射表。
>
> 对齐范式：`harness-code-quality`（可追溯、自包含、无歧义、fail-closed）+ `harness-architecture`（一 Feature 一文件 + 索引）+ `harness-safety`（fail-closed）。
>
> 规则事实底座（本 Spec 引用它们，不各自发明）：
> - 术语对齐：`harness/references/glossary.md`
> - 技术架构规则（T-x）：`harness/references/tech-rules.md`
> - 代码架构规则（C-x）：`harness/references/code-rules.md`
> - 模块/业务域契约（M-x）：`harness/references/module-contracts.md`
> - 模块约束（DO/DON'T）：`harness/agents/<module>.md`

---

# Spec：<Feature 名>（<FR-x>）

| 项 | 内容 |
| --- | --- |
| Feature | <FR-x> <一句话> |
| PRD 版本 | docs/01-PRD.md v<版本> |
| Spec 状态 | DRAFT / REVIEWED / IMPLEMENTED / VERIFIED |
| 负责人 | 研发 / 测试 |
| 关联 Spec | <本 Feature 依赖/被依赖的其他 Spec> |
| 覆盖 PRD 元素 | FR-x, AC-FR?-*, EX-*, NFR-* |
| 触及业务域 | <M-x 列表，与 §2.5 一致> |

---

## 0. 术语对齐（概念层对齐）

> 查 `harness/references/glossary.md`。本 Feature 用到的每个关键业务/技术概念都必须在此映射；glossary 查不到的新词 → 登记 glossary「待确认」区或记 §12 OPEN，**不得凭空造词**。

| Spec 用词 | 统一术语（glossary） | PRD 术语 | 代码符号 | 模块 |
| --- | --- | --- | --- | --- |
| <Spec 里用的词> | <统一术语> | <PRD 原文措辞> | <类.方法 / 字段> | <M-x> |

## 1. 需求来源

- PRD 原文要点摘录 + 位置（`（ref: FR-x 需求描述）`）。
- 非目标红线（`（ref: NG-x）`）：本 Spec **不得**实现以下行为。
- 用户角色与权限边界（`（ref: §7）`）。

## 2. 功能规格

| 编号 | 类型 | 描述 | 追溯 |
| --- | --- | --- | --- |
| R-1 | 输入 | <输入字段、类型、边界> | `（ref: FR-x 需求描述）` |
| R-2 | 输出 | <输出结构、状态、副作用> | `（ref: FR-x 需求描述）` |
| R-3 | 业务规则 | <规则 1：…> | `（ref: FR-x / NFR-x）` |
| R-4 | 前置条件 | <必须满足，否则行为 X> | `（ref: FR-x 前置条件）` |
| R-5 | 后置条件 | <成功后的可观察状态> | `（ref: FR-x 后置条件）` |
| R-6 | 权限边界 | <谁可做 / 边界在哪层判定（服务端/前端）> | `（ref: §7 权限）` |

> 规则必须无歧义、可测试：有明确数字、明确分支、明确异常行为。写「合理」= 不过门禁。

## 2.5 业务域与适用规则

**触及业务域**（查 `harness/references/module-contracts.md`）：<M-x 模块名（端口/DB）>、<…>。

**适用技术架构规则**（查 `harness/references/tech-rules.md`，逐条给理由）：
- `T-x` <规则一句话> —— 理由：<为什么本 Feature 必须守它，如「跨服务调用 → 必须统一错误体」>
- …

**适用代码架构规则**（查 `harness/references/code-rules.md`，逐条给理由）：
- `C-x` <规则一句话> —— 理由：<…>
- …

> 纪律：本 Feature 涉及的任何中间件/通信/一致性/校验模式，只能引用这里的 T-x/C-x；**出现这里没有的模式 = 需先扩展 rules，不得在 Spec 里自造**。

## 3. 流程规格

**主流程步骤**（编号 P-x，标注跨服务调用点）：

1. P-1 <步骤> → 调用 `POST /api/products/{id}/stock/deduct`（`（ref: FR-x 主流程步骤 2）`）
2. P-2 …

**时序**（如适用，用 ASCII 图；来源 `（ref: SD-02 §5.x）`）：
```
调用方  服务A  服务B
  │──▶│─▶│─▶
```

## 3.5 改造定位（Change Localization）

> 结合 GitNexus 静态图谱 + 动态代码埋点 trace，把「新增功能落在哪个类、哪个方法、哪个插入点」钉死。禁止「based on findings」式把落点推给实现阶段。

**证据来源**（必须标注，可多选）：
- 静态调用链：`docs/trace/01-static-knowledge-graph.md` §6.x（file:line）
- 动态 trace：`docs/trace/03-dynamic-traces.md` §x（traceId=…，跨服务实测）
- 代码阅读：`grep` / `gitnexus context <symbol>` 实测（file:line）

**现状调用链**（本 Feature 相关主路径，含 file:line）：
```
<入口 Controller> → <Service> → <Client> → <下游 Controller/Service>
```

**待改造清单**：

| 动作 | 类.方法 | 位置（file:line / 插入点） | 变更前后对比 | 必须保持的不变量 |
| --- | --- | --- | --- | --- |
| 新增 | <Class.method> | <file:line，插入在哪个方法段之后/之前> | 前：…；后：… | <先扣后补顺序 / BigDecimal / UK / CAS…> |
| 修改 | … | … | … | … |
| 删除 | … | … | … | … |

**新增方法签名草案**（如适用）：`public <Ret> <method>(<args>)`

## 4. 接口契约

| 方向 | 方法 | 路径 | 请求 | 响应 | 错误码 | 幂等 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 对外（Web/BFF） | POST | <路径> | <schema> | <schema> | 400… | ⭐/❌ | `（ref: FR-x / SD-02 §3.x）` |
| 内部（服务间） | POST | <路径> | <schema> | <schema> | 400… | ⭐/❌ | `（ref: FR-x / SD-02 §3.x）` |

> 请求/响应 schema 用 JSON 示例；错误码语义见 §7；错误体统一 `ApiError {"message"}`（T-3）。

## 5. 数据模型

| 表 | 列 | 类型 | 约束/索引 | 说明 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| <table> | <col> | <type> | UK/IDX/NOT NULL | <语义，含快照/幂等键说明> | `（ref: SD-02 §4.x）` |

> 本 Feature 只写**涉及**的表；跨表/跨库只读通过 REST，不跨库 JOIN（T-4）。

## 6. 状态机

**涉及状态**：<只列本 Feature 触碰的状态>。
合法流转表：

| 当前状态 | 动作 | 目标状态 | 允许 | 说明 |
| --- | --- | --- | --- | --- |
| … | … | … | ✓/✗ | … |

（来源 `（ref: PRD §14.1 / SD-02 §6）`）

## 7. 异常规格

| 编号 | 异常场景（EX） | 触发条件 | 期望行为 | HTTP | message | 降级/兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E-1 | <EX-x 场景> | <条件> | <行为> | 400/500 | <文案> | <兜底> | `（ref: EX-x）` |

> 每个 EX 至少对应一条可测试断言（见 §10）。

## 8. 边界与校验规则

| 编号 | 约束 | 值/语义 | fail-closed 说明 | 追溯 |
| --- | --- | --- | --- | --- |
| B-1 | 数值边界 | 如 `quantity ∈ [1,999]` | 默认最严口径 | `（ref: FR-x / NFR-x）` |
| B-2 | 并发边界 | 如「原子扣减为唯一边界」 | 页面校验仅为提示 | `（ref: NFR-6）` |
| B-3 | 默认值 | 如「缺省 type=complement」 | 显式声明，不隐式 | `（ref: FR-x 现状差异）` |

## 9. 事务与补偿边界

| 编号 | 跨服务动作 | 主路径 | 失败补偿 | 兜底 | 追溯 |
| --- | --- | --- | --- | --- | --- |
| T-1 | 扣库存→落订单 | 先扣后落 | 落库失败→回补库存 | 补偿失败→人工对账 | `（ref: SD-02 §8 / EX-19）` |
| T-2 | 幂等 | … | … | … | `（ref: NFR-7/8）` |

> 补偿语义：best-effort，**不抛出掩盖主因**（T-5），仅记 ERROR 日志留下可对账痕迹。

## 10. 可测试验收

| 编号 | 验收（Given-When-Then） | 来源 AC | 状态 |
| --- | --- | --- | --- |
| T-1 | Given …；When …；Then … | AC-FR2-1 | ☐ |
| T-2 | … | 新增（补 AC 未覆盖的可测断言，须附理由） | ☐ |

> 规则：AC 原样映射 + 补足「研发可测断言」；每条 EX 至少一条；每条 NFR 至少一条可量化验收。

## 11. 测试映射

| 测试层次 | 用例 | 覆盖 Spec 项 | 受影响既有用例 | 追溯 |
| --- | --- | --- | --- | --- |
| 单元（service） | <类>.<方法> → <断言> | R-3 / E-1 | <本次改动会波及的旧用例，如 `OrderServiceTest.createOrder` 系列> | `（ref: SD-02 §10.1）` |
| 接口（MockMvc/REST） | <路径> → <状态码/body> | R-x / E-x / §4 | … | `（ref: SD-02 §10.2）` |
| 集成（跨服务） | <端到端场景> | §3 / T-x | … | `（ref: SD-02 §10.3）` |

> 「受影响既有用例」由 §3.5 改造定位推导：改到哪个类/方法，就把该类的既有测试列入回归。

## 12. 待澄清项（OPEN）

| 编号 | 来源位置 | 问题 | 澄清需求 | 状态 |
| --- | --- | --- | --- | --- |
| OPEN-x | <PRD 位置> | <歧义/缺失> | <要产品拍板的事> | ☐ 待澄清 |

> **纪律**：OPEN 未清零前，本 Spec 不得进入 REVIEWED。清零后在状态行更新状态。

## 13. 研发方案（Implementation Plan）

> 研发落地清单。每一项必须能定位到文件/方法，并引用规则与模块约束；禁止「我们加个 X」式无落点描述。
> **实现注记**：本清单由 `/implement` 执行——Step 2 逐条复核 file:line 与不变量（静态 gitnexus/grep + 动态 docs/trace/03，行号以实测为准）；落地后回写本清单（勾选）+ 更新 `docs/spec/SPEC-INDEX.md` 状态 → `IMPLEMENTED`；新建 Spring `RestClient`（`RestClient.builder()/create()`）必须挂 `TraceContext.interceptor()`（T-6，受 `.claude/hooks/check-instrumentation.py` PostToolUse 门禁，ES 客户端豁免）。

**分文件改造清单**：

| # | 文件 | 改动（新增/修改/删除 + 一句话） | 引用规则 | 模块约束 |
| --- | --- | --- | --- | --- |
| 1 | <相对路径/Class> | <改动内容> | C-x / T-x | `harness/agents/<module>.md` DO/DON'T |
| 2 | … | … | … | … |

**分层改动点**（勾选本次涉及）：web（页面/表单）□ · api（JSON）□ · service（编排/事务）□ · client（下游）□ · repository（SQL）□ · model/DTO □

**受影响既有用例**：<列出 §11 里会回归的旧用例组>

**验证方式**：起服务后带 `X-Trace-Id: <id>` 走黄金链路 <GPx>，按 traceId 串日志验证改动生效（T-6）；另跑受影响单测/接口测。

---

## 转化自检（对应 spec-quality-gates.md）

- [ ] 双向可追溯：每条 Spec 项带 ref，可从 PRD 反向定位
- [ ] 可测试性：每条规则/异常可转成断言（§10 全绿）
- [ ] 无歧义：不含「可能/待定/视情况」
- [ ] 覆盖完备：FR×AC×EX 全覆盖（追溯矩阵对齐）
- [ ] 单一事实来源：与方案/代码现状无矛盾
- [ ] 自包含：文件+章节+验证方式齐全
- [ ] **术语对齐**：§0 用词均可在 `harness/references/glossary.md` 解析（或已扩展 glossary）
- [ ] **改造定位可执行**：§3.5 每个改动点都能定位到 file:line，不变量明确
- [ ] **规则引用齐全**：§2.5/§13 触及模块的必守 T-x/C-x 均已引用，无自造中间件/模式
