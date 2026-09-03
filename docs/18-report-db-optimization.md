# 报告解读 Agent 数据库设计优化分析（v3 路线图）

> 关联：`docs/16-PRD-REPORT.md`、`docs/spec/report-interpretation.spec.md`、`docs/15-agent-architecture.md`
> 输入：`GraphRAG_医药健康报告解读_完整教程与项目设计_人类可读版.html` + agent-service 现状代码
> 定位：回答「第一版只有 ES 向量库，第二版加了 Neo4j 图库，下一版怎么继续优化」

---

## 一、现状盘点（v1 → v2 你已经走过的路）

| 版本 | 存储/检索能力 | 核心代码 | 解决的能力边界 |
| --- | --- | --- | --- |
| **v1（ES）** | 10 个知识索引（alias + backing index）+ `HybridRetrievalService`（BM25 + kNN 向量 + 客户端 RRF）+ `RelationLookupService`（`knowledge_relation` 逐指标查表） | `es/*`、`rag/*` | 「逐指标并集」：每个异常指标各自查一次关联疾病，**不做跨指标联合排序** |
| **v2（+Neo4j）** | `(Indicator)-[ABNORMAL_HIGH/LOW]->(Disease)-[:TREATED_AT]->(Department)` + `HAS_SYMPTOM`；一条 Cypher 完成「多指标命中排序 + 科室/症状多跳」 | `graph/*`、`GraphEvalTest`（accuracy@1 证明图优于表） | 「多指标联合推理」：`count(DISTINCT i) AS hits ORDER BY hits DESC` |

**这一步步进是对的**——它精确对应 GraphRAG 教程里「关系，而非品牌」和「普通 RAG → GraphRAG」的差异：从「搜相似片段」到「沿着关系组织证据」。

但对照教程的**九个核心要点**和**八条工程约束**，v2 的图还停在「演示级共现图」：边只有方向、没有出处/版本/条件，排序靠「投票」而不是「规则」，检索只有一条 Cypher，输出只有「支持证据」没有「冲突/缺失证据」。

---

## 二、核心差距（对照教程，落到你的具体类）

| # | 教程原则 | 你当前的差距 | 落到哪个类 |
| --- | --- | --- | --- |
| 1 | **出处是一等属性** | 种子数据无来源/证据等级/版本，图推理结果 `DiseaseInference` 无 provenance | `MedicalKnowledgeSeeds`、`GraphQueryPort.DiseaseInference` |
| 2 | **Rule-first（规则引擎做判断，图只组织证据）** | `hits` 排序是「朴素投票」，不是临床组合规则（K-of-N/排除/时间窗）；会把「ALT↑+AST↑」简单判成「脂肪肝 hits=2」 | `Neo4jGraphQueryPort` 的 `INFER_CYPHER` |
| 3 | **Schema 先于规模** | 图无唯一约束/索引/遍历超时/跳数上限；`available()` 用 `MATCH (n) count(n)` 全图扫描探活 | `GraphSeedRunner`、`Neo4jGraphQueryPort.available()` |
| 4 | **同时召回支持/反向/适用/缺失证据** | 只输出「疑似疾病+科室+症状」，全部是支持方向，缺反向证据与「还需什么才能区分」 | `GraphKnowledgeService.buildAbnormalContext` |
| 5 | **图组织，原文证明** | 图推理结果无原文回链，LLM 无法溯源 | 同上 + `formatGraph` |
| 6 | **路由胜过全用** | 只有一条固定 Local 查询，无 Basic/Local/Global/DRIFT 路由 | `GraphQueryPort`（单方法） |
| 7 | **实体消歧决定上限** | 消歧只有约 40 个字符串别名，无 LOINC 等受控编码 | `IndicatorNormalizer` + `indicator-aliases.json` |
| 8 | **知识主本 + 只读投影** | 知识源头是 Java 硬编码种子，双写 ES/Neo4j，无主本、无发布快照、无回滚 | `MedicalKnowledgeSeeds`（Java 常量） |
| 9 | **不可变工件（可复现/可回滚）** | 有 `file_hash`/`response_snapshot`（幂等），但没记录「这次用了哪个图快照/规则版本/知识版本」 | `ReportRecord.response_snapshot` |
| 10 | **物理隔离（患者事实 vs 共享知识）** | 患者事实在 MySQL，图只存共享知识——方向对，但缺「查询时投影 + 概念 ID 对齐」的显式契约 | `GraphKnowledgeService`（UNWIND 传参） |

---

## 三、v3 路线图（分优先级，克制演进）

> 教程反复强调两条纪律：**①「GraphRAG 是中间层，不是总控」；②「物理落地不要一开始过度设计」。**
> 所以 v3 的目标不是「再上一个引擎」，而是「把现有图从共现投票升级为有出处、有版本、有条件的可治理知识」，并按价值密度分批落地。

### P0 —— ✅ 已完成（2026-09-02 落地，代码已编译 + 单测通过）

**1. 图 Schema 治理 + 探活修正**
- 为 `Indicator.name` / `Disease.name` / `Department.name` 声明唯一约束 + 索引（MERGE 才有语义保证，也防图大了慢查询）。
- `Neo4jGraphQueryPort.available()` 的 `MATCH (n) RETURN count(n)` 改为 `RETURN 1`（与 `GraphSeedRunner` 一致），避免全图扫描；探活结果做短缓存 + 连接池，别每次 `buildAbnormalContext` 都探活一次再查一次（两次往返）。
- Cypher 加跳数上限与超时（`OPTIONAL MATCH` 扩展科室/症状时限定一跳），关系类型白名单固化。

**2. 关系条件化 + 出处 + 版本（把图从「演示」变「可用」）**
- `SeedEdge` 升级为带 `source / evidenceGrade / population / validFrom / version` 的边属性；边类型从「方向」升级为「有条件的声明」。
- `DiseaseInference` 增加 `provenance`（来源 + 证据等级 + 版本），`formatGraph` 输出时带出处，让 LLM 能说「依据 XX 指南（A 级证据）」而不是「疑似 XX」。

**3. 证据包协议 + 反向/缺失证据（直接提升解读的合规性与质量）**
- 定义 `EvidencePack` 结构：`事实 / 已匹配模式 / 支持证据 / 冲突证据 / 缺失上下文 / 来源版本`，替代当前「拼文本进 prompt」。
- 给每条指标→疾病关联补充「反向证据」（如 HGB↓ 不只是缺铁性贫血，还可能是失血/慢性病）和「缺失上下文」（如「需结合 MCV、铁蛋白才能区分」），让解读能诚实地说「更像 X，但也可能是 Y，还需要 Z」——这正是教程「拒答是主流程」「不能只有支持方向材料」的核心。

#### P0 落地记录（改动清单）

| 目标 | 改动 | 文件 |
|---|---|---|
| Schema 治理 | 新增 `GraphSchema`：4 个 name 唯一约束 + 2 个索引（`IF NOT EXISTS` 幂等）、6 种受控关系类型白名单、边属性名常量 | `graph/GraphSchema.java`（新） |
| 探活修正 | `available()` 改 `RETURN 1`（原为全图 `count(n)`）；结果按 `agent.graph.probe-cache-ms`（默认 5s）缓存；连接超时 2s | `graph/Neo4jGraphQueryPort.java`、`application.properties` |
| 关系条件化 + 出处 + 版本 | `SeedEdge` 扩为 10 参：`source / evidenceGrade / population / caveat / version`；20 条边全部标注真实指南来源与 A/B/C 等级，并写明成立条件（如「GLU↑ 需非同日两次确认」） | `graph/MedicalKnowledgeSeeds.java` |
| 证据包协议 | `GraphQueryPort` 以 `EvidencePack(supporting / contradicting / missing / symptoms)` 取代单向 `DiseaseInference`；新增 `Provenance`、`Evidence`、`MissingContext` 三个 record；C 级占位知识渲染时显式标注「待医学审核」 | `graph/GraphQueryPort.java` |
| 三路证据查询 | 一条 Cypher 用模式推导一次取回支持（ABNORMAL_HIGH/LOW）、反向（CONTRAINDICATES）、缺失（REQUIRES_CONTEXT 且不在 `$observed` 内）、科室与症状；固定 1~2 跳，无可变长度路径 | `graph/Neo4jGraphQueryPort.java` |
| 灌库与约束 | 启动先建约束/索引，再灌支持边（带出处属性）、9 条反向边、18 条缺失上下文边；关系类型经白名单校验 | `graph/GraphSeedRunner.java` |
| 降级层同源 | `RelationEdge` 增加 `source / evidenceGrade`；`knowledge_relation` mapping 新增 7 个字段 | `graph/RelationLookupService.java`、`es/knowledge_relation.mapping.json` |
| ES mapping 演进 | 索引初始化器新增「已存在索引增量同步 mapping」——`dynamic: strict` 下新增字段若不先演进，灌库会被 ES 拒绝 | `es/KnowledgeIndexInitializer.java` |
| 知识版本刷新 | 灌库幂等判定从「doc_id 存在」升级为「doc_id 存在 **且** version 匹配」，否则存量无出处文档永远停留在旧版 | `es/KnowledgeSeedRunner.java` |
| 输出 | `formatEvidencePacks` 按「支持 / 反向 / 待补充 / 典型症状」四段渲染，各段设长度上限控制 prompt 体积 | `graph/GraphKnowledgeService.java` |

**验证**：`agent-service` 主代码 + 测试编译通过；全量测试 151 通过 / 0 失败 / 7 跳过（跳过的为 env-gated 的 `GraphEvalTest` 与需外部中间件用例）。
`GraphEvalTest` 已同步升级：除原有 accuracy@1 外，新增「每条支持证据必须带出处与等级」「未检测的鉴别指标必须出现在缺失上下文」「反向证据可对候选疾病召回」三组断言，需本机 Neo4j 时以 `AGENT_EVAL_NEO4J=true` 启用。

**一个设计取舍（值得记录）**：反向证据只对**已有支持证据的候选疾病**生效。
为零支持的疾病也召回反向证据会让候选集爆炸，且对解读无增益——所以「TSH↑ 削弱甲亢」这类反向边，只在甲亢已因其它指标进入候选时才被展示。

### P1 —— ✅ 已完成（2026-09-02 落地，代码已编译 + 单测通过）

**4. 组合规则引擎（版本化，替代 hits 投票）**
- 把「多指标组合模式」从图上的 `count hits` 投票，下沉到**确定性规则引擎**（K-of-N、排除条件、上下文齐全性），规则以 `rule_id@semver` 版本化、经医学审核后发布。
- 图只负责「找与该模式相关的知识与证据」，规则引擎负责「判定 成立 / 证据不足 / 已排除」三态——这与教程 D08「多指标组合是一份逐项核对的检查单，不是指标 A 连指标 B 再连疾病」完全一致。
- 你已有 `CriticalValueEvaluator`（危急值规则）和 `ReportReferenceService`（参考范围比对）两个确定性防线，这一项是把同样思路扩展到「组合模式」，架构是顺的。

**5. 指标受控编码（LOINC 化）**
- `IndicatorNormalizer` 的字符串别名升级为「受控编码映射」（41 个高频指标已映射 LOINC），`concept_id = codeSystem:code`；未核实编码一律留 `null`、回退为 `LOCAL:<标准名>`，从根上解决跨机构同义/歧义。
- **顺手修正一处词典缺陷**：原词典把「铁蛋白」并入「血清铁」的标准名，二者 LOINC 不同（2498-4 / 2276-4）且临床意义不同——铁蛋白才是缺铁性贫血更可靠的判据，分离后同步补了 `FERR↓` 知识边与规则上下文。

#### P1 落地记录（改动清单）

| 目标 | 改动 | 文件 |
|---|---|---|
| 规则模型 | 新增 `CombinationRule`（required AND / anyOf K-of-N / excluded / context）+ `RuleVerdict`（SATISFIED / INSUFFICIENT / EXCLUDED 三态 + rank） | `graph/CombinationRule.java`、`graph/RuleVerdict.java`（新） |
| 规则引擎 | 纯 Java 判定，无 LLM / 无图依赖；加载期校验（无 id、无条件、K>N 剔除）；规则文件缺失按 T-2 降级为「无规则」不阻断启动；输出 `ruleId@version` 可复现 | `graph/CombinationRuleEngine.java`（新） |
| 规则包 | 15 条规则覆盖脂代谢/糖代谢/肝/肾/甲状腺/血液/感染方向，每条带来源与版本 | `graph/combination-rules.json`（新） |
| 职责切分 | 图负责找证据、规则负责定序：`rankByRule` 按「成立→证据不足→无规则→已排除」分档，档内才用 hits 做次要排序；附 `formatStandaloneVerdicts` 输出图未覆盖但规则成立的判定 | `graph/GraphKnowledgeService.java` |
| 受控编码 | `IndicatorNormalizer` 增加 `codeOf` / `conceptId` / `codedConceptCount`；加载器对未知字段宽容（容忍 `_comment`） | `report/IndicatorNormalizer.java`、`report/indicator-aliases.json` |

**验证**：主代码 + 测试编译通过；P1 相关单测 27 例全绿（`CombinationRuleEngineTest` 6 例锁三态判定、`IndicatorNormalizerTest` 10 例锁 LOINC、`GraphKnowledgeServiceTest` 11 例锁「图找证据 + 规则定序」）。
关键回归点：`ALT↑+AST↑+TBIL↑` 现在脂肪肝被规则判为**已排除**、胆汁淤积判为**成立**——纯 `hits` 投票会排反（脂肪肝 hits 更高）；图与 ES 全不可用时规则仍给出确定性结论。

**效果对照（v2 → v3）**：`ALT↑+AST↑` 在 v2 按连边数把「脂肪肝」排第一、「肝损伤」方向被压住；v3 由规则引擎按检查单判定，把「需排除胆汁淤积/药物性肝损伤」显式说出来，排序交给确定性逻辑而非共现投票。

### P2 —— 规模与治理需要时再上（教程明确「不要一开始 8,000 条」）

**6. 知识主本 + 发布编译器 + 只读投影**
- 知识从「Java 硬编码」迁到「Git/JSON 管理的 DSL 主本」（延续 `critical-rules.json` 的配置化思路，不必首日上 34 表关系库），发布时校验（规则树无环、K≤N、必须有证据的声明无来源即拒）后投影到 ES + Neo4j 只读副本，换版本 = 换整个快照。

**7. 运行快照不可变工件**
- 把 `response_snapshot` 升级为结构化运行快照：`graph snapshot + rule bundle version + knowledge version + model version`，支持「复现历史回答」和「知识回滚」。

**8. 社区检测 + Global Search（可选）**
- 仅当知识规模增长到需要「全局归纳」（如「这批指南里反复出现的风险主题」）时，再上 Leiden 社区 + 社区报告 + Global/DRIFT 查询路由。报告解读的日常高频仍是 Basic（指标含义，走 ES `lab_exam`）+ Local（指标→疾病多跳，走图），Global 不是当前刚需。

### 长期 —— ✅ 已完成（2026-09-02 落地，代码已编译 + 单测通过）

**9. 双图落地（查询时投影，不新增存储）**
- 当前患者事实在 MySQL、共享知识在 Neo4j，隔离方向正确。**不**把患者节点持久化进共享知识图（教程明确反对），而是复用既有 `report_indicator` 表，在查询时按标准指标名投影出**跨报告时间序列趋势**（Episode Graph 的语义等价物）。
- 把 `HealthFactCard` 预留的 `ALLERGY / CHRONIC_FLAG / MEDICATION` 事实类型真正打通：问诊 / 问药 Agent 在用户明确陈述时调用 `recordFact` 落库，注入上下文时通过 `redlineFactsSection` 读取「已知过敏 / 长期用药 / 慢病标签」作为红线事实，形成「报告 + 问诊 + 用药」的患者级长程记忆。

#### 长期落地记录（改动清单）

| 目标 | 改动 | 文件 |
|---|---|---|
| 趋势投影（Episode Graph 等价） | 新增 `PatientObservationService`：复用 `ReportIndicatorRecordRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc`，投影出 `Direction(IMPROVING/WORSENING/STABLE/RISING/FALLING/UNKNOWN)`、`delta`、最近正常日期、`trendSection` 紧凑段；不新增任何存储，纯查询时投影 | `service/PatientObservationService.java`（新） |
| 红线事实读写 | `HealthMemoryService` 新增 `getFacts(userId)`、`recordFact(userId, type, name, value)`（@Transactional，首次为准、只去重不覆盖）、`redlineFactsSection(userId)`（空则空串，基类仅在非空时追加） | `service/HealthMemoryService.java` |
| Agent 注入红线事实 | `ConsultationAgent` / `MedicineQaAgent` 注入 `HealthMemoryService`，覆写 `memoryContext` 返回 `redlineFactsSection`，让问诊 / 问药在系统提示中带上患者已知过敏 / 用药 / 慢病，规避禁忌 | `agent/specialist/ConsultationAgent.java`、`agent/specialist/MedicineQaAgent.java` |
| 可观测工具 | `ReportTools` 新增 `@Tool queryIndicatorTrend(indicatorName)`，复用趋势投影为医生 / 用户侧提供「该指标历史走势」查询 | `orchestration/tools/ReportTools.java` |
| 顺带修复一处生产级缺陷 | `HealthFactCard.facts` 字段 getter 命名为 `facts()`（非 `getFacts()`），不符合 JavaBean 约定，Jackson 不识别 → `factCard` 列一直被写成 `{}` 静默丢失全部事实（此前因读取方读 `profileSummary` 字符串而未暴露）。补 `@JsonProperty("facts")` 修复；并补 `@JsonProperty` 反向/缺失字段一致性。 | `service/HealthFactCard.java` |
| 序列化健壮性 | `HealthFactCard` 含 `LocalDate` 字段，`ObjectMapper` 需注册 `JavaTimeModule` 才能往返。`HealthMemoryService` 构造器改为「复制注入的 mapper + `findAndRegisterModules()`」，无论注入 Spring 的 ObjectMapper 还是测试裸 `new ObjectMapper()` 都能正确序列化，且不污染共享 Bean | `service/HealthMemoryService.java` |

**验证**：患者观测图相关单测 11 例全绿（`PatientObservationServiceTest` 4 例锁趋势投影方向 / 变化量 / 最近正常日期 / 无数据；`HealthMemoryServiceTest` 2 例锁红线事实去重与空段；`HealthFactCardTest` 5 例锁合并语义 + JSON 往返保真）。
**全量回归**：`agent-service` 全量 **172 通过 / 0 失败 / 7 跳过**（7 跳过为 env-gated 用例），BUILD SUCCESS，无回归。`HealthFactCard` 的 `{}` 序列化缺陷修复后，历史 `factCard` 列首次能真正落库事实，不受影响读取方已切换为 `getFacts`/`redlineFactsSection`。

---

## 四、教程明确反对的三个错（务必守住）

1. **把「指标→疾病」的关联边当成诊断规则**——路径存在 ≠ 患病。你现在的 `hits` 排序已经是这个坑的雏形，P1-4 的组合规则引擎就是来堵这个口的。
2. **把规则命中交给 LLM**——自然语言说得像就算命中。你的 `RangeParser` 纯 Java 判定、`CriticalValueEvaluator` 确定性规则是对的，别在组合模式上开倒车。
3. **把患者节点持久化到共享知识图**——权限、删除、缓存都收不了口。你「患者事实留 MySQL、图只存共享知识」是对的，继续保持，只做「查询时按标准概念 ID 投影」。

---

## 五、一句话总结

> v1 解决「找得到」（检索），v2 解决「连得上」（多指标联合），v3 要解决「信得过 + 说得清」——**关系要有出处与版本、判断交给确定性规则而非投票、检索给出支持/冲突/缺失三路证据、知识走「主本 + 只读投影 + 不可变快照」的治理闭环**。每一步都克制在「价值密度最高」的那一层，而不是再引入一个存储引擎。
