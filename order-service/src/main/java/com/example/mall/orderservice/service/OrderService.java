package com.example.mall.orderservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.orderservice.client.PaymentClient;
import com.example.mall.orderservice.client.ProductClient;
import com.example.mall.orderservice.model.Order;
import com.example.mall.orderservice.model.OrderItem;
import com.example.mall.orderservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单业务：编排商品服务（库存）与支付服务，自身只负责订单表。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final PaymentClient paymentClient;

    public OrderService(OrderRepository orderRepository, ProductClient productClient, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.paymentClient = paymentClient;
    }

    /* ---------- 商品（代理到商品服务，供页面使用） ---------- */

    @Transactional(readOnly = true)
    public List<ProductDTO> listProducts() {
        return productClient.listProducts();
    }

    @Transactional(readOnly = true)
    public ProductDTO findProduct(Long id) {
        return productClient.getProduct(id);
    }

    /* ---------- 订单 ---------- */

    /**
     * 下单：校验数量 → 调用商品服务原子扣减库存 → 本地生成订单 + 明细快照。
     * 跨服务无法保证强一致：若本地保存失败，尽力回补库存（补偿），
     * 崩溃窗口内可能出现扣减未回补，属演示级的分布式事务限制（Saga/Outbox 为后续工作）。
     */
    @Transactional
    public Order createOrder(Long productId, int quantity,
                             String receiverName, String receiverPhone, String receiverAddress) {
        if (quantity <= 0) {
            throw new BusinessException("购买数量必须大于 0");
        }
        ProductDTO product = productClient.getProduct(productId);
        // 商品价格异常（null/≤0）拒绝下单，避免产生零/负金额订单（PRD EX-6）
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("商品价格异常，无法下单");
        }
        if (product.getStock() < quantity) {
            throw new BusinessException("库存不足，当前库存仅 " + product.getStock() + " 件");
        }
        // 原子扣减（并发正确性唯一边界，失败会抛出库存不足异常）
        productClient.deductStock(productId, quantity);

        BigDecimal unitPrice = product.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        // 总金额必须为正（quantity≥1 且 unitPrice>0 时天然成立，防御脏数据）
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单金额异常，无法下单");
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setReceiverAddress(receiverAddress);
        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(LocalDateTime.now());

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setUnitPrice(unitPrice);
        item.setQuantity(quantity);
        item.setSubtotal(totalAmount);
        order.getItems().add(item);

        try {
            return orderRepository.save(order);
        } catch (RuntimeException ex) {
            // 补偿：本地保存失败时回补库存（best-effort），先记录落库失败便于对账
            log.error("下单落库失败，开始补偿回补库存。orderNo={}, productId={}, quantity={}",
                    order.getOrderNo(), productId, quantity, ex);
            try {
                productClient.restoreStock(productId, quantity);
            } catch (RuntimeException restoreEx) {
                // 回补也失败：库存已扣但订单未落库，形成"已扣未补"悬挂库存。
                // 取舍：不在此抛出新异常（本地事务已回滚，抛出根因 ex 即可，避免掩盖主因），
                // 仅记 ERROR 日志留下可对账痕迹（含订单号/商品/数量，检索日志即可人工对账），
                // 保证不产生脏数据、路径可观测。对账兜底见方案 §9.3 日志规范 / 评审 R-07。
                log.error("库存回补失败，请人工对账。orderNo={}, productId={}, quantity={}",
                        order.getOrderNo(), productId, quantity, restoreEx);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Order findOrderByOrderNo(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        order.getItems().size(); // 提前初始化懒加载集合
        return order;
    }

    @Transactional(readOnly = true)
    public List<Order> listOrders() {
        List<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc();
        orders.forEach(order -> order.getItems().size());
        return orders;
    }

    /** 订单明细转 DTO（供 AI 智能导购：把已购商品发给推荐服务） */
    @Transactional(readOnly = true)
    public List<OrderItemDTO> listOrderItems(String orderNo) {
        Order order = findOrderByOrderNo(orderNo);
        return order.getItems().stream().map(this::toOrderItemDTO).toList();
    }

    /**
     * 取消订单：仅待支付订单可取消。先落库 CANCELLED 成功、后逐明细回补库存。
     *
     * 顺序取舍（对齐 createOrder 的补偿语义，评审 P1"取消孤儿回补不对称"）：
     * createOrder 是"先扣库存→后落库，落库失败→补偿回补"；cancelOrder 反向采用
     * "先落库 CANCELLED→后回补库存"：
     *  - save 成功：订单已置 CANCELLED（终态），状态机拒绝再次取消，堵死"二次回补虚增库存"最坏路径；
     *  - save 失败：本地事务回滚，订单在库仍为 CREATED，且回补代码根本不会执行——
     *    不会产生"已回补未取消"孤儿，也避免了用户再次取消/继续支付一个本应取消的订单；
     *  - 回补失败：订单已 CANCELLED 已提交、仅个别商品库存未回补（单边缺口）。
     *    取舍：不抛新异常（取消主流程已成功，抛异常会回滚"已取消"且掩盖根因），仅记 ERROR 日志
     *    （含 orderNo/productId/quantity）留下可对账痕迹，与 createOrder 补偿失败的取舍一致（P1-02 / R-07）。
     */
    @Transactional
    public Order cancelOrder(String orderNo) {
        Order order = findOrderByOrderNo(orderNo);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessException(order.getStatus() == OrderStatus.PAID ? "订单已支付，无法取消" : "订单已取消");
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        orderRepository.save(order);
        for (OrderItem item : order.getItems()) {
            try {
                productClient.restoreStock(item.getProductId(), item.getQuantity());
            } catch (RuntimeException restoreEx) {
                // 回补失败：订单已 CANCELLED 且将提交，库存未回补形成单边缺口。
                // 取舍：不抛新异常（避免把"已取消成功"回滚、且不掩盖回补根因），
                // 仅记 ERROR 日志（订单号/商品/数量，检索日志即可人工对账）。
                log.error("取消订单回补库存失败，请人工对账。orderNo={}, productId={}, quantity={}",
                        order.getOrderNo(), item.getProductId(), item.getQuantity(), restoreEx);
            }
        }
        return order;
    }

    /**
     * 支付确认回调（payment-service 调用）：把订单置为已支付。
     * 幂等：订单已是 PAID 时直接返回成功，避免支付服务重试导致报错。
     */
    @Transactional
    public OrderDTO confirmPayment(String orderNo, ConfirmPaymentRequest request) {
        Order order = findOrderByOrderNo(orderNo);
        if (order.getStatus() == OrderStatus.PAID) {
            return toOrderDTO(order);
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("订单已取消，无法支付");
        }
        order.setStatus(OrderStatus.PAID);
        order.setPayMethod(request.getPayMethod());
        if (request.getPayBank() != null && !request.getPayBank().isBlank()) {
            order.setPayBank(request.getPayBank());
        }
        if (request.getPayCardTail() != null && !request.getPayCardTail().isBlank()) {
            order.setPayCardTail(request.getPayCardTail());
        }
        order.setPaidAt(request.getPaidAt() != null ? request.getPaidAt() : LocalDateTime.now());
        return toOrderDTO(orderRepository.save(order));
    }

    /** 供支付服务拉取订单信息 */
    @Transactional(readOnly = true)
    public OrderDTO getOrderDTO(String orderNo) {
        return toOrderDTO(findOrderByOrderNo(orderNo));
    }

    private OrderDTO toOrderDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo(order.getOrderNo());
        dto.setReceiverName(order.getReceiverName());
        dto.setReceiverPhone(order.getReceiverPhone());
        dto.setReceiverAddress(order.getReceiverAddress());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setStatus(order.getStatus());
        dto.setStatusLabel(order.getStatus() == null ? null : order.getStatus().getLabel());
        dto.setPayMethod(order.getPayMethod());
        dto.setPayBank(order.getPayBank());
        dto.setPayCardTail(order.getPayCardTail());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setPaidAt(order.getPaidAt());
        dto.setCancelledAt(order.getCancelledAt());
        dto.setItems(order.getItems().stream().map(this::toOrderItemDTO).toList());
        return dto;
    }

    private OrderItemDTO toOrderItemDTO(OrderItem item) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setProductId(item.getProductId());
        dto.setProductName(item.getProductName());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setQuantity(item.getQuantity());
        dto.setSubtotal(item.getSubtotal());
        return dto;
    }

    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
