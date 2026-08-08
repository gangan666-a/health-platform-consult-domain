package com.example.mall.consultservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 问诊服务：REST（Spring MVC/Tomcat :8085）+ Netty WebSocket（:8086）双端口共存。
 * 会话/消息/处方三张表由 @Entity 自动建表（ddl-auto=update）。
 */
@SpringBootApplication
@EnableScheduling
public class ConsultServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsultServiceApplication.class, args);
    }
}
