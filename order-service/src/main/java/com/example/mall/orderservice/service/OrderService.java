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
        if (product.getStock() < quantity) {
            throw new BusinessException("库存不足，当前库存仅 " + product.getStock() + " 件");
        }
        // 原子扣减（并发正确性唯一边界，失败会抛出库存不足异常）
        productClient.deductStock(productId, quantity);

        BigDecimal unitPrice = product.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));

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
            // 补偿：本地保存失败时回补库存（best-effort）
            try {
                productClient.restoreStock(productId, quantity);
            } catch (RuntimeException ignored) {
                // 补偿也失败则交由人工对账
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
     * 取消订单：仅待支付订单可取消，并逐明细回补库存。
     */
    @Transactional
    public Order cancelOrder(String orderNo) {
        Order order = findOrderByOrderNo(orderNo);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessException(order.getStatus() == OrderStatus.PAID ? "订单已支付，无法取消" : "订单已取消");
        }
        for (OrderItem item : order.getItems()) {
            productClient.restoreStock(item.getProductId(), item.getQuantity());
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        return orderRepository.save(order);
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
