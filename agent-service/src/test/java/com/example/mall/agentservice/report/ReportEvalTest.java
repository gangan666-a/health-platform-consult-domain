package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测门禁（防线4，确定性路径，CI 必跑）：字段级 recall/precision 低于 0.90 即测试失败阻断构建——
 * 改 prompt、换模型、调抽取逻辑后自动回归，防止「改 A 坏 B」。
 * LLM 路径门禁见 LlmReportEvalTest（需真实 Key）。
 */
class ReportEvalTest extends ReportEvalSupport {

    private static final double GATE = 0.90;

    private final ReportParser deterministicParser = ReportParserTest.newReportParser();

    @Override
    protected ReportParser parser() {
        return deterministicParser;
    }

    @Test
    void extractionQuality_meetsRegressionGate() throws Exception {
        List<Fixture> fixtures = loadFixtures();
        assertFalse(fixtures.isEmpty(), "评测金标集不能为空");

        for (Fixture fixture : fixtures) {
            EvalResult result = evaluate(fixture);
            assertTrue(result.recall() >= GATE,
                    () -> fixture.name() + " 指标召回不足（漏抽）: " + result);
            assertTrue(result.precision() >= GATE,
                    () -> fixture.name() + " 指标精确不足（疑似幻觉/多抽）: " + result);
            assertTrue(result.findingRecall() >= 1.0,
                    () -> fixture.name() + " 检查所见关键词缺失: " + result);
            if (fixture.expected().examName() != null) {
                assertEquals(fixture.expected().examName(), result.examName(),
                        () -> fixture.name() + " 检查名不符: " + result);
            }
        }
    }
}
