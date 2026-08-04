package com.example.mall.productservice.controller;

import com.example.mall.common.model.ProductDTO;
import com.example.mall.productservice.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品服务 REST API
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductDTO> list() {
        return productService.listProducts();
    }

    @GetMapping("/{id}")
    public ProductDTO detail(@PathVariable Long id) {
        return productService.getProduct(id);
    }

    /** 扣减库存（下单时，order-service 调用） */
    @PostMapping("/{id}/stock/deduct")
    public ProductDTO deduct(@PathVariable Long id,
                             @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        return productService.deductStock(id, quantity);
    }

    /** 回补库存（取消订单时，order-service 调用） */
    @PostMapping("/{id}/stock/restore")
    public ProductDTO restore(@PathVariable Long id,
                              @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        return productService.restoreStock(id, quantity);
    }
}
