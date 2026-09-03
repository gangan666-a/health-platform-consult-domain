package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指标单位归一：mg/dL↔mmol/L 等换算（值与参考范围联动）、已是标准单位/定性值不猜。 */
class UnitConverterTest {

    @Test
    void glucose_mgPerDl_convertedToMmol() {
        UnitConverter.WithRange r = UnitConverter.convertWithRange("GLU", "100", "mg/dL", "70-110");

        assertTrue(r.converted());
        assertEquals("5.55", r.value());
        assertEquals("mmol/L", r.unit());
        assertEquals("3.88-6.10", r.refRange(), "参考范围必须联动换算，否则比对错");
    }

    @Test
    void cholesterol_mgPerDl_converted() {
        UnitConverter.WithRange r = UnitConverter.convertWithRange("TC", "200", "mg/dl", "<200");

        assertTrue(r.converted());
        assertEquals("5.17", r.value());
        assertTrue(r.refRange().contains("5.17"), "单边上限同样换算: " + r.refRange());
    }

    @Test
    void uricAcid_mgPerDl_toUmol() {
        UnitConverter.WithRange r = UnitConverter.convertWithRange("UA", "6.0", "mg/dL", "3.5-7.2");

        assertTrue(r.converted());
        assertEquals("356.88", r.value());
        assertEquals("umol/L", r.unit());
    }

    @Test
    void alreadyCanonicalUnit_passthrough() {
        UnitConverter.WithRange r = UnitConverter.convertWithRange("GLU", "5.2", "mmol/L", "3.9-6.1");

        assertFalse(r.converted());
        assertEquals("5.2", r.value());
        assertEquals("3.9-6.1", r.refRange());
    }

    @Test
    void unknownIndicatorOrQualitative_passthrough() {
        assertFalse(UnitConverter.convertWithRange("WBC", "6.5", "10^9/L", "3.5-9.5").converted());
        assertFalse(UnitConverter.convertWithRange("HBSAG", "阳性", "", "").converted());
        assertFalse(UnitConverter.convertWithRange("GLU", "阳性", "mg/dL", "").converted(),
                "定性值不换算（不猜）");
    }

    @Test
    void umSymbolUnitRecognized() {
        // μmol/L 全角/希腊 μ 写法归一后不换算（已是标准单位）
        assertFalse(UnitConverter.convertWithRange("UA", "360", "μmol/L", "208-428").converted());
        // mmol/L 的 CRE 换算到 μmol/L
        UnitConverter.WithRange r = UnitConverter.convertWithRange("CRE", "0.09", "mmol/L", "");
        assertTrue(r.converted());
        assertEquals("90.00", r.value());
        assertEquals("umol/L", r.unit());
    }
}
