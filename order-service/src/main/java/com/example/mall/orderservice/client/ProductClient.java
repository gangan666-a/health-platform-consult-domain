package com.example.mall.orderservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ProductDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * 商品服务调用方（order-service → product-service）
 */
@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(@Value("${mall.product-service.url}") String baseUrl, RestClient.Builder builder) {
        // builder 为 RestClientConfig 注入的带超时配置的实例（connect 3s / read 5s）
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<ProductDTO> listProducts() {
        try {
            ResponseEntity<List<ProductDTO>> resp = restClient.get()
                    .uri("/api/products")
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });
            return resp.getBody() == null ? List.of() : resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("商品服务不可用，请稍后重试");
        }
    }

    public ProductDTO getProduct(Long id) {
        try {
            ResponseEntity<ProductDTO> resp = restClient.get()
                    .uri("/api/products/{id}", id)
                    .retrieve()
                    .toEntity(ProductDTO.class);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("商品服务不可用，请稍后重试");
        }
    }

    public ProductDTO deductStock(Long id, int quantity) {
        try {
            ResponseEntity<ProductDTO> resp = restClient.post()
                    .uri("/api/products/{id}/stock/deduct?quantity={q}", id, quantity)
                    .retrieve()
                    .toEntity(ProductDTO.class);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("商品服务不可用，请稍后重试");
        }
    }

    public void restoreStock(Long id, int quantity) {
        try {
            restClient.post()
                    .uri("/api/products/{id}/stock/restore?quantity={q}", id, quantity)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("商品服务不可用，请稍后重试");
        }
    }
}
