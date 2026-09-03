package com.example.mall.agentservice.graph;

import com.example.mall.agentservice.graph.GraphQueryPort.IndicatorSignal;
import com.example.mall.agentservice.graph.RuleVerdict.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合规则引擎（P1-1）：纯 Java 确定性判定，不依赖图 / ES / LLM。
 * 重点锁住三态与「图投票做不到」的排除 / 上下文逻辑。
 */
class CombinationRuleEngineTest {

    private final CombinationRuleEngine engine = new CombinationRuleEngine(new ObjectMapper());

    private static IndicatorSignal sig(String indicator, String direction) {
        return new IndicatorSignal(indicator, direction);
    }

    private static RuleVerdict verdict(List<RuleVerdict> all, String disease) {
        return Optional.ofNullable(CombinationRuleEngine.bestFor(all, disease))
                .orElseThrow(() -> new AssertionError("规则未对 " + disease + " 给出判定"));
    }

    /** 必须项满足 + K-of-N 满足 + 无排除项 + 上下文已检测 → 成立。 */
    @Test
    void satisfied_whenAllConditionsMetAndContextTested() {
        // 缺铁性贫血：HGB↓ + FERR↓ + HCT↓（K-of-N 命中），FERR 在 observed 中已检测
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("HGB", "LOW"), sig("FERR", "LOW"), sig("HCT", "LOW")),
                List.of("HGB", "FERR", "HCT"));

        RuleVerdict v = verdict(all, "缺铁性贫血");
        assertEquals(Outcome.SATISFIED, v.outcome());
        assertEquals("iron-deficiency-anemia@1.0.0", v.ref());
    }

    /** 上下文指标未检测 → INSUFFICIENT（信息缺口，不是结论）。 */
    @Test
    void insufficient_whenContextNotTested() {
        // HGB↓ + HCT↓ 命中 K-of-N，但 FERR 未在 observed → 证据不足
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("HGB", "LOW"), sig("HCT", "LOW")),
                List.of("HGB", "HCT"));

        RuleVerdict v = verdict(all, "缺铁性贫血");
        assertEquals(Outcome.INSUFFICIENT, v.outcome());
        assertTrue(v.missingContext().contains("FERR"), "必须点明还缺哪一项");
    }

    /** 命中排除项 → EXCLUDED，且优先级高于上下文判定。 */
    @Test
    void excluded_whenExclusionCriterionHit() {
        // ALT↑ + AST↑ 命中脂肪肝模式（required ALT + anyOf AST），再叠加 TBIL↑ 触发排除项
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("ALT", "HIGH"), sig("AST", "HIGH"), sig("TBIL", "HIGH")),
                List.of("ALT", "TBIL", "AST", "GGT"));

        RuleVerdict v = verdict(all, "脂肪肝");
        assertEquals(Outcome.EXCLUDED, v.outcome());
        assertTrue(v.excludedHits().contains("TBIL↑"));
    }

    /** K-of-N 未达门槛 → 规则不成立，不输出判定（避免把单次异常当组合模式）。 */
    @Test
    void noVerdict_whenKOfNNotMet() {
        // 仅 LDL↑：要求 anyOf(TC/TG/HDL) 至少命中 1 项，未命中 → 无判定
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("LDL", "HIGH")), List.of("LDL"));

        assertTrue(CombinationRuleEngine.bestFor(all, "动脉粥样硬化") == null,
                "单项 LDL 异常不应触发动脉粥样硬化组合模式判定");
    }

    /** 同一疾病被多条规则判定时，保留最优（rank 最小）。 */
    @Test
    void bestVerdict_keepsLowestRank() {
        // 糖尿病：GLU↑（required GLU HIGH）成立，但 HBA1C 未在 observed → INSUFFICIENT
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("GLU", "HIGH")), List.of("GLU"));

        RuleVerdict v = verdict(all, "糖尿病");
        assertEquals(Outcome.INSUFFICIENT, v.outcome());
    }

    /** 反向证据：ALT↑+AST↑+TBIL↑ 时，脂肪肝被排除、胆汁淤积成立——规则把原本会被投票压住的方向翻正。 */
    @Test
    void ruleReordersAgainstRawHitCount() {
        // 脂肪肝规则 required ALT↑ + anyOf(AST/GGT/TG) + 排除 TBIL↑
        // 胆汁淤积规则 required TBIL↑ + anyOf(ALP/GGT/DBIL)
        List<RuleVerdict> all = engine.evaluate(
                List.of(sig("ALT", "HIGH"), sig("AST", "HIGH"), sig("TBIL", "HIGH"), sig("ALP", "HIGH")),
                List.of("ALT", "TBIL", "ALP", "AST", "GGT", "DBIL"));

        assertEquals(Outcome.EXCLUDED, verdict(all, "脂肪肝").outcome());
        assertEquals(Outcome.SATISFIED, verdict(all, "胆汁淤积").outcome());
    }
}
