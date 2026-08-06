package com.example.mall.orderservice;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.orderservice.client.PaymentClient;
import com.example.mall.orderservice.client.ProductClient;
import com.example.mall.orderservice.client.RecommendClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 功能/集成测试：@SpringBootTest 全上下文 + H2 内存库（MySQL 兼容模式）。
 * 下游 ProductClient/PaymentClient/RecommendClient 用 @MockBean 替换避免真实网络。
 * 真实 OrderService + OrderRepository（H2）串联完整业务流。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductClient productClient;

    @MockBean
    private PaymentClient paymentClient;

    @MockBean
    private RecommendClient recommendClient;

    private ProductDTO product(Long id, String name, BigDecimal price, int stock) {
        ProductDTO p = new ProductDTO();
        p.setId(id);
        p.setName(name);
        p.setDescription("描述");
        p.setPrice(price);
        p.setStock(stock);
        p.setImageUrl("📱");
        p.setCategory("手机");
        return p;
    }

    private RecommendationItemDTO rec() {
        RecommendationItemDTO r = new RecommendationItemDTO();
        r.setProductId(9L);
        r.setProductName("苹果 AirPods Pro 2");
        r.setPrice(new BigDecimal("1899.00"));
        r.setImageUrl("🎧");
        r.setReason("买了手机，再配一副 AirPods，通勤更沉浸");
        r.setSource("rule");
        return r;
    }

    private String submitOrder(int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", String.valueOf(quantity))
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertNotNull(location);
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void stubProductForOrder(int stock) {
        ProductDTO phone = product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), stock);
        when(productClient.getProduct(1L)).thenReturn(phone);
        when(productClient.deductStock(1L, 1)).thenReturn(phone);
        when(productClient.listProducts()).thenReturn(List.of(phone));
    }

    /* ==================== 完整链路 ==================== */

    @Test
    void fullFlow_browse_confirm_order_pay_paySuccess_withRecommendation() throws Exception {
        stubProductForOrder(10);
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of(rec()));
        when(recommendClient.recommend(any(), any())).thenReturn(List.of(rec()));
        when(paymentClient.executePayment(anyString(), anyString(), any(), any())).thenReturn(null);

        // 确认单（GET /order/confirm）
        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")));

        // 提交下单（mock 扣库存成功）→ 重定向订单详情
        String orderNo = submitOrder(1);

        // 订单详情 CREATED + 同类推荐
        mockMvc.perform(get("/order/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("待支付")))
                .andExpect(content().string(containsString("去支付")))
                .andExpect(content().string(containsString("苹果 AirPods Pro 2")));

        // 发起支付（mock 支付服务）→ 重定向成功页
        mockMvc.perform(post("/order/{orderNo}/pay", orderNo).param("payMethod", "支付宝"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/" + orderNo + "/pay/success"));

        // 支付确认回调置 PAID
        mockMvc.perform(post("/api/orders/{orderNo}/confirm-payment", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\",\"payCardTail\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 支付成功页 200 含推荐（同类 + 互补）
        mockMvc.perform(get("/order/{orderNo}/pay/success", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("支付成功")))
                .andExpect(content().string(containsString("同类商品推荐")))
                .andExpect(content().string(containsString("你可能还需要")))
                .andExpect(content().string(containsString("苹果 AirPods Pro 2")));

        // 断言原子扣库存被调用
        verify(productClient).deductStock(1L, 1);
    }

    /* ==================== 下单-取消链路（回补库存） ==================== */

    @Test
    void cancelFlow_createdOrder_cancelled_restoresStock() throws Exception {
        ProductDTO phone = product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10);
        when(productClient.getProduct(1L)).thenReturn(phone);
        when(productClient.deductStock(1L, 2)).thenReturn(phone);

        String orderNo = submitOrder(2);

        mockMvc.perform(post("/order/{orderNo}/cancel", orderNo))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders"));

        // 逐明细回补库存（数量 2）被调用
        verify(productClient).restoreStock(1L, 2);

        // 取消后状态为 CANCELLED，访问收银台被拒
        mockMvc.perform(get("/order/{orderNo}/pay", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("订单已取消，无法支付")));

        // 取消后再取消 → 拒绝（不重复回补库存）
        mockMvc.perform(post("/order/{orderNo}/cancel", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("订单已取消")));
    }

    /* ==================== 重复 confirm-payment 幂等 ==================== */

    @Test
    void confirmPayment_idempotent_repeatedCallback_paidStays() throws Exception {
        stubProductForOrder(10);
        String orderNo = submitOrder(1);

        String body = "{\"payMethod\":\"支付宝\",\"payCardTail\":\"1234\"}";
        mockMvc.perform(post("/api/orders/{orderNo}/confirm-payment", orderNo)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 重复回调（支付服务重试）→ 200 且状态不变
        mockMvc.perform(post("/api/orders/{orderNo}/confirm-payment", orderNo)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 查询确认状态仍为 PAID、明细仍在
        mockMvc.perform(get("/api/orders/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payMethod").value("支付宝"))
                .andExpect(jsonPath("$.items[0].productId").value(1));
    }

    /* ==================== 推荐服务不可用：页面仍 200、推荐区隐藏 ==================== */

    /**
     * 支付成功页有 exceptionally 兜底：RecommendClient 抛异常 → 页面仍 200、推荐区隐藏。
     */
    @Test
    void recommendFailure_paySuccess_still200AndHidden() throws Exception {
        stubProductForOrder(10);
        when(recommendClient.recommendSimilar(any(), any()))
                .thenThrow(new RuntimeException("recommend down"));
        when(recommendClient.recommend(any(), any()))
                .thenThrow(new RuntimeException("recommend down"));

        String orderNo = submitOrder(1);

        // 置 PAID（否则 pay/success 会重定向）
        mockMvc.perform(post("/api/orders/{orderNo}/confirm-payment", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"支付宝\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/order/{orderNo}/pay/success", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("支付成功")))
                // 注：模板内嵌 HTML 注释含「同类商品推荐/你可能还需要」，不能以此断言隐藏；
                // 改而断言推荐内容（rec() 的商品名）未渲染。
                .andExpect(content().string(notContains("苹果 AirPods Pro 2")));
    }

    /**
     * 订单详情页推荐降级：RecommendClient 返回空（生产语义=吞异常）→ 页面 200、推荐区隐藏。
     * 注：order 详情页 viewOrder 无独立防御层，仅依赖 RecommendClient 内部吞异常；
     * 若直接抛异常会渲染 error 页（健壮性观察项，见报告）。
     */
    @Test
    void recommendFailure_orderDetail_still200AndHidden() throws Exception {
        stubProductForOrder(10);
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        String orderNo = submitOrder(1);

        mockMvc.perform(get("/order/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("订单详情")))
                // 注：模板内嵌 HTML 注释含「同类商品推荐」字样，不能以此断言隐藏；
                // 改而断言推荐内容（rec() 的商品名 / 优选角标）未渲染。
                .andExpect(content().string(notContains("苹果 AirPods Pro 2")))
                .andExpect(content().string(notContains("同类优选")));
    }

    /* ==================== 支付服务不可用 ==================== */

    /** 发起支付失败（下游 PaymentClient 抛业务异常）→ 不 500，error 页回显，订单保持 CREATED 可重试 */
    @Test
    void paymentClientFailure_payDoesNot500_orderStaysCreated() throws Exception {
        stubProductForOrder(10);
        when(paymentClient.executePayment(anyString(), anyString(), any(), any()))
                .thenThrow(new BusinessException("支付服务不可用，请稍后重试"));

        String orderNo = submitOrder(1);

        mockMvc.perform(post("/order/{orderNo}/pay", orderNo).param("payMethod", "支付宝"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("支付服务不可用，请稍后重试")));

        // 订单保持 CREATED（可重试）
        mockMvc.perform(get("/api/orders/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    private static org.hamcrest.Matcher<String> notContains(String substring) {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(substring));
    }
}
