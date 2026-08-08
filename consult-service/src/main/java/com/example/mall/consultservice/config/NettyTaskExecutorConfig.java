package com.example.mall.consultservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件循环「外」的任务执行器：把需要落库的裁决类操作（结束、开方、心跳时间戳）从 Netty 事件循环
 * 卸载到独立线程（ADR-IM-04：事件循环内绝不阻塞 IO）。消息高频路径仍走批量写队列，不走本执行器。
 */
@Configuration
public class NettyTaskExecutorConfig {

    private final AtomicInteger counter = new AtomicInteger();

    @Bean(destroyMethod = "shutdown")
    public ExecutorService nettyTaskExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "netty-task-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
