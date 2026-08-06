package com.example.mall.orderservice.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 服务间 RestClient 统一超时配置（评审 P1-01 / 设计 §0「服务间通信均配置超时」）。
 * <p>
 * 默认下游（商品/支付）connect 3s / read 5s；推荐服务下游可能等待 DeepSeek（最长 12s），
 * 单独放宽 read 至 12s，但必须有上限，避免任一慢响应拖垮 BFF 页面渲染（NFR-10 ≤3s）。
 */
@Configuration
public class RestClientConfig {

    /** 默认下游超时：商品服务 / 支付服务 */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(newRequestFactory(Duration.ofSeconds(5)));
    }

    /** 推荐服务：下游可能等 DeepSeek（12s），read 放宽，但仍有上限 */
    @Bean
    public RestClient.Builder recommendRestClientBuilder() {
        return RestClient.builder().requestFactory(newRequestFactory(Duration.ofSeconds(12)));
    }

    private SimpleClientHttpRequestFactory newRequestFactory(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
