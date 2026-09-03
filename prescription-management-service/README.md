# prescription-management-service（处方管理服务）

问诊领域支撑服务之一：**处方管理**。

- **规划端口**：8092
- **规划数据库**：`shopdb_prescription_mgmt`
- **职责**：处方全生命周期管理——从开处方服务产生的处方进入流转（待审核 → 药师审方 → 生效/驳回 → 被订单消费 → 归档），维护处方状态机、审方意见留痕、合规审计与处方查询；处方药购买的唯一合法凭据来源。
- **状态**：占位模块，暂无代码实现。具体实现要求见 [`harness/agents/prescription-management-service.md`](../harness/agents/prescription-management-service.md)。

> 问诊领域属于医疗买药问诊平台四大领域（商品 / 导购 / 问诊 / 订单）之一：上游接收导购领域转化来的问诊线索，问诊完成后向订单领域输出处方凭据。
