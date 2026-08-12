package com.example.mall.common.trace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 动态埋点自动装配：把 {@link TraceFilter} 与 {@link TraceAspect} 注册为 Bean。
 * <p>
 * 关键背景：mall-common 的根包 {@code com.example.mall.common} 是各服务扫描根包
 * （{@code com.example.mall.xxxservice}）的兄弟包，放进本模块的普通 @Component 默认不会被服务拾取。
 * 因此这里使用 Spring Boot 3.5 的 {@link AutoConfiguration} 机制，
 * 并依靠 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 声明本类 FQN，使每个服务启动时自动加载本配置（所有服务均 compile 依赖 mall-common）。
 */
@AutoConfiguration
public class TraceAutoConfiguration {

    /** 入站请求：解析 X-Trace-Id → MDC，请求结束清理 */
    @Bean
    public TraceFilter traceFilter() {
        return new TraceFilter();
    }

    /** @RestController / @Service 方法织入 ENTER / EXIT / ERROR 日志 */
    @Bean
    public TraceAspect traceAspect() {
        return new TraceAspect();
    }
}
