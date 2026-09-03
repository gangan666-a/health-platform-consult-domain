package com.example.mall.agentservice.orchestration;

import com.example.mall.common.model.AgentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由金标评测（L3，CI 必跑门禁）：钉住启发式路由的行为契约——
 * 路由规则重构（或切换为 LLM 路由）后此门禁保证 16 条金标输入的分发结果不变。
 */
class RoutingEvalTest {

    record Case(String input, String expect) {
    }

    record CaseFile(List<Case> cases) {
    }

    private final List<Case> cases = load();

    @Test
    void heuristicRouting_accuracyIs100Percent() {
        assertFalseEmpty();
        long correct = cases.stream()
                .filter(c -> ManagerRouter.heuristicRoute(c.input()) == AgentType.valueOf(c.expect()))
                .count();

        assertEquals(cases.size(), correct,
                () -> misroutedReport(correct));
    }

    private void assertFalseEmpty() {
        assertTrue(!cases.isEmpty(), "路由金标集不能为空");
    }

    private String misroutedReport(long correct) {
        StringBuilder sb = new StringBuilder("路由准确率 " + correct + "/" + cases.size() + "，错分：");
        for (Case c : cases) {
            AgentType actual = ManagerRouter.heuristicRoute(c.input());
            if (actual != AgentType.valueOf(c.expect())) {
                sb.append('[').append(c.input()).append(" 期望 ").append(c.expect())
                        .append(" 实际 ").append(actual).append("] ");
            }
        }
        return sb.toString();
    }

    private static List<Case> load() {
        try {
            return new ObjectMapper()
                    .readValue(new ClassPathResource("agent-eval/routing-golden.json").getFile(), CaseFile.class)
                    .cases();
        } catch (Exception e) {
            throw new IllegalStateException("路由金标集加载失败", e);
        }
    }
}
