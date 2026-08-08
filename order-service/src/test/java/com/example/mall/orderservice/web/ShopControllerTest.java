package com.example.mall.orderservice.web;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.orderservice.client.PaymentClient;
import com.example.mall.orderservice.client.RecommendClient;
import com.example.mall.orderservice.model.Order;
import com.example.mall.orderservice.model.OrderItem;
import com.example.mall.orderservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web（BFF）页面路由接口测试：mock service + clients，走真实 Thymeleaf 渲染。
 * 覆盖 FR-1 浏览 / FR-2 下单 / FR-4 支付 / FR-6 列表 / FR-7/8/9 推荐与降级。
 */
@WebMvcTest(ShopController.class)
class ShopControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

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

    private ProductDTO rxProduct(Long id, String name, BigDecimal price, int stock) {
        ProductDTO p = product(id, name, price, stock);
        p.setRxFlag(true);
        return p;
    }

    private Order order(OrderStatus status, String orderNo) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setStatus(status);
        o.setReceiverName("张三");
        o.setReceiverPhone("13800000000");
        o.setReceiverAddress("北京市朝阳区");
        o.setTotalAmount(new BigDecimal("1999.00"));
        o.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 0));
        if (status == OrderStatus.PAID) {
            o.setPayMethod("支付宝");
            o.setPaidAt(LocalDateTime.of(2026, 8, 6, 10, 5));
        }
        OrderItem item = new OrderItem();
        item.setId(1L);
        item.setOrder(o);
        item.setProductId(1L);
        item.setProductName("iPhone 15 Pro");
        item.setUnitPrice(new BigDecimal("1999.00"));
        item.setQuantity(1);
        item.setSubtotal(new BigDecimal("1999.00"));
        o.getItems().add(item);
        return o;
    }

    private OrderItemDTO itemDTO() {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setProductId(1L);
        dto.setProductName("iPhone 15 Pro");
        dto.setUnitPrice(new BigDecimal("1999.00"));
        dto.setQuantity(1);
        dto.setSubtotal(new BigDecimal("1999.00"));
        return dto;
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

    /* ==================== FR-1 商品浏览 / 详情 ==================== */

    @Test
    void listProducts_renders200WithProducts() throws Exception {
        when(orderService.listProducts()).thenReturn(List.of(
                product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10),
                product(2L, "华为 Mate 60", new BigDecimal("5999.00"), 5)
        ));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("商品浏览")))
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                .andExpect(content().string(containsString("华为 Mate 60")));
    }

    @Test
    void viewProduct_ok() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                .andExpect(content().string(containsString("库存 10 件")));
    }

    /** 商品不存在 → error 页（非白屏） */
    @Test
    void viewProduct_notExists_rendersErrorPage() throws Exception {
        when(orderService.findProduct(999L)).thenThrow(new BusinessException("商品不存在"));

        mockMvc.perform(get("/product/999"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("出错了")))
                .andExpect(content().string(containsString("商品不存在")));
    }

    /* ---- FR-D1 Rx 药品详情页：在线问诊购药 / OTC 普通购买 ---- */

    /** Rx 药品详情：展示「处方药」徽标 + 明确文案 + 「在线问诊购药」入口（AC-D1-3） */
    @Test
    void viewProduct_rx_showsConsultEntryAndRxBadge() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "阿莫西林胶囊", new BigDecimal("12.50"), 100));

        mockMvc.perform(get("/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("阿莫西林胶囊")))
                .andExpect(content().string(containsString("处方药")))
                .andExpect(content().string(containsString("在线问诊购药")))
                .andExpect(content().string(containsString("需医生在线问诊开具处方后凭处方单号购买")));
    }

    /** OTC 药品详情：普通购买入口，不提供在线问诊（AC-D1-2） */
    @Test
    void viewProduct_otc_showsDirectPurchaseOnly() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "布洛芬缓释胶囊", new BigDecimal("15.80"), 100));

        mockMvc.perform(get("/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("立即购买")))
                // 模板注释恒含「在线问诊购药/处方药」字样，须用渲染标记 rx-consult / rx-badge 断言未渲染
                .andExpect(content().string(not(containsString("rx-consult"))))
                .andExpect(content().string(not(containsString("rx-badge"))));
    }

    /**
     * Rx 药品详情页不应提供「立即购买」直达下单入口（PRD FR-D1 / AC-D1-3：按钮为「在线问诊购药」而非「立即购买」）。
     * 期望：Rx 详情无「立即购买」按钮；实际：模板无条件渲染「立即购买」（product.html 第 25-29 行 buy-form 无 th:if 守卫）
     * → 已知真实缺陷，见 Bug 报告（不上修生产代码）。
     */
    @Test
    void viewProduct_rx_shouldNotOfferDirectPurchase() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "阿莫西林胶囊", new BigDecimal("12.50"), 100));

        mockMvc.perform(get("/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("立即购买"))));
    }

    /* ==================== FR-2 确认订单 / 提交订单 ==================== */

    @Test
    void confirmOrder_ok() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                .andExpect(content().string(containsString("× 2")));
    }

    /** quantity 缺省 → 默认 1 */
    @Test
    void confirmOrder_quantityMissing_defaultsToOne() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/order/confirm").param("productId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("× 1")));
    }

    /**
     * quantity=0 或负数 → 按 PRD FR-2 步骤 1 重置为 1 并正常渲染确认页（不报错）。
     * 注：下单强校验（@Min/@Max）在 POST /order 上，见校验用例。
     */
    @Test
    void confirmOrder_quantityZero_resetsToOne() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("× 1")));
    }

    @Test
    void confirmOrder_quantityNegative_resetsToOne() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "-3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("× 1")));
    }

    /** quantity 超库存 → error 页 */
    @Test
    void confirmOrder_quantityExceedsStock_rendersError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 5));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("购买数量超过库存")));
    }

    /* ---- FR-P2 确认订单页：Rx 处方号输入框 ---- */

    /** Rx 确认订单页：展示处方号输入框 + 「前往在线问诊」入口（AC-P2-2 展示侧） */
    @Test
    void confirmOrder_rx_showsPrescriptionNoInput() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "阿莫西林胶囊", new BigDecimal("12.50"), 100));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("处方单号")))
                .andExpect(content().string(containsString("前往在线问诊")));
    }

    /** Rx 确认订单页：携带处方号 → 输入框预填（AC-P2-2） */
    @Test
    void confirmOrder_rx_withPrescriptionNo_prefills() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "阿莫西林胶囊", new BigDecimal("12.50"), 100));

        mockMvc.perform(get("/order/confirm").param("productId", "1").param("prescriptionNo", "RX202608071234567890"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RX202608071234567890")));
    }

    /** OTC 确认订单页：不展示处方号输入框（老流程不回归） */
    @Test
    void confirmOrder_otc_hidesPrescriptionNoInput() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(get("/order/confirm").param("productId", "1"))
                .andExpect(status().isOk())
                // 模板注释恒含「处方单号」字样，须用渲染标记 rx-prescription-group 断言输入框未渲染
                .andExpect(content().string(not(containsString("rx-prescription-group"))));
    }

    @Test
    void createOrder_ok_redirectsToOrderDetail() throws Exception {
        // 控制器提交前先查商品（Rx 判定），再调 6 参 createOrder
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));
        when(orderService.createOrder(1L, 2, "张三", "13800000000", "北京市朝阳区", null))
                .thenReturn(order(OrderStatus.CREATED, "SO202608061000011111"));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "2")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/SO202608061000011111"));
    }

    /** 库存不足 → error 页回显（不落订单） */
    @Test
    void createOrder_insufficientStock_rendersError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));
        when(orderService.createOrder(1L, 3, "张三", "13800000000", "北京市朝阳区", null))
                .thenThrow(new BusinessException("库存不足，当前库存仅 2 件"));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "3")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("库存不足，当前库存仅 2 件")));
    }

    /* ---- Rx 处方药：在线问诊购药 ---- */

    /** 处方药携带处方单号 → 正常下单（prescriptionNo 透传 6 参 createOrder） */
    @Test
    void createOrder_rxProduct_withPrescriptionNo_redirects() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "布洛芬", new BigDecimal("10.00"), 10));
        when(orderService.createOrder(1L, 1, "张三", "13800000000", "北京市朝阳区", "RX202608071234567890"))
                .thenReturn(order(OrderStatus.CREATED, "SO202608061000011111"));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区")
                        .param("prescriptionNo", "RX202608071234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/SO202608061000011111"));
    }

    /** 处方药缺处方单号 → 回填确认页并上屏单字段错误（fail-closed，PRD FR-D1），不落单 */
    @Test
    void createOrder_rxProduct_missingPrescriptionNo_rerendersConfirmWithError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "布洛芬", new BigDecimal("10.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("处方药必须填写处方单号")));

        verify(orderService, never()).createOrder(anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    /** consult 服务不可用 → Rx 下单被拒（error 页回显，fail-closed，AC-IM-6 / NFR-11），不允许绕过 */
    @Test
    void createOrder_rx_consultUnavailable_rendersErrorFailClosed() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(rxProduct(1L, "阿莫西林胶囊", new BigDecimal("12.50"), 10));
        when(orderService.createOrder(1L, 1, "张三", "13800000000", "北京市朝阳区", "RX202608071234567890"))
                .thenThrow(new BusinessException("问诊服务不可用，请稍后重试"));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区")
                        .param("prescriptionNo", "RX202608071234567890"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("问诊服务不可用，请稍后重试")));
    }

    /* ---- 表单字段非法：校验失败回填确认页 ---- */

    @Test
    void createOrder_receiverPhoneBlank_showsValidationError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", "张三").param("receiverPhone", "")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请填写手机号")));
    }

    @Test
    void createOrder_receiverNameBlank_showsValidationError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", " ").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请填写收货人姓名")));
    }

    @Test
    void createOrder_receiverAddressBlank_showsValidationError() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请填写收货地址")));
    }

    /**
     * quantity=0 → @Min 校验失败：回填确认页、不落单，且错误文案上屏（评审 P2 修复）。
     */
    @Test
    void createOrder_quantityZero_rejectsAndRerendersConfirm() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "0")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                // P2 修复：数量校验失败时错误必须可见（th:errors 渲染 @Min 文案）
                .andExpect(content().string(containsString("购买数量必须大于 0")));

        // 校验失败必须回填确认页，不得调用下单（未落单）
        verify(orderService, never()).createOrder(anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    /** quantity=1000 → @Max 校验失败：回填确认页、不落单，且错误文案上屏（评审 P2 修复） */
    @Test
    void createOrder_quantityOverLimit_rejectsAndRerendersConfirm() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1").param("quantity", "1000")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                // P2 修复：数量校验失败时错误必须可见（th:errors 渲染 @Max 文案）
                .andExpect(content().string(containsString("单次最多购买 999 件")));

        verify(orderService, never()).createOrder(anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    /** quantity 缺省（null）→ @NotNull 校验失败：回填确认页、不落单，且错误文案上屏（评审 P2 修复） */
    @Test
    void createOrder_quantityMissing_rejectsAndRerendersConfirm() throws Exception {
        when(orderService.findProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", new BigDecimal("7999.00"), 10));

        mockMvc.perform(post("/order")
                        .param("productId", "1")
                        .param("receiverName", "张三").param("receiverPhone", "13800000000")
                        .param("receiverAddress", "北京市朝阳区"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("确认订单")))
                .andExpect(content().string(containsString("iPhone 15 Pro")))
                // P2 修复：数量缺省（null）时错误必须可见（th:errors 渲染 @NotNull 文案）
                .andExpect(content().string(containsString("请填写购买数量")));

        verify(orderService, never()).createOrder(anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    /* ==================== FR-7 订单详情 + 同类推荐 ==================== */

    @Test
    void viewOrder_created_showsPayButton() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("订单详情")))
                .andExpect(content().string(containsString("去支付")))
                .andExpect(content().string(containsString("取消订单")))
                .andExpect(content().string(containsString("待支付")));
    }

    /** 已支付订单：不显示支付/取消按钮 */
    @Test
    void viewOrder_paid_hidesPayButton() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.PAID, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已支付")))
                .andExpect(content().string(not(containsString("去支付"))))
                .andExpect(content().string(not(containsString("取消订单"))));
    }

    /** 已取消订单：不显示支付/取消按钮 */
    @Test
    void viewOrder_cancelled_hidesPayButton() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CANCELLED, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已取消")))
                .andExpect(content().string(not(containsString("去支付"))));
    }

    /* ---- Rx 订单详情：处方号快照展示 ---- */

    /** Rx 订单详情：展示处方单号快照（order.html 处方单号区块） */
    @Test
    void viewOrder_rx_showsPrescriptionNo() throws Exception {
        Order o = order(OrderStatus.CREATED, "SO-RX-1");
        o.setPrescriptionNo("RX202608071234567890");
        when(orderService.findOrderByOrderNo("SO-RX-1")).thenReturn(o);
        when(orderService.listOrderItems("SO-RX-1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO-RX-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("处方单号")))
                .andExpect(content().string(containsString("RX202608071234567890")));
    }

    /** OTC 订单详情：不展示处方单号区块 */
    @Test
    void viewOrder_otc_hidesPrescriptionNo() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("处方单号"))));
    }

    /** 推荐为空（RecommendClient 返回空=降级）→ 推荐区块隐藏（th:if 生效） */
    @Test
    void viewOrder_recommendEmpty_sectionHidden() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                // 注：模板内嵌 HTML 注释含「同类商品推荐」字样，不能用它断言区块隐藏；
                // 改而断言仅当推荐区块真正渲染时才出现的内容（推荐商品名 / 优选角标）。
                .andExpect(content().string(not(containsString("苹果 AirPods Pro 2"))))
                .andExpect(content().string(not(containsString("同类优选"))));
    }

    /** 推荐有数据 → 区块展示 */
    @Test
    void viewOrder_recommendPresent_sectionShown() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of(rec()));

        mockMvc.perform(get("/order/SO1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("同类商品推荐")))
                .andExpect(content().string(containsString("苹果 AirPods Pro 2")));
    }

    /* ==================== FR-4 支付页 ==================== */

    @Test
    void payPage_created_rendersCashier() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));

        mockMvc.perform(get("/order/SO1/pay"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("收银台")))
                .andExpect(content().string(containsString("SO1")));
    }

    /** 已支付订单访问收银台 → 重定向支付成功页 */
    @Test
    void payPage_paid_redirectsToPaySuccess() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.PAID, "SO1"));

        mockMvc.perform(get("/order/SO1/pay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/SO1/pay/success"));
    }

    /** 已取消订单访问收银台 → error 页（订单已取消，无法支付） */
    @Test
    void payPage_cancelled_rendersError() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CANCELLED, "SO1"));

        mockMvc.perform(get("/order/SO1/pay"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("订单已取消，无法支付")));
    }

    /* ==================== 支付成功页 ==================== */

    @Test
    void paySuccess_paid_renders200WithRecommendations() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.PAID, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any())).thenReturn(List.of(rec()));
        when(recommendClient.recommend(any(), any())).thenReturn(List.of(rec()));

        mockMvc.perform(get("/order/SO1/pay/success"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("支付成功")))
                .andExpect(content().string(containsString("同类商品推荐")))
                .andExpect(content().string(containsString("你可能还需要")))
                .andExpect(content().string(containsString("苹果 AirPods Pro 2")));
    }

    /** 非 PAID（CREATED）访问支付成功页 → 重定向回订单详情（新修） */
    @Test
    void paySuccess_created_redirectsToOrderDetail() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CREATED, "SO1"));

        mockMvc.perform(get("/order/SO1/pay/success"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/SO1"));
    }

    /** 非 PAID（CANCELLED）访问支付成功页 → 重定向回订单详情 */
    @Test
    void paySuccess_cancelled_redirectsToOrderDetail() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.CANCELLED, "SO1"));

        mockMvc.perform(get("/order/SO1/pay/success"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order/SO1"));
    }

    /** 推荐服务抛异常 → 页面仍 200，推荐区块为空（exceptionally 兜底，NFR-2） */
    @Test
    void paySuccess_recommendThrows_still200AndHidden() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.PAID, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        when(recommendClient.recommendSimilar(any(), any()))
                .thenThrow(new RuntimeException("recommend down"));
        when(recommendClient.recommend(any(), any()))
                .thenThrow(new RuntimeException("recommend down"));

        mockMvc.perform(get("/order/SO1/pay/success"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("支付成功")))
                // 注：模板内嵌 HTML 注释含「同类商品推荐/你可能还需要」字样，不能以此断言隐藏；
                // 改而断言推荐内容（rec() 的商品名）未渲染。
                .andExpect(content().string(not(containsString("苹果 AirPods Pro 2"))));
    }

    /**
     * 推荐服务慢/超时（阻塞超 2s，触发 orTimeout）→ 页面仍 200、推荐区为空。
     * 确定性：用永不释放的 CountDownLatch 让 supplyAsync 阻塞，orTimeout(2s) 自动超时降级。
     */
    @Test
    void paySuccess_recommendSlow_timeoutStill200AndHidden() throws Exception {
        when(orderService.findOrderByOrderNo("SO1")).thenReturn(order(OrderStatus.PAID, "SO1"));
        when(orderService.listOrderItems("SO1")).thenReturn(List.of(itemDTO()));
        when(orderService.listProducts()).thenReturn(List.of());
        CountDownLatch latch = new CountDownLatch(1);
        when(recommendClient.recommendSimilar(any(), any())).thenAnswer(inv -> {
            latch.await(30, TimeUnit.SECONDS);
            return List.of();
        });
        when(recommendClient.recommend(any(), any())).thenAnswer(inv -> {
            latch.await(30, TimeUnit.SECONDS);
            return List.of();
        });

        try {
            mockMvc.perform(get("/order/SO1/pay/success"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("支付成功")))
                    // 注：模板内嵌 HTML 注释含「同类商品推荐/你可能还需要」字样，不能以此断言隐藏；
                    // 改而断言推荐内容（两种推荐的 rec() 商品名相同）未渲染。
                    .andExpect(content().string(not(containsString("苹果 AirPods Pro 2"))));
        } finally {
            latch.countDown(); // 释放 commonPool 阻塞线程
        }
    }

    /* ==================== FR-6 订单列表 ==================== */

    @Test
    void listOrders_rendersAllOrders() throws Exception {
        when(orderService.listOrders()).thenReturn(List.of(
                order(OrderStatus.CREATED, "SO1"),
                order(OrderStatus.PAID, "SO2"),
                order(OrderStatus.CANCELLED, "SO3")
        ));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("我的订单")))
                .andExpect(content().string(containsString("SO1")))
                .andExpect(content().string(containsString("SO2")))
                .andExpect(content().string(containsString("SO3")));
    }

    /** 空列表 → 空状态提示，不报错 */
    @Test
    void listOrders_empty_showsEmptyState() throws Exception {
        when(orderService.listOrders()).thenReturn(List.of());

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("还没有订单")));
    }
}
