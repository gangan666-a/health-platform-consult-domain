package com.example.mall.productservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.productservice.model.Product;
import com.example.mall.productservice.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductDTO> listProducts() {
        return productRepository.findAllByOrderByIdAsc().stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductDTO getProduct(Long id) {
        return toDTO(findEntity(id));
    }

    /**
     * 扣减库存：先确认商品存在，再原子扣减；影响行数为 0 说明库存不足。
     * 这里是并发正确性的唯一边界，上游（order-service）的库存校验只是提示。
     */
    @Transactional
    public ProductDTO deductStock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("扣减数量必须大于 0");
        }
        Product product = findEntity(id);
        int affected = productRepository.deductStock(id, quantity);
        if (affected == 0) {
            throw new BusinessException("库存不足，当前库存仅 " + product.getStock() + " 件");
        }
        product.setStock(product.getStock() - quantity);
        return toDTO(product);
    }

    /** 回补库存（取消订单时） */
    @Transactional
    public ProductDTO restoreStock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("回补数量必须大于 0");
        }
        Product product = findEntity(id);
        productRepository.restoreStock(id, quantity);
        product.setStock(product.getStock() + quantity);
        return toDTO(product);
    }

    private Product findEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException("商品不存在"));
    }

    private ProductDTO toDTO(Product product) {
        // P1-03：返回给下游的价格 ≤0 视为异常数据，拒绝透传（脏数据生成 0/负金额订单）
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("商品价格异常");
        }
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setStock(product.getStock());
        dto.setImageUrl(product.getImageUrl());
        dto.setCategory(product.getCategory());
        dto.setCreatedAt(product.getCreatedAt());
        return dto;
    }
}
