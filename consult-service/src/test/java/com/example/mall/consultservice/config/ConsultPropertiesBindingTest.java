package com.example.mall.consultservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ConsultProperties 绑定实证测试（不启动上下文，用 Spring Binder 直接绑定）。
 * <p>
 * 背景（评审 R-18 / P2 缺陷修复）：{@code wsPort} 字段的规范绑定名是平铺 kebab-case
 * {@code consult.ws-port}。生产 application.properties 曾用点分 {@code consult.ws.port}——
 * 按 Spring Boot 松散绑定规则，点号是「嵌套属性分隔符」，{@code ws.port} 不会绑定到平铺字段
 * {@code wsPort}，仅因默认值恰好对齐才未暴露（配置是死配置）。已修复：生产/测试配置统一改为与字段
 * 一致的平铺 kebab 键（见 application.properties）。本测试实证「修复后配置真实生效」：给定【非默认值】
 * 必须取到该值，避免被默认值对齐的假通过掩盖。
 */
class ConsultPropertiesBindingTest {

    @Test
    @DisplayName("修复后生产键（平铺 kebab）全部绑定且取到非默认值")
    void productionKeysBind() {
        ConsultProperties p = new ConsultProperties();
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "consult.ws-port", "18086",
                "consult.ws-heartbeat-interval-seconds", "45",
                "consult.session-disconnect-grace-seconds", "120",
                "consult.session-idle-timeout-seconds", "3600",
                "consult.session-sweep-interval-seconds", "30",
                "consult.prescription-valid-days", "14",
                "consult.batch-queue-capacity", "999",
                "consult.batch-drain-threshold", "50",
                "consult.batch-drain-interval-ms", "250")));
        binder.bind("consult", Bindable.ofInstance(p));
        assertEquals(18086, p.getWsPort());
        assertEquals(45, p.getWsHeartbeatIntervalSeconds());
        assertEquals(120, p.getSessionDisconnectGraceSeconds());
        assertEquals(3600, p.getSessionIdleTimeoutSeconds());
        assertEquals(30, p.getSessionSweepIntervalSeconds());
        assertEquals(14, p.getPrescriptionValidDays());
        assertEquals(999, p.getBatchQueueCapacity());
        assertEquals(50, p.getBatchDrainThreshold());
        assertEquals(250, p.getBatchDrainIntervalMs());
    }

    @Test
    @DisplayName("平铺 kebab consult.ws-port → 绑定到 wsPort")
    void kebabBinds() {
        ConsultProperties p = new ConsultProperties();
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("consult.ws-port", "18086")));
        binder.bind("consult", Bindable.ofInstance(p));
        assertEquals(18086, p.getWsPort());
    }

    @Test
    @DisplayName("点分嵌套 consult.ws.port → 不绑定平铺 wsPort（绑定语义回归护栏，解释旧缺陷根因）")
    void dottedNestedDoesNotBindFlatField() {
        ConsultProperties p = new ConsultProperties();
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("consult.ws.port", "18086")));
        binder.bind("consult", Bindable.ofInstance(p));
        // 点号是嵌套分隔符 → 不绑定平铺字段 wsPort（保持默认 8086）。生产配置改用平铺 kebab 后不再依赖此语义。
        assertEquals(8086, p.getWsPort());
    }
}
