package com.example.mall.common.trace;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 动态埋点 AOP 切面：对 @RestController / @Service 的每个方法织入 ENTER / EXIT / ERROR 三段日志。
 * <p>
 * 特性：
 * <ul>
 *   <li>不标 @Component，由 {@link TraceAutoConfiguration} 注册为 @Bean（见 {@code AutoConfiguration.imports}）；</li>
 *   <li>{@code @Order(HIGHEST_PRECEDENCE)} 保证最先执行、最后退出，不影响业务事务等切面排序；</li>
 *   <li>日志消息内直接内嵌 traceId（独立 logger 名 {@code TRACE}，INFO 级），
 *       可用 {@code grep '\[TRACE\]'} 一键提取全链路日志，无需改动各服务日志配置；</li>
 *   <li>使用 {@code @Around}，成功/异常都记耗时，异常记录后原样重抛，绝不吞异常。</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceAspect {

    private static final Logger log = LoggerFactory.getLogger("TRACE");

    /** 参数安全摘要截断长度 */
    private static final int MAX_ABBREV = 80;

    /** 超过该数量的参数不再逐项展开，只记数量 */
    private static final int MAX_ARGS = 5;

    @Around("@within(org.springframework.web.bind.annotation.RestController) "
            + "|| @within(org.springframework.stereotype.Service)")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        String tid = TraceContext.getTraceId();
        if (tid == null || tid.isEmpty()) {
            // WS/定时等未经过 TraceFilter 的线程，无 traceId 记为 "-"
            tid = "-";
        }

        String args = safeArgs(pjp.getArgs());
        log.info("[TRACE] traceId={} {}.{}() ENTER args=[{}]", tid, className, methodName, args);

        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[TRACE] traceId={} {}.{}() EXIT elapsed={}ms result={}",
                    tid, className, methodName, elapsed, safeResult(result));
            return result;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[TRACE] traceId={} {}.{}() ERROR ex={}: {} elapsed={}ms",
                    tid, className, methodName, t.getClass().getName(), safeMessage(t.getMessage()), elapsed);
            throw t;
        }
    }

    /** 参数安全摘要：每个参数取 {@code 类型:值}，逗号拼接；null/超长只记数量 */
    private String safeArgs(Object[] args) {
        if (args == null) {
            return "0 args";
        }
        if (args.length == 0) {
            return "";
        }
        if (args.length > MAX_ARGS) {
            return args.length + " args";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(safeArg(args[i]));
        }
        return sb.toString();
    }

    private String safeArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        String value = String.valueOf(arg);
        if (value.length() > MAX_ABBREV) {
            value = value.substring(0, MAX_ABBREV);
        }
        return arg.getClass().getSimpleName() + ":" + value;
    }

    /** 返回值摘要：null / 超长截断 */
    private String safeResult(Object result) {
        if (result == null) {
            return "null";
        }
        String value = String.valueOf(result);
        if (value.length() > MAX_ABBREV) {
            value = value.substring(0, MAX_ABBREV);
        }
        return value;
    }

    private String safeMessage(String message) {
        return message == null ? "" : message;
    }
}
