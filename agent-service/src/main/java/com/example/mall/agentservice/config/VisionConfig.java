package com.example.mall.agentservice.config;

import com.example.mall.agentservice.report.GlmVisionAnalyzer;
import com.example.mall.agentservice.report.MockVisionAnalyzer;
import com.example.mall.agentservice.report.VisionAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉通道装配：配置 agent.report.vision.api-key → GlmVisionAnalyzer（GLM-4.6V 真实视觉抽取，
 * 免费档 + 并发溢出/失败升级付费档）；未配置 → MockVisionAnalyzer（明确未识别，不虚构）。
 */
@Configuration
public class VisionConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionConfig.class);

    @Bean
    public VisionAnalyzer visionAnalyzer(VisionProperties properties) {
        if (properties.enabled()) {
            log.info("视觉通道启用：{}（免费档并发 {}，溢出升级 {}）",
                    properties.getModel(), properties.getFreeConcurrency(), properties.getFallbackModel());
            return new GlmVisionAnalyzer(properties);
        }
        return new MockVisionAnalyzer();
    }
}
