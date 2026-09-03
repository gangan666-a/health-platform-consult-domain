package com.example.mall.agentservice.controller;

import com.example.mall.agentservice.config.LlmUsageMeter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 调用计量端点：近期调用明细（供应商/模型/token/时延/成败）+ 按供应商累计与成本估算。
 * 数据来自 RoutedChatModel 收口处的无侵入采集（全应用 LLM 调用均经过网关）。
 */
@RestController
@RequestMapping("/api/agents")
public class LlmUsageController {

    private final LlmUsageMeter meter;

    public LlmUsageController(LlmUsageMeter meter) {
        this.meter = meter;
    }

    @GetMapping("/llm-usage")
    public LlmUsageMeter.UsageSnapshot usage() {
        return meter.snapshot();
    }
}
