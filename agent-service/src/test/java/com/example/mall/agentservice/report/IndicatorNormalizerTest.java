package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指标同义词归一（OPEN-3）：中文别名/缩写/带单位尾缀/全半角 → 标准名；未命中保留原名。 */
class IndicatorNormalizerTest {

    private final IndicatorNormalizer normalizer = new IndicatorNormalizer(new ObjectMapper());

    @Test
    void chineseAlias_toCanonical() {
        assertEquals("WBC", normalizer.normalize("白细胞计数"));
        assertEquals("WBC", normalizer.normalize("白细胞"));
        assertEquals("GLU", normalizer.normalize("空腹血糖"));
        assertEquals("ALT", normalizer.normalize("谷丙转氨酶"));
        assertEquals("HDL", normalizer.normalize("高密度脂蛋白胆固醇"));
    }

    @Test
    void canonicalStaysCanonical() {
        assertEquals("WBC", normalizer.normalize("WBC"));
        assertEquals("GLU", normalizer.normalize("GLU"));
    }

    @Test
    void trailingUnitStripped() {
        assertEquals("WBC", normalizer.normalize("白细胞计数(10^9/L)"));
        assertEquals("WBC", normalizer.normalize("WBC（10^9/L）"));
    }

    @Test
    void fullWidthLettersMatch() {
        assertEquals("WBC", normalizer.normalize("ｗｂｃ"));
        assertEquals("WBC", normalizer.normalize("ＷＢＣ"));
    }

    @Test
    void unknownKeepsOriginal() {
        assertEquals("自定义特色指标", normalizer.normalize("  自定义特色指标 "));
        assertEquals("XYZ1", normalizer.normalize("XYZ1"));
    }

    // ---- P1-2 受控编码 ----

    @Test
    void loincCodeResolvedViaAliasOrChineseName() {
        assertEquals("718-7", normalizer.codeOf("HGB"));
        assertEquals("718-7", normalizer.codeOf("血红蛋白"), "中文名与缩写必须映射到同一编码");
        assertEquals("2345-7", normalizer.codeOf("空腹血糖"));
    }

    @Test
    void conceptIdUsesLoincWhenCoded() {
        assertEquals("LOINC:718-7", normalizer.conceptId("HGB"));
        assertEquals("LOINC:718-7", normalizer.conceptId("血红蛋白浓度"), "别名路径同样得到受控概念标识");
    }

    /** 未核实编码必须显式回退 LOCAL，而不是填一个看起来对但没核实的码。 */
    @Test
    void conceptIdFallsBackToLocalWhenNotCoded() {
        assertEquals("LOCAL:HP", normalizer.conceptId("幽门螺杆菌"));
        assertNull(normalizer.codeOf("HP"), "未编码概念不臆造 LOINC 码");
        assertEquals("LOCAL:自定义特色指标", normalizer.conceptId("自定义特色指标"));
    }

    @Test
    void serumIronAndFerritinAreDistinctConcepts() {
        // P1-2 修正：原词典把「铁蛋白」并入 FE，二者 LOINC 不同（2498-4 / 2276-4）且临床意义不同
        assertEquals("FE", normalizer.normalize("血清铁"));
        assertEquals("FERR", normalizer.normalize("铁蛋白"));
        assertEquals("2498-4", normalizer.codeOf("血清铁"));
        assertEquals("2276-4", normalizer.codeOf("铁蛋白"));
    }

    @Test
    void mostConceptsAreCoded() {
        int coded = normalizer.codedConceptCount();
        assertTrue(coded >= 40, "受控编码覆盖率应覆盖主流指标，实际 " + coded);
    }
}
