# consult-service（问诊会话服务 · 参考实现）

问诊领域核心链路的**现有参考实现**：问诊会话（创建/接诊/结束/消息）、医生开方、处方一次性消费/回补、WebSocket 实时聊天。

- **端口**：8085 REST / 8086 Netty WS · **DB**：`shopdb_consult` · 自包含（无出站）
- **定位**：在问诊领域目标架构（九大服务拆分）落地前，本服务承载问诊会话与开方的存量实现，并被 agent-service（项目主体）依赖调用；后续将按目标架构逐步把「问诊记录」「开处方」拆分至 `consult-record-service`、`prescription-service`，处方流转交由 `prescription-management-service` 管理。
- **实现状态**：已实现（详见 [`harness/agents/consult-service.md`](../harness/agents/consult-service.md)）。

> 问诊领域属于医疗买药问诊平台四大领域（商品 / 导购 / 问诊 / 订单）之一：上游接收导购领域转化来的问诊线索，问诊完成后向订单领域输出处方凭据。
