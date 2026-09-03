package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测门禁（防线4，LLM 路径）：设置环境变量 DEEPSEEK_API_KEY 后自动启用，
 * 以真实 LLM（规划/分类/抽取全链路）跑同一批金标样例；未设置时干净跳过，CI 不受影响。
 * 阈值较确定性门禁放宽（0.80）：允许 LLM 表述级差异，但漏抽/幻觉仍会被拦下。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=${DEEPSEEK_API_KEY}",
        "agent.deepseek.api-key=${DEEPSEEK_API_KEY}"
})
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class LlmReportEvalTest extends ReportEvalSupport {

    private static final double GATE = 0.80;

    @Autowired
    ReportParser springParser;

    @Override
    protected ReportParser parser() {
        return springParser;
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void llmExtractionQuality_meetsGate() throws Exception {
        List<Fixture> fixtures = loadFixtures();

        for (Fixture fixture : fixtures) {
            EvalResult result = evaluate(fixture);
            assertTrue(result.recall() >= GATE,
                    () -> fixture.name() + " LLM 指标召回不足: " + result);
            assertTrue(result.precision() >= GATE,
                    () -> fixture.name() + " LLM 指标精确不足（疑似幻觉）: " + result);
            assertTrue(result.findingRecall() >= GATE,
                    () -> fixture.name() + " LLM 所见关键词缺失: " + result);
        }
    }
}
