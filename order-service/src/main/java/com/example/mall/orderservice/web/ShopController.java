package com.example.mall.orderservice.web;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.OrderStatus;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.orderservice.client.PaymentClient;
import com.example.mall.orderservice.client.RecommendClient;
import com.example.mall.orderservice.form.OrderForm;
import com.example.mall.orderservice.model.Order;
import com.example.mall.orderservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网页（BFF）：所有页面入口都在 order-service，商品/支付数据通过内部服务获取。
 */
@Controller
public class ShopController {

    private final OrderService orderService;
    private final PaymentClient paymentClient;
    private final RecommendClient recommendClient;

    public ShopController(OrderService orderService, PaymentClient paymentClient, RecommendClient recommendClient) {
        this.orderService = orderService;
        this.paymentClient = paymentClient;
        this.recommendClient = recommendClient;
    }

    /** 商品列表（数据来自商品服务） */
    @GetMapping({"/", "/products"})
    public String listProducts(Model model) {
        model.addAttribute("products", orderService.listProducts());
        return "products";
    }

    /** 商品详情（数据来自商品服务） */
    @GetMapping("/product/{id}")
    public String viewProduct(@PathVariable Long id, Model model) {
        model.addAttribute("product", orderService.findProduct(id));
        return "product";
    }

    /** 确认订单：展示商品与数量，填写收货信息 */
    @GetMapping("/order/confirm")
    public String confirmOrder(@RequestParam Long productId,
                               @RequestParam(defaultValue = "1") int quantity,
                               Model model) {
        ProductDTO product = orderService.findProduct(productId);
        if (quantity < 1) {
            quantity = 1;
        }
        if (quantity > product.getStock()) {
            throw new BusinessException("购买数量超过库存（当前库存 " + product.getStock() + " 件）");
        }

        OrderForm form = new OrderForm();
        form.setProductId(productId);
        form.setQuantity(quantity);

        model.addAttribute("product", product);
        model.addAttribute("quantity", quantity);
        model.addAttribute("total", product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        model.addAttribute("form", form);
        return "order-confirm";
    }

    /** 提交订单 */
    @PostMapping("/order")
    public String createOrder(@Valid @ModelAttribute("form") OrderForm form,
                              BindingResult bindingResult,
                              Model model) {
        if (bindingResult.hasErrors()) {
            ProductDTO product = orderService.findProduct(form.getProductId());
            int quantity = form.getQuantity() == null ? 1 : form.getQuantity();
            model.addAttribute("product", product);
            model.addAttribute("quantity", quantity);
            model.addAttribute("total", product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            return "order-confirm";
        }
        Order order = orderService.createOrder(form.getProductId(), form.getQuantity(),
                form.getReceiverName(), form.getReceiverPhone(), form.getReceiverAddress());
        return "redirect:/order/" + order.getOrderNo();
    }

    /** 订单详情：下方展示同类商品推荐（下单后即可看到） */
    @GetMapping("/order/{orderNo}")
    public String viewOrder(@PathVariable String orderNo, Model model) {
        model.addAttribute("order", orderService.findOrderByOrderNo(orderNo));
        List<OrderItemDTO> purchasedItems = orderService.listOrderItems(orderNo);
        List<ProductDTO> catalog = orderService.listProducts();
        model.addAttribute("similarRecommendations",
                recommendClient.recommendSimilar(purchasedItems, catalog));
        return "order";
    }

    /** 订单列表 */
    @GetMapping("/orders")
    public String listOrders(Model model) {
        model.addAttribute("orders", orderService.listOrders());
        return "orders";
    }

    /** 模拟支付页 */
    @GetMapping("/order/{orderNo}/pay")
    public String payPage(@PathVariable String orderNo, Model model) {
        Order order = orderService.findOrderByOrderNo(orderNo);
        if (order.getStatus() == OrderStatus.PAID) {
            return "redirect:/order/" + orderNo + "/pay/success";
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("订单已取消，无法支付");
        }
        model.addAttribute("order", order);
        return "pay";
    }

    /** 执行模拟支付：转发给支付服务，成功后跳转支付成功页 */
    @PostMapping("/order/{orderNo}/pay")
    public String pay(@PathVariable String orderNo,
                      @RequestParam(required = false) String payMethod,
                      @RequestParam(required = false) String payBank,
                      @RequestParam(required = false) String payCardNo,
                      RedirectAttributes redirectAttributes) {
        paymentClient.executePayment(orderNo,
                payMethod == null || payMethod.isBlank() ? "支付宝" : payMethod,
                payBank, payCardNo);
        redirectAttributes.addFlashAttribute("paid", true);
        return "redirect:/order/" + orderNo + "/pay/success";
    }

    /** 支付成功页：展示订单 + AI 智能导购推荐（同类 + 互补，推荐失败不影响本页，区块自动隐藏） */
    @GetMapping("/order/{orderNo}/pay/success")
    public String paySuccess(@PathVariable String orderNo, Model model) {
        model.addAttribute("order", orderService.findOrderByOrderNo(orderNo));
        // AI 智能导购：把已购商品 + 全量目录交给推荐服务，挑出同类与互补商品
        List<OrderItemDTO> purchasedItems = orderService.listOrderItems(orderNo);
        List<ProductDTO> catalog = orderService.listProducts();
        // 两次推荐并行调用，避免两个 DeepSeek 请求串行拖慢页面渲染
        CompletableFuture<List<RecommendationItemDTO>> similarFuture =
                CompletableFuture.supplyAsync(() -> recommendClient.recommendSimilar(purchasedItems, catalog));
        CompletableFuture<List<RecommendationItemDTO>> complementFuture =
                CompletableFuture.supplyAsync(() -> recommendClient.recommend(purchasedItems, catalog));
        model.addAttribute("similarRecommendations", similarFuture.join());
        model.addAttribute("recommendations", complementFuture.join());
        return "pay-success";
    }

    /** 取消订单 */
    @PostMapping("/order/{orderNo}/cancel")
    public String cancelOrder(@PathVariable String orderNo, RedirectAttributes redirectAttributes) {
        orderService.cancelOrder(orderNo);
        redirectAttributes.addFlashAttribute("msg", "订单已取消，库存已恢复");
        return "redirect:/orders";
    }
}
