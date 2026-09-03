# 15. 多 Agent 架构（Spring AI）

> 医疗健康平台核心：1 个 Manager + 6 个 Sub Agent，Supervisor 与 Handoff 混合编排。

## 架构总览

```
用户（浏览器 :8082）
    │
    ├─ POST /api/agent/chat ──▶ order-service BFF ──▶ agent-service (:8087)
    │                              AgentOrchestrator
    │                                   │
    │                    ┌──────────────┴──────────────┐
    │                    ▼                             ▼
    │              SafetyAgent.preCheck          会话状态 AgentSessionStore
    │              （fail-closed 拦截）                │
    │                    │                    activeAgent / mode
    │                    ▼                             │
    │         ┌──────────┴──────────┐                  │
    │         ▼                     ▼                  │
    │   SUPERVISOR 模式        HANDOFF 模式            │
    │   Manager + @Tool        当前 Sub Agent          │
    │   委派 Sub Agent         + handoff tools         │
    └─ 药品购药 / IM 问诊 ──▶ medicine / consult / payment
```

## Agent 清单

| AgentType | 职责 | Handoff 目标 |
|---|---|---|
| MANAGER | Supervisor 路由、汇总 | （委派 Sub Agent） |
| CONSULTATION | 症状、就医建议 | 问药、导购、科普、报告 |
| MEDICINE_QA | 用药咨询 | 问诊、导购、安全 |
| SHOPPING_GUIDE | 选药购药引导 | 问药、问诊、安全 |
| SCIENCE | 健康科普 | 问诊、问药 |
| REPORT | 报告解读 | 问诊、问药 |
| SAFETY | 合规审查、紧急拦截 | 问诊 |

## 编排模式

### Supervisor（Manager 委派）

- Manager 是 `ChatClient` + `@Tool`（`ManagerDelegateTools`）
- 每个 Sub Agent 暴露为一个 tool 方法
- 适合**新会话首条消息**或**交还 Manager** 后的路由

### Handoff（Sub Agent 直连）

- 会话 `activeAgent != MANAGER` 时进入 Handoff 模式
- Sub Agent 携带 `HandoffTools`，可将对话转交 peer Agent
- `AgentSessionStore` 记录 handoff 历史

### 混合策略（`AgentOrchestrator`）

1. **Safety 前置**：所有消息先过 `SafetyAgent.preCheck`，拦截则直接返回
2. **有 active Sub Agent** → `HandoffOrchestrator`
3. **否则** → `SupervisorOrchestrator`（Manager 路由）

## 技术栈

| 组件 | 选型 |
|---|---|
| LLM | Spring AI `spring-ai-starter-model-openai`（DeepSeek 兼容 API） |
| 向量库 | Elasticsearch（`spring-ai-starter-vector-store-elasticsearch`） |
| Memory | `MessageWindowChatMemory` + namespaced conversationId |
| 共享 DTO | `mall-common`：`AgentType` / `AgentChatRequest` / `AgentChatResponse` |

## 模块与端口

| 模块 | 端口 | 说明 |
|---|---|---|
| `agent-service` | 8087 | 多 Agent 编排 + RAG |
| `medicine-service` | 8081 | 药品目录（替代原 product-service） |
| `order-service` | 8082 | BFF：AI 助手页 + 购药 |
| `consult-service` | 8085/8086 | IM 在线问诊 |
| `payment-service` | 8083 | 支付 |

## 配置

```properties
# agent-service/src/main/resources/application.properties
agent.deepseek.api-key=sk-你的DeepSeek_API_Key
spring.ai.openai.api-key=${agent.deepseek.api-key:placeholder-local-dev}
spring.ai.openai.base-url=https://api.deepseek.com
spring.elasticsearch.uris=http://localhost:9200
spring.ai.vectorstore.elasticsearch.index-name=health-knowledge
```

留空 `agent.deepseek.api-key` 时 Agent 使用占位回复，便于无 Key 本地联调。

## 后续完善（TODO）

- [ ] 各 Sub Agent 业务 Tool（查药品、建问诊会话、下单）
- [ ] ES 知识库灌入（按 AgentType 分索引/metadata）
- [ ] Handoff 的 LLM 意图识别（替代关键词启发式）
- [ ] 会话持久化（Redis/MySQL）
- [ ] 流式 SSE 响应
