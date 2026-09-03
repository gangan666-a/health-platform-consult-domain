# 双向追溯矩阵（FR×AC×EX×NFR → Spec × Test）

> 设计元素（行）→ Spec 章节（列）→ 测试用例（值）。新增 Spec 在此扩展。
> report-interpretation 设计元素来源 = `docs/16-PRD-REPORT.md`（FR-R-1..R-7）+ PM-DESIGN-2026-08-24。

## report-interpretation（报告解读 Agent）

| 设计元素 | §2 功能 | §3 流程 | §4 接口 | §5 数据 | §7 异常 | §10 验收 | §11 测试 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 决策1 双通道解析 | R-3 | P-4 | — | — | E-3 | T-1 | ReportParserTest |
| 决策2 化验单/体检单优先 | R-1 | P-3 | — | — | — | T-1 | ReportParserTest |
| 决策3 接口抽象+模拟 | R-3 | P-4 | — | — | — | — | ReportParserTest |
| 决策4 加库 shopdb_agent | R-5 | P-7 | — | report_record/indicator | — | T-1 | ReportUploadServiceTest |
| 决策5 结构化+摘要记忆 | R-5 | P-8 | — | health_profile | — | T-8 | HealthMemoryService |
| 决策6 同指标多日期对比 | — | — | GET /compare | report_indicator(idx) | E-6 | T-4/T-5 | ReportCompareServiceTest |
| 合规·授权 | R-4 | P-2 | upload(consent) | — | E-1 | T-2 | ReportControllerTest |
| 合规·脱敏/最小化 | R-3 | P-6 | — | — | E-7 | — | ReportComplianceService |
| 合规·免责/客观 | R-2 | P-9 | — | — | E-5 | T-1/T-7 | ReportAgent |
| 非目标·不做影像诊断 | §1 NG | — | — | — | — | — | — |
