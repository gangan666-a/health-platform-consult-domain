# 双向追溯矩阵模板（Coverage Matrix）

> 产出物：`docs/spec/COVERAGE.md`。用途：证明「PRD 每个元素都被 Spec 覆盖、每条 Spec 都能反向定位」。对齐范式：`harness-code-quality`（可追溯）+ `harness-architecture`（索引文件限长，常载进上下文）。

## 用法

- 一行一 Feature，按 PRD 的 FR 顺序排列。
- 覆盖率 = 已映射元素数 / PRD 元素总数；**目标 100%**。
- 更新纪律：PRD 变更 / Spec 修订时同步更新矩阵（只算变化）。

## 矩阵结构

### 表 1：FR → Spec 覆盖

| PRD 元素 | 覆盖 Spec 文件 | Spec 章节 | 关键验收 | 状态 |
| --- | --- | --- | --- | --- |
| FR-1 商品浏览 | docs/spec/FR-1-products.spec.md | §2/§4/§10 | T-FR1-* | ✅ |
| FR-2 下单 | docs/spec/FR-2-place-order.spec.md | §2~§11 | T-FR2-* | ✅ |
| … | … | … | … | … |

### 表 2：AC 覆盖核对

| AC | 映射到 Spec §10 编号 | 测试用例编号 | 状态 |
| --- | --- | --- | --- |
| AC-FR2-3 | T-FR2-3 | OrderServiceTest.quantityZero | ✅ |
| … | … | … | … |

### 表 3：EX 覆盖核对

| EX | 映射到 Spec §7 编号 | 对应验收 | 状态 |
| --- | --- | --- | --- |
| EX-1 库存不足 | E-1 | T-FR2-5 | ✅ |
| … | … | … | … |

### 表 4：NFR 覆盖核对

| NFR | 映射到 Spec §8/§9 编号 | 可量化验收 | 状态 |
| --- | --- | --- | --- |
| NFR-6 并发防超卖 | B-2 | T-X3 并发扣减 | ✅ |
| … | … | … | … |

### 汇总

```
Feature: FR-2 下单   覆盖率: 8/8 FR · 9/9 AC · 5/5 EX · 2/2 NFR = 100%   Spec 状态: DRAFT
```

## 限长纪律

- COVERAGE.md 是常载/常查文件：设行数与字节上限（如 300 行 / 16KB），超限只保留「当前活跃迭代 + 上期已归档」两段，历史归档到 `docs/spec/archive/`。
- 每条目控制在单行（≤ 150 字符），指向详细内容而非内嵌。
