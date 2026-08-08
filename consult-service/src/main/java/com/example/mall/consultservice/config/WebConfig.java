package com.example.mall.consultservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS：放行 order-service 页面（localhost/127.0.0.1 任意端口）直连问诊 REST。
 * 实时 WS 走独立端口（:8086）与握手期 OriginCheck（P2-02），不在此范围。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/consult/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
