package com.example.mall.common.trace;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.client.MockClientHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TraceContext 单元测试：不启动 Spring 上下文，纯静态方法/拦截器行为。
 */
class TraceContextTest {

    @Test
    void beginOrContinue_usesHeaderValueWhenPresent() {
        TraceContext.beginOrContinue("abc12345");
        assertEquals("abc12345", TraceContext.getTraceId());
        assertEquals("abc12345", MDC.get(TraceContext.MDC_KEY));
        TraceContext.clear();
    }

    @Test
    void beginOrContinue_generatesWhenHeaderAbsent() {
        TraceContext.clear();
        String tid = TraceContext.beginOrContinue(null);
        assertNotNull(tid);
        assertFalse(tid.isBlank());
        assertEquals(tid, TraceContext.getTraceId());

        // 空白 header 等同缺省，生成新 traceId
        TraceContext.beginOrContinue("   ");
        assertNotNull(TraceContext.getTraceId());
        TraceContext.clear();
    }

    @Test
    void mdcIsSetAndCleared() {
        TraceContext.beginOrContinue(null);
        assertNotNull(MDC.get(TraceContext.MDC_KEY));
        assertEquals(TraceContext.getTraceId(), MDC.get(TraceContext.MDC_KEY));

        TraceContext.clear();
        assertNull(MDC.get(TraceContext.MDC_KEY));
        assertNull(TraceContext.getTraceId());
    }

    @Test
    void interceptor_addsTraceHeaderWhenMdcHasTraceId() throws Exception {
        TraceContext.beginOrContinue("trace-1");
        MockClientHttpRequest request = new MockClientHttpRequest();
        TraceContext.interceptor().intercept(request, new byte[0], (req, body) -> null);
        assertEquals("trace-1", request.getHeaders().getFirst(TraceContext.TRACE_HEADER));
        TraceContext.clear();
    }

    @Test
    void interceptor_skipsHeaderWhenMdcEmpty() throws Exception {
        TraceContext.clear();
        MockClientHttpRequest request = new MockClientHttpRequest();
        TraceContext.interceptor().intercept(request, new byte[0], (req, body) -> null);
        assertNull(request.getHeaders().getFirst(TraceContext.TRACE_HEADER));
    }

    @Test
    void propagate_addsHeaderOnlyWhenTraceIdPresent() {
        // 有 traceId：写入头
        TraceContext.beginOrContinue("trace-2");
        MockClientHttpRequest request = new MockClientHttpRequest();
        TraceContext.propagate(request);
        assertEquals("trace-2", request.getHeaders().getFirst(TraceContext.TRACE_HEADER));
        TraceContext.clear();

        // 无 traceId：不写头
        MockClientHttpRequest request2 = new MockClientHttpRequest();
        TraceContext.propagate(request2);
        assertNull(request2.getHeaders().getFirst(TraceContext.TRACE_HEADER));
    }
}
