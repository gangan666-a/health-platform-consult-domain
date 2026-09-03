# doctor-assignment-service（分配医生服务）

问诊领域核心服务之一：**分配医生**。

- **规划端口**：8093
- **规划数据库**：`shopdb_doctor_assign`
- **职责**：接收问诊请求后按「科室匹配 → 在线状态（依赖排班服务）→ 当前负载」策略将问诊会话调度给合适医生，维护排队队列与超时转派；支持患者指定医生与紧急问诊插队。
- **状态**：占位模块，暂无代码实现。具体实现要求见 [`harness/agents/doctor-assignment-service.md`](../harness/agents/doctor-assignment-service.md)。

> 问诊领域属于医疗买药问诊平台四大领域（商品 / 导购 / 问诊 / 订单）之一：上游接收导购领域转化来的问诊线索，问诊完成后向订单领域输出处方凭据。
