package com.example.mall.agentservice.report;

/** 指标异常标记：高/正常/低/无法判定（UNKNOWN 不猜默认，fail-closed）。 */
public enum IndicatorStatus {

    HIGH("偏高"),
    NORMAL("正常"),
    LOW("偏低"),
    UNKNOWN("无法判定");

    private final String display;

    IndicatorStatus(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
