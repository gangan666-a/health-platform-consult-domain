package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 参考范围判定：HIGH/NORMAL/LOW/UNKNOWN（E-7 / B-4，不猜默认）。 */
class RangeParserTest {

    @Test
    void inRange_isNormal() {
        assertEquals(IndicatorStatus.NORMAL, RangeParser.computeStatus("6.5", "3.5-9.5"));
    }

    @Test
    void belowRange_isLow() {
        assertEquals(IndicatorStatus.LOW, RangeParser.computeStatus("3.0", "3.5-9.5"));
    }

    @Test
    void aboveRange_isHigh() {
        assertEquals(IndicatorStatus.HIGH, RangeParser.computeStatus("10.5", "3.5-9.5"));
    }

    @Test
    void aboveSingleUpperBound_isHigh() {
        assertEquals(IndicatorStatus.HIGH, RangeParser.computeStatus("5.8", "<5.2"));
    }

    @Test
    void aboveSingleLowerBound_isNormal() {
        assertEquals(IndicatorStatus.NORMAL, RangeParser.computeStatus("6.0", ">5"));
    }

    @Test
    void unparseableValue_isUnknown() {
        assertEquals(IndicatorStatus.UNKNOWN, RangeParser.computeStatus("abc", "3.5-9.5"));
    }

    @Test
    void blankRange_isUnknown() {
        assertEquals(IndicatorStatus.UNKNOWN, RangeParser.computeStatus("6.5", ""));
    }

    @Test
    void stripsUnitAndChineseBrackets() {
        assertEquals(IndicatorStatus.NORMAL, RangeParser.computeStatus("6.5", "（3.5-9.5）×10^9/L"));
    }
}
