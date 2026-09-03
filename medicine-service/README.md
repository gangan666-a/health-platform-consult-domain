# medicine-service（药品服务）

问诊领域支撑服务之一：**药品服务**（九大服务中已实现的一个）。

- **端口**：8081 · **DB**：`shopdb_medicine` · 纯 REST
- **职责**：药品目录（Rx 处方药 / OTC 非处方药）、药品详情、原子库存扣减/回补；处方药语义（`rxFlag=true` 须凭处方购买）为问诊领域与订单领域的衔接点。
- **实现状态**：已实现（`controller/MedicineController` 等，详见 [`harness/agents/medicine-service.md`](../harness/agents/medicine-service.md)）。
- **注意**：本服务属于问诊领域，与商品领域的商品服务无关；通用商品（非药品）由商品领域工程维护。

> 问诊领域属于医疗买药问诊平台四大领域（商品 / 导购 / 问诊 / 订单）之一：上游接收导购领域转化来的问诊线索，问诊完成后向订单领域输出处方凭据。
