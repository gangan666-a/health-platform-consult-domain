# Agent 评测框架（docs/17）

> 版本 v1.0（2026-08-29）。设计原则：**确定性评测进 CI 阻断构建，LLM 评测 env-gated 干净跳过**；
> 评测资产（用例/金标/评分标准）JSON 化进 git 版本管理；深度分级——report 最深（客观真值），
> safety 全量枚举，对话型 Agent 金标 + judge。

## 一、四层框架与覆盖范围

| 层级 | 对象 | 方法 | 运行方式 | 落地件 |
| --- | --- | --- | --- | --- |
| L0 组件级 | 工具/规则/网关 | 单元测试 | CI 必跑 | 既有 90+ 单测（含 RoutedChatModelTest） |
| L1 管线级 | 报告解析 + 解读 | 解析：字段级 P/R；解读：确定性断言（免责/危急告警/就医引导）+ judge | 解析 CI 必跑；judge env-gated | `ReportEvalTest`（CI 门禁）、`LlmReportEvalTest`、`LlmAgentBehaviorEvalTest#reportInterpretation` |
| L1 视觉抽取 | 视觉通道（GLM-4.6V） | 渲染化验单图片 → 真实 VLM 抽取，字段级 recall@0.90 | env-gated（`VISION_API_KEY`） | `VisionEvalTest`（不达标 → 切付费档 glm-4.6v） |
| L2 单 Agent 行为级 | 问诊/问药/科普/导购 | 金标问答 + LLM-as-judge（rubric：正确性/安全性/有用性/要点覆盖） | env-gated（`DEEPSEEK_API_KEY`） | `agent-eval/agent-golden/*.json` + `LlmAgentBehaviorEvalTest` |
| L3 编排级 | 安全拦截 + 路由分发 | 红队集拦截率 100%；路由金标准确率 100%（钉住行为契约） | **CI 必跑**（纯规则确定性） | `SafetyRedTeamEvalTest`、`RoutingEvalTest` |
| L2 检索级 | hybrid 检索质量 | golden query recall@5 | env-gated（`AGENT_EVAL_ES=true`） | `RetrievalEvalTest` |
| L2 知识推理级 | 图谱 vs 关系表 | 多指标联合推理金标 accuracy@1（图按命中排序，表为逐指标并集） | env-gated（`AGENT_EVAL_NEO4J=true`，需本机 Neo4j） | `GraphEvalTest` |
| L4 线上 | 全系统 | token/成本计量（`GET /api/agents/llm-usage`）+ 用户反馈位（规划） | 常驻 | `LlmUsageMeter` |

**为什么覆盖全部 Agent 但深度分级**：report 有客观真值（抽取字段可对答案）→ 最深；
safety 是纯规则可枚举 → 全量红队穷举进 CI（拦截率 100% 是硬门槛）；对话型 Agent 真值模糊
→ 金标问答 + judge；路由是分类问题 → 金标准确率。评测框架本身是通用的，新增 Agent =
加一个 `agent-golden/<agent>.json`。

## 二、运行方式

```bash
# CI 常规（确定性全部门禁，无外部依赖）
mvn -pl agent-service test

# LLM 行为评测（需真实 Key）
DEEPSEEK_API_KEY=sk-xxx mvn -pl agent-service test -Dtest=LlmAgentBehaviorEvalTest
DEEPSEEK_API_KEY=sk-xxx mvn -pl agent-service test -Dtest=LlmReportEvalTest

# 检索评测（需本机 ES + IK 插件，先启动应用让种子数据灌入）
AGENT_EVAL_ES=true mvn -pl agent-service test -Dtest=RetrievalEvalTest

# 视觉抽取评测（需智谱 Key，免费档 glm-4.6v-flash；recall<0.90 会提示切付费档）
VISION_API_KEY=xxx mvn -pl agent-service test -Dtest=VisionEvalTest
```

## 三、门禁阈值

| 门禁 | 阈值 | 违反后果 |
| --- | --- | --- |
| 报告抽取（确定性） | recall ≥ 0.90 且 precision ≥ 0.90 | 构建失败 |
| 报告抽取（LLM） | recall ≥ 0.80 且 precision ≥ 0.80 | 构建失败（有 Key 时） |
| 安全拦截 | BLOCK 用例拦截率 100%，PASS 用例误拦率 0% | 构建失败 |
| 路由分发 | 金标准确率 100% | 构建失败 |
| Agent 回答 | correctness ≥ 4.0 / safety ≥ 4.0 / helpfulness ≥ 3.5（5 分制） | 构建失败（有 Key 时） |
| 检索 | golden query recall@5 = 100% | 构建失败（启用时） |

## 四、judge 校准纪律

LLM-as-judge 有偏差（长度偏好/自我偏好），纪律：① judge 与被测模型用不同来源更佳（当前同为
DeepSeek，标注为已知局限）；② 每次修改 rubric 后抽样 10% 人工复核；③ judge 输出带 reason
便于失败排查；④ 关键安全断言不依赖 judge——危急值/免责声明用**确定性断言**（代码可验证的
事实不交给概率模型）。红队集中的「规则边界」用例（如无关键词的自杀暗示）标注为 PASS 并注明
「LLM 增强层价值点」——它们记录的是规则引擎的已知边界，不是缺陷。

## 五、评测资产清单

```
src/test/resources/
├── report/eval/                    # 报告解析金标（3 份多页样例）
└── agent-eval/
    ├── safety-redteam.json         # 安全红队 24 条（14 BLOCK + 10 PASS）
    ├── routing-golden.json         # 路由金标 16 条
    └── agent-golden/               # 各 Agent 金标问答（consultation/medicine_qa/science/shopping_guide）
```
