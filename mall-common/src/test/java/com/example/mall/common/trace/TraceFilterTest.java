package com.example.mall.common.trace;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TraceFilter 单元测试：直接 new（无构造参数），用 spring-test 的 Mock 对象走完整 filter 流程。
 * 不启动 Spring 上下文。
 */
class TraceFilterTest {

    @Test
    void continuesIncomingTraceIdAndClearsAfterRequest() throws ServletException, IOException {
        TraceContext.clear();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.TRACE_HEADER, "incoming-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] inside = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                inside[0] = TraceContext.getTraceId();
            }
        };

        new TraceFilter().doFilter(request, response, chain);

        assertEquals("incoming-1", inside[0], "chain 内应读到上游传入的 traceId");
        assertNull(TraceContext.getTraceId(), "请求结束后 MDC 应被清理，避免线程复用串线");
    }

    @Test
    void generatesNewTraceIdWhenHeaderAbsentAndClearsAfterRequest() throws ServletException, IOException {
        TraceContext.clear();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] inside = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                inside[0] = TraceContext.getTraceId();
            }
        };

        new TraceFilter().doFilter(request, response, chain);

        assertNotNull(inside[0], "无上游头时应生成新 traceId");
        assertNull(TraceContext.getTraceId(), "请求结束后 MDC 应被清理");
    }
}
