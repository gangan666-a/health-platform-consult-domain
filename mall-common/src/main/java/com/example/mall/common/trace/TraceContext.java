package com.example.mall.common.trace;

import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.HttpRequest;

import java.util.UUID;

/**
 * 动态埋点核心：traceId 的 MDC 存取助手 + 跨服务 HTTP 头传播。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>traceId 写入 SLF4J MDC（键名 {@value #MDC_KEY}），日志打印时无需改格式即可随行输出；</li>
 *   <li>跨服务调用通过 {@link #interceptor()} 返回的 {@link ClientHttpRequestInterceptor}
 *       读取 MDC 中的 traceId 并写入 HTTP 头 {@value #TRACE_HEADER}，下游由 {@link TraceFilter} 续用；</li>
 *   <li>{@link #propagate(ClientHttpRequest)} 提供可直接以方法引用使用的重载（两者逻辑一致）。</li>
 * </ul>
 */
public final class TraceContext {

    /** MDC 键名 */
    public static final String MDC_KEY = "traceId";

    /** 跨服务传播的 HTTP 头名 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** 本类为静态工具类，禁止实例化 */
    private TraceContext() {
    }

    /**
     * 开始（或延续）一次调用链：header 有值则续用该 traceId，否则生成新 traceId，并写入 MDC。
     *
     * @param headerValue 上游传入的 {@link #TRACE_HEADER} 头值，可为 null/空白
     * @return 当前生效的 traceId
     */
    public static String beginOrContinue(String headerValue) {
        String traceId = (headerValue != null && !headerValue.isBlank())
                ? headerValue.trim()
                : newTraceId();
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    /**
     * 生成新 traceId：UUID 取前 8 位十六进制，足够短且可读（低冲突、便于日志检索）。
     */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 读取当前线程 MDC 中的 traceId（无则返回 null）。
     */
    public static String getTraceId() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 清空当前线程 MDC 中的 traceId（请求结束、异常处理 finally 中调用）。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * 给 {@link ClientHttpRequest} 写入 {@link #TRACE_HEADER} 头：MDC 有 traceId 才写，无则跳过。
     * <p>
     * 注意：{@link ClientHttpRequestInterceptor#intercept} 的首参是 {@link HttpRequest}（宽于
     * {@link ClientHttpRequest}），方法引用直接匹配有难度，构建点统一改用 {@link #interceptor()}。
     */
    public static void propagate(ClientHttpRequest request) {
        String traceId = getTraceId();
        if (traceId != null && !traceId.isEmpty()) {
            request.getHeaders().set(TRACE_HEADER, traceId);
        }
    }

    /**
     * 供 RestClient 构建点使用的传播拦截器：{@code .requestInterceptor(TraceContext.interceptor())}。
     * <p>
     * 拦截器对每次出站请求读取 MDC：有 traceId 则加 {@link #TRACE_HEADER} 头，无则保持原样（不引入头），
     * 且始终把请求透传给下游执行链。
     */
    public static ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            String traceId = getTraceId();
            if (traceId != null && !traceId.isEmpty()) {
                request.getHeaders().set(TRACE_HEADER, traceId);
            }
            return execution.execute(request, body);
        };
    }
}
