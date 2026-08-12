package com.example.mall.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入站 HTTP 过滤：解析上游 {@code X-Trace-Id} 头进入当前线程 MDC，请求结束后清理。
 * <p>
 * 无构造参数、无任何配置项，零业务侵入。由 {@link TraceAutoConfiguration} 注册为普通 Filter Bean，
 * Spring Boot 会自动将其挂到 servlet 容器，对全部 REST 接口生效。
 */
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(TraceContext.TRACE_HEADER);
        TraceContext.beginOrContinue(header);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程可能被线程池复用，务必清理，避免 traceId 串线
            TraceContext.clear();
        }
    }
}
