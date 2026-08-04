package com.example.mall.recommendservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 智能导购服务：无数据库、无页面，纯 REST。
 * 接收「已购商品 + 全量商品目录」，调用 DeepSeek 大模型挑选互补商品并给出推荐理由；
 * 无 API Key 或调用失败时自动回退到关键词互补规则推荐。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RecommendServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendServiceApplication.class, args);
    }
}
