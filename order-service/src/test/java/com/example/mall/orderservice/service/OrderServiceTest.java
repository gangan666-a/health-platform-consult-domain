package com.example.mall.orderservice.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.ConsumePrescriptionItem;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.orderservice.client.ConsultClient;
import com.example.mall.orderservice.client.PaymentClient;
import com.example.mall.orderservice.client.ProductClient;
import com.example.mall.orderservice.model.Order;
import com.example.mall.orderservice.model.OrderItem;
import com.example.mall.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 核心业务层单元测试（JUnit5 + Mockito，不加载 Spring 上下文）。
 * <p>
 * 覆盖：下单（含 P1-03 价格校验 / P1-02 补偿回补与日志）、取消（状态机 + 回补）、
 * 支付确认（幂等三态）、查询（明细快照 + statusLabel）、orderNo 生成规则。
 * <p>
 * 说明：OrderService 使用构造器注入，此处直接 new 并传入 mock；
 * 日志断言通过 logback ListAppender 捕获 ERROR 日志，验证 P1-02 补偿失败留痕。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductClient productClient;
    @Mock
    private PaymentClient paymentClient;
    @Mock
    private ConsultClient consultClient;

    private OrderService orderService;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productClient, paymentClient, consultClient);
        logAppender = new ListAppender<>();
        logAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    /* ================= 工具方法 ================= */

    private ProductDTO product(long id, String name, String price, int stock) {
        ProductDTO p = new ProductDTO();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setCategory("手机");
        return p;
    }

    private OrderItem item(long productId, String name, String unitPrice, int quantity, String subtotal) {
        OrderItem it = new OrderItem();
        it.setProductId(productId);
        it.setProductName(name);
        it.setUnitPrice(new BigDecimal(unitPrice));
        it.setQuantity(quantity);
        it.setSubtotal(new BigDecimal(subtotal));
        return it;
    }

    private Order order(OrderStatus status, OrderItem... items) {
        Order o = new Order();
        o.setOrderNo("SO202608061000011234");
        o.setReceiverName("张三");
        o.setReceiverPhone("13800000000");
        o.setReceiverAddress("北京市海淀区");
        o.setTotalAmount(new BigDecimal("0.30"));
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        for (OrderItem it : items) {
            it.setOrder(o);
            o.getItems().add(it);
        }
        return o;
    }

    private void stubSaveReturnsArgument() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<String> errorLogs() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /* ================= 下单 createOrder ================= */

    @Test
    void createOrder_success_createsOrderWithSnapshotAndPreciseAmount() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 100));
        stubSaveReturnsArgument();

        Order saved = orderService.createOrder(1L, 3, "张三", "13800000000", "北京市海淀区");

        // 状态与基础字段
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getReceiverName()).isEqualTo("张三");
        assertThat(saved.getReceiverPhone()).isEqualTo("13800000000");
        assertThat(saved.getReceiverAddress()).isEqualTo("北京市海淀区");
        assertThat(saved.getCreatedAt()).isNotNull();

        // 金额精度：0.1 × 3 = 0.3（BigDecimal 而非 double）
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.3"));
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.30"));

        // 明细快照
        assertThat(saved.getItems()).hasSize(1);
        OrderItem snap = saved.getItems().get(0);
        assertThat(snap.getProductId()).isEqualTo(1L);
        assertThat(snap.getProductName()).isEqualTo("iPhone 15 Pro");
        assertThat(snap.getUnitPrice()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(snap.getQuantity()).isEqualTo(3);
        assertThat(snap.getSubtotal()).isEqualByComparingTo(new BigDecimal("0.3"));

        // 扣减被调用一次
        verify(productClient).deductStock(1L, 3);
    }

    @Test
    void createOrder_stockBelowQuantity_rejectsWithoutDeductOrSave() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "100.00", 2));

        assertThatThrownBy(() -> orderService.createOrder(1L, 3, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("库存不足，当前库存仅 2 件");

        verify(productClient, never()).deductStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_deductStockFails_rejectsWithoutSavingOrRestoring() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "100.00", 100));
        // 模拟并发下原子扣减失败（影响行数 0 → 商品服务抛库存不足）
        when(productClient.deductStock(1L, 3))
                .thenThrow(new BusinessException("库存不足，当前库存仅 0 件"));

        assertThatThrownBy(() -> orderService.createOrder(1L, 3, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("库存不足，当前库存仅 0 件");

        verify(orderRepository, never()).save(any(Order.class));
        verify(productClient, never()).restoreStock(anyLong(), anyInt());
    }

    @Test
    void createOrder_nullPrice_rejects() {
        ProductDTO p = product(1L, "iPhone 15 Pro", "0.1", 100);
        p.setPrice(null);
        when(productClient.getProduct(1L)).thenReturn(p);

        assertThatThrownBy(() -> orderService.createOrder(1L, 1, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品价格异常，无法下单");

        verify(productClient, never()).deductStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_zeroPrice_rejects() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.00", 100));

        assertThatThrownBy(() -> orderService.createOrder(1L, 1, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品价格异常，无法下单");

        verify(productClient, never()).deductStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_negativePrice_rejects() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "-1.00", 100));

        assertThatThrownBy(() -> orderService.createOrder(1L, 1, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品价格异常，无法下单");

        verify(productClient, never()).deductStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_zeroQuantity_rejectsBeforeFetchingProduct() {
        assertThatThrownBy(() -> orderService.createOrder(1L, 0, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("购买数量必须大于 0");

        verify(productClient, never()).getProduct(anyLong());
        verify(productClient, never()).deductStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_negativeQuantity_rejectsBeforeFetchingProduct() {
        assertThatThrownBy(() -> orderService.createOrder(1L, -3, "张三", "138", "addr"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("购买数量必须大于 0");

        verify(productClient, never()).getProduct(anyLong());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_saveFails_compensatesByRestoringStockAndLogsError() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 100));
        RuntimeException dbEx = new RuntimeException("DB down");
        when(orderRepository.save(any(Order.class))).thenThrow(dbEx);

        // 落库失败 → 原异常上抛（根因不被掩盖）
        assertThatThrownBy(() -> orderService.createOrder(1L, 3, "张三", "138", "addr"))
                .isInstanceOf(RuntimeException.class)
                .isSameAs(dbEx);

        // 补偿：回补库存被调用（P1-02）
        verify(productClient).restoreStock(1L, 3);

        // 补偿前先记录落库失败日志（含订单信息）
        List<String> errors = errorLogs();
        assertThat(errors).anyMatch(m -> m.contains("下单落库失败") && m.contains("productId=1") && m.contains("quantity=3"));
    }

    @Test
    void createOrder_restoreFails_doesNotMaskRootCauseButLeavesErrorLog() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 100));
        RuntimeException dbEx = new RuntimeException("DB down");
        when(orderRepository.save(any(Order.class))).thenThrow(dbEx);
        doThrow(new BusinessException("商品服务不可用，请稍后重试"))
                .when(productClient).restoreStock(1L, 3);

        // 回补也失败：不抛回补异常掩盖根因，仍抛原始 save 异常（P1-02 取舍）
        assertThatThrownBy(() -> orderService.createOrder(1L, 3, "张三", "138", "addr"))
                .isInstanceOf(RuntimeException.class)
                .isSameAs(dbEx);

        // 回补确实被尝试
        verify(productClient).restoreStock(1L, 3);

        // 记录"回补失败请人工对账"ERROR 日志，含订单信息
        List<String> errors = errorLogs();
        assertThat(errors).anyMatch(m -> m.contains("库存回补失败") && m.contains("productId=1") && m.contains("quantity=3"));
    }

    @Test
    void createOrder_generatesWellFormedOrderNo() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 100));
        stubSaveReturnsArgument();

        Order saved = orderService.createOrder(1L, 1, "张三", "138", "addr");

        // 规则：SO + yyyyMMddHHmmss + 4位随机数
        assertThat(saved.getOrderNo()).matches("^SO\\d{18}$");
        assertThat(saved.getOrderNo()).hasSize(20);
        assertThat(saved.getOrderNo()).startsWith("SO");
    }

    @Test
    void createOrder_generatesDistinctOrderNosWithinBatch() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 1000));
        stubSaveReturnsArgument();

        List<String> orderNos = java.util.stream.Stream.of(
                        orderService.createOrder(1L, 1, "张三", "138", "addr"),
                        orderService.createOrder(1L, 1, "张三", "138", "addr"),
                        orderService.createOrder(1L, 1, "张三", "138", "addr"),
                        orderService.createOrder(1L, 1, "张三", "138", "addr"),
                        orderService.createOrder(1L, 1, "张三", "138", "addr"))
                .map(Order::getOrderNo)
                .toList();

        assertThat(orderNos).doesNotHaveDuplicates();
        orderNos.forEach(no -> assertThat(no).matches("^SO\\d{18}$"));
    }

    /* ================= Rx 处方药下单（F5：扣库存 → 消费处方 → 落单 + 补偿） ================= */

    private ProductDTO rxProduct() {
        ProductDTO p = product(7L, "处方药布洛芬", "10.00", 100);
        p.setRxFlag(true);
        return p;
    }

    private PrescriptionDTO prescription(String no, String orderNo) {
        PrescriptionDTO dto = new PrescriptionDTO();
        dto.setNo(no);
        dto.setConsumedOrderNo(orderNo);
        return dto;
    }

    /** Rx 商品成功下单：扣库存 + 消费处方（带 orderNo）+ 保存，订单绑定处方号 */
    @Test
    void createOrder_rxProduct_success_consumesPrescriptionAndSaves() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenAnswer(inv -> prescription("RX202608071234567890", inv.getArgument(1)));
        stubSaveReturnsArgument();

        Order saved = orderService.createOrder(7L, 2, "张三", "13800000000", "北京市朝阳区", "RX202608071234567890");

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getPrescriptionNo()).isEqualTo("RX202608071234567890");
        // 扣库存 → 消费处方（订单号归属，P1-02）→ 保存
        verify(productClient).deductStock(7L, 2);
        verify(consultClient).consumePrescription(eq("RX202608071234567890"), anyString(), any());
        verify(orderRepository).save(any(Order.class));
    }

    /** Rx 商品无处方单号 → fail-closed 拒绝，且已扣库存被回补 */
    @Test
    void createOrder_rxProduct_missingPrescriptionNo_rejectsAndRestoresStock() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("处方药必须填写处方单号");

        // 扣库存已发生（任务顺序：先扣库存）→ 拒绝后回补，不消费处方、不落单
        verify(productClient).deductStock(7L, 2);
        verify(productClient).restoreStock(7L, 2);
        verify(consultClient, never()).consumePrescription(anyString(), anyString(), any());
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** 扣库存成功但处方消费失败 → 回补库存（P1-01），再抛消费异常（fail-closed） */
    @Test
    void createOrder_rxProduct_consumeFails_restoresStockAndRethrows() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenThrow(new BusinessException("处方已使用，请重新问诊开具处方"));

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "RX202608071234567890"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("处方已使用，请重新问诊开具处方");

        verify(productClient).deductStock(7L, 2);
        verify(productClient).restoreStock(7L, 2); // 扣库存成功但消费失败 → 回补
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** Rx 商品落单失败 → 先恢复处方（USED→ACTIVE）、再回补库存（P1-01），抛原始异常 */
    @Test
    void createOrder_rxProduct_saveFails_restoresPrescriptionThenStock() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenAnswer(inv -> prescription("RX202608071234567890", inv.getArgument(1)));
        RuntimeException dbEx = new RuntimeException("DB down");
        when(orderRepository.save(any(Order.class))).thenThrow(dbEx);

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "RX202608071234567890"))
                .isInstanceOf(RuntimeException.class)
                .isSameAs(dbEx);

        // 补偿顺序：先恢复处方、再回补库存
        verify(consultClient).restorePrescription(eq("RX202608071234567890"), anyString());
        verify(productClient).restoreStock(7L, 2);

        // 补偿前先记录落库失败日志
        List<String> errors = errorLogs();
        assertThat(errors).anyMatch(m -> m.contains("下单落库失败") && m.contains("productId=7"));
    }

    /** Rx 商品落单失败且处方恢复也失败 → 不掩盖根因，留 ERROR 日志（含处方号/订单号）对账 */
    @Test
    void createOrder_rxProduct_restoreFails_doesNotMaskRootCauseAndLogs() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenAnswer(inv -> prescription("RX202608071234567890", inv.getArgument(1)));
        when(consultClient.restorePrescription(anyString(), anyString()))
                .thenThrow(new BusinessException("问诊服务不可用，请稍后重试"));
        RuntimeException dbEx = new RuntimeException("DB down");
        when(orderRepository.save(any(Order.class))).thenThrow(dbEx);

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "RX202608071234567890"))
                .isInstanceOf(RuntimeException.class)
                .isSameAs(dbEx);

        // 处方恢复失败不抛新异常（避免掩盖根因），回补库存仍执行
        verify(consultClient).restorePrescription(eq("RX202608071234567890"), anyString());
        verify(productClient).restoreStock(7L, 2);
        List<String> errors = errorLogs();
        assertThat(errors).anyMatch(m -> m.contains("处方恢复失败")
                && m.contains("prescriptionNo=RX202608071234567890"));
    }

    /** Rx 成功下单：消费处方调用参数精确匹配（prescriptionNo/orderNo/items 明细），保存的订单绑定同一 orderNo 与处方号快照 */
    @Test
    void createOrder_rxProduct_consumeArgsExactAndSavedOrderCarriesPrescriptionNo() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenAnswer(inv -> prescription("RX202608071234567890", inv.getArgument(1)));
        stubSaveReturnsArgument();

        Order saved = orderService.createOrder(7L, 2, "张三", "13800000000", "北京市朝阳区", "RX202608071234567890");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConsumePrescriptionItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> orderNoCaptor = ArgumentCaptor.forClass(String.class);
        verify(consultClient).consumePrescription(eq("RX202608071234567890"), orderNoCaptor.capture(), itemsCaptor.capture());

        // consume 参数精确：prescriptionNo 精确、items 明细匹配（productId + quantity）
        List<ConsumePrescriptionItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getProductId()).isEqualTo(7L);
        assertThat(items.get(0).getQuantity()).isEqualTo(2);
        String consumedOrderNo = orderNoCaptor.getValue();
        assertThat(consumedOrderNo).matches("^SO\\d{18}$");

        // 保存的订单：同一 orderNo + 处方号快照已持久化（AC-P2-1）
        ArgumentCaptor<Order> savedCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(savedCaptor.capture());
        Order persisted = savedCaptor.getValue();
        assertThat(persisted.getOrderNo()).isEqualTo(consumedOrderNo);
        assertThat(persisted.getPrescriptionNo()).isEqualTo("RX202608071234567890");
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getPrescriptionNo()).isEqualTo("RX202608071234567890");

        // 正常路径不回补库存
        verify(productClient, never()).restoreStock(anyLong(), anyInt());
    }

    /** OTC 商品（即使携带处方号）→ 不消费处方、不恢复处方、订单不绑定处方号（老流程不回归，AC-P2-12） */
    @Test
    void createOrder_otcProduct_neverConsumesPrescriptionAndIgnoresPrescriptionNo() {
        when(productClient.getProduct(1L)).thenReturn(product(1L, "iPhone 15 Pro", "0.1", 100));
        stubSaveReturnsArgument();

        Order saved = orderService.createOrder(1L, 2, "张三", "138", "addr", "RX202608071234567890");

        // 关键断言：非 Rx 商品绝不触达处方服务（verify never）
        verify(consultClient, never()).consumePrescription(anyString(), anyString(), any());
        verify(consultClient, never()).restorePrescription(anyString(), anyString());
        verify(productClient).deductStock(1L, 2);
        verify(orderRepository).save(any(Order.class));
        // 非 Rx 订单不写处方号快照
        assertThat(saved.getPrescriptionNo()).isNull();
    }

    /** Rx 商品空白处方号（仅空格）→ 同样 fail-closed 拒绝并回补库存（缺失边界） */
    @Test
    void createOrder_rxProduct_blankPrescriptionNo_rejectsAndRestoresStock() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("处方药必须填写处方单号");

        verify(productClient).deductStock(7L, 2);
        verify(productClient).restoreStock(7L, 2);
        verify(consultClient, never()).consumePrescription(anyString(), anyString(), any());
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** consult 服务不可用 → Rx fail-closed 拒绝，回补库存、不落单（AC-IM-6 / NFR-11） */
    @Test
    void createOrder_rxProduct_consultUnavailable_rejectsFailClosed() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenThrow(new BusinessException("问诊服务不可用，请稍后重试"));

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "RX202608071234567890"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("问诊服务不可用，请稍后重试");

        verify(productClient).deductStock(7L, 2);
        verify(productClient).restoreStock(7L, 2);
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** 落单失败补偿：恢复处方使用与消费同一 orderNo（P1-01 时序/归属一致），再回补库存 */
    @Test
    void createOrder_rxProduct_saveFails_restoreUsesSameOrderNoAsConsume() {
        when(productClient.getProduct(7L)).thenReturn(rxProduct());
        when(consultClient.consumePrescription(anyString(), anyString(), any()))
                .thenAnswer(inv -> prescription("RX202608071234567890", inv.getArgument(1)));
        when(orderRepository.save(any(Order.class))).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> orderService.createOrder(7L, 2, "张三", "138", "addr", "RX202608071234567890"))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<String> consumedOrderNo = ArgumentCaptor.forClass(String.class);
        verify(consultClient).consumePrescription(eq("RX202608071234567890"), consumedOrderNo.capture(), any());
        ArgumentCaptor<String> restoredOrderNo = ArgumentCaptor.forClass(String.class);
        verify(consultClient).restorePrescription(eq("RX202608071234567890"), restoredOrderNo.capture());
        // 恢复处方的订单号 = 消费时的订单号（同一尝试的归属），补偿不跨单
        assertThat(restoredOrderNo.getValue()).isEqualTo(consumedOrderNo.getValue());
        // 落单失败补偿：先恢复处方、再回补库存
        verify(productClient).restoreStock(7L, 2);
    }

    /* ================= 取消订单 cancelOrder ================= */

    @Test
    void cancelOrder_created_success_restoresEachItemAndSetsCancelled() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.CREATED,
                        item(1L, "iPhone 15 Pro", "0.1", 2, "0.2"),
                        item(2L, "AirPods Pro", "0.5", 1, "0.5"))));
        stubSaveReturnsArgument();

        Order cancelled = orderService.cancelOrder("SO202608061000011234");

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();

        // 逐明细回补库存
        verify(productClient).restoreStock(1L, 2);
        verify(productClient).restoreStock(2L, 1);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void cancelOrder_paid_rejectsWithoutRestoring() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.PAID, item(1L, "iPhone 15 Pro", "0.1", 2, "0.2"))));

        assertThatThrownBy(() -> orderService.cancelOrder("SO202608061000011234"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单已支付，无法取消");

        verify(productClient, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void cancelOrder_cancelled_rejectsWithoutReRestoring() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.CANCELLED, item(1L, "iPhone 15 Pro", "0.1", 2, "0.2"))));

        assertThatThrownBy(() -> orderService.cancelOrder("SO202608061000011234"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单已取消");

        // 已取消订单不允许再次取消，避免库存虚增
        verify(productClient, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void cancelOrder_notFound_rejects() {
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder("SO-NOT-EXISTS"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void cancelOrder_restoreFails_logsError_orderStaysCancelled() {
        Order order = order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 2, "0.2"));
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.of(order));
        stubSaveReturnsArgument();
        doThrow(new BusinessException("商品服务不可用，请稍后重试"))
                .when(productClient).restoreStock(1L, 2);

        // 先落库成功 → 订单为 CANCELLED；回补失败不抛回补异常（取消主流程已成功、事务将提交），
        // 仅记 ERROR 日志留下可对账痕迹（P1-02 取舍，与 createOrder 补偿失败一致）
        Order cancelled = orderService.cancelOrder("SO202608061000011234");

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        // 回补确实被尝试
        verify(productClient).restoreStock(1L, 2);

        // 记录"取消订单回补库存失败"ERROR 日志，含订单号/商品/数量，供人工对账
        List<String> errors = errorLogs();
        assertThat(errors).anyMatch(m -> m.contains("取消订单回补库存失败")
                && m.contains("orderNo=SO202608061000011234")
                && m.contains("productId=1")
                && m.contains("quantity=2"));
    }

    /**
     * 回归（评审 P1"取消孤儿回补不对称"）：cancelOrder 采用"先落库 CANCELLED 后回补库存"的顺序。
     * 当 save 失败（本地 DB 异常）时，本地事务回滚、订单在库仍为 CREATED（CANCELLED 未持久化），
     * 且回补代码根本不会执行 —— 不会产生"已回补未取消"孤儿，也就不会有二次取消再次回补导致的库存虚增。
     */
    @Test
    void cancelOrder_saveFails_doesNotRestoreStock_avoidsOrphanRefund() {
        Order order = order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 2, "0.2"));
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.of(order));
        RuntimeException dbEx = new RuntimeException("DB down");
        when(orderRepository.save(any(Order.class))).thenThrow(dbEx);

        assertThatThrownBy(() -> orderService.cancelOrder("SO202608061000011234"))
                .isInstanceOf(RuntimeException.class)
                .isSameAs(dbEx);

        // 新顺序：先落库后回补。save 失败 → 绝不允许发生回补（否则二次取消会再次回补、库存虚增）
        verify(productClient, never()).restoreStock(anyLong(), anyInt());
        // save 确实被尝试（携带 CANCELLED 状态），但抛出 → CANCELLED 未持久化，订单在库仍为 CREATED
        verify(orderRepository).save(any(Order.class));
    }

    /* ================= 支付确认 confirmPayment（幂等） ================= */

    @Test
    void confirmPayment_created_becomesPaid() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"))));
        stubSaveReturnsArgument();
        ConfirmPaymentRequest req = new ConfirmPaymentRequest("支付宝", null, null, LocalDateTime.of(2026, 8, 6, 10, 1));

        OrderDTO dto = orderService.confirmPayment("SO202608061000011234", req);

        assertThat(dto.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(dto.getStatusLabel()).isEqualTo("已支付");
        assertThat(dto.getPayMethod()).isEqualTo("支付宝");
        assertThat(dto.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 8, 6, 10, 1));
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void confirmPayment_paid_idempotent_returnsWithoutSecondWrite() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.PAID, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"))));
        ConfirmPaymentRequest req = new ConfirmPaymentRequest("支付宝", null, null, LocalDateTime.of(2026, 8, 6, 10, 2));

        OrderDTO dto = orderService.confirmPayment("SO202608061000011234", req);

        // 幂等命中：返回现状、不抛错、状态不变、不二次写
        assertThat(dto.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(dto.getStatusLabel()).isEqualTo("已支付");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void confirmPayment_cancelled_rejects() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.CANCELLED, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"))));
        ConfirmPaymentRequest req = new ConfirmPaymentRequest("支付宝", null, null, LocalDateTime.of(2026, 8, 6, 10, 2));

        assertThatThrownBy(() -> orderService.confirmPayment("SO202608061000011234", req))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单已取消，无法支付");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void confirmPayment_notFound_rejects() {
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
        ConfirmPaymentRequest req = new ConfirmPaymentRequest("支付宝", null, null, null);

        assertThatThrownBy(() -> orderService.confirmPayment("SO-NOT-EXISTS", req))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void confirmPayment_blankBankAndTailInRequest_keepsExistingValues() {
        Order order = order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"));
        order.setPayBank("招商银行");
        order.setPayCardTail("8888");
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.of(order));
        stubSaveReturnsArgument();
        // 回调 body 中银行/尾号为空（如非银行卡支付）时不应覆盖已有信息
        ConfirmPaymentRequest req = new ConfirmPaymentRequest("银行卡", "", "  ", LocalDateTime.now());

        OrderDTO dto = orderService.confirmPayment("SO202608061000011234", req);

        assertThat(dto.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(dto.getPayBank()).isEqualTo("招商银行");
        assertThat(dto.getPayCardTail()).isEqualTo("8888");
    }

    /* ================= 查询 ================= */

    @Test
    void findOrderByOrderNo_notFound_throwsBusinessException() {
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findOrderByOrderNo("SO-NOT-EXISTS"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void getOrderDTO_includesSnapshotAndStatusLabel() {
        Order order = order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"));
        when(orderRepository.findByOrderNo(anyString())).thenReturn(Optional.of(order));

        OrderDTO dto = orderService.getOrderDTO("SO202608061000011234");

        assertThat(dto.getOrderNo()).isEqualTo("SO202608061000011234");
        assertThat(dto.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(dto.getStatusLabel()).isEqualTo("待支付");
        assertThat(dto.getItems()).hasSize(1);
        OrderItemDTO it = dto.getItems().get(0);
        assertThat(it.getProductId()).isEqualTo(1L);
        assertThat(it.getProductName()).isEqualTo("iPhone 15 Pro");
        assertThat(it.getUnitPrice()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(it.getQuantity()).isEqualTo(3);
        assertThat(it.getSubtotal()).isEqualByComparingTo(new BigDecimal("0.3"));
    }

    @Test
    void listOrderItems_returnsItemSnapshot() {
        when(orderRepository.findByOrderNo(anyString()))
                .thenReturn(Optional.of(order(OrderStatus.CREATED,
                        item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"),
                        item(2L, "AirPods Pro", "0.5", 1, "0.5"))));

        List<OrderItemDTO> items = orderService.listOrderItems("SO202608061000011234");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getProductName()).isEqualTo("iPhone 15 Pro");
        assertThat(items.get(1).getProductName()).isEqualTo("AirPods Pro");
        assertThat(items.get(1).getSubtotal()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void listOrders_returnsAllWithItemsInitialized() {
        Order o1 = order(OrderStatus.CREATED, item(1L, "iPhone 15 Pro", "0.1", 3, "0.3"));
        Order o2 = order(OrderStatus.PAID, item(2L, "AirPods Pro", "0.5", 1, "0.5"));
        when(orderRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(o1, o2));

        List<Order> orders = orderService.listOrders();

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getItems()).hasSize(1);
        assertThat(orders.get(1).getItems()).hasSize(1);
    }
}
