# pharmacist-assignment-service（分配药师服务）

问诊领域核心服务之一：**分配药师**。

- **规划端口**：8094
- **规划数据库**：`shopdb_pharmacist_assign`
- **职责**：处方开具后进入审方环节，按「执业药师资质 → 在线状态（依赖排班服务）→ 当前审方负载」将处方分配给合适药师；维护审方排队队列、超时转派与审方时效统计。
- **状态**：占位模块，暂无代码实现。具体实现要求见 [`harness/agents/pharmacist-assignment-service.md`](../harness/agents/pharmacist-assignment-service.md)。

> 问诊领域属于医疗买药问诊平台四大领域（商品 / 导购 / 问诊 / 订单）之一：上游接收导购领域转化来的问诊线索，问诊完成后向订单领域输出处方凭据。
