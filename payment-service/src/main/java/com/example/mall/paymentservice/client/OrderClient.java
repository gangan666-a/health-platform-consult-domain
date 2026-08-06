package com.example.mall.paymentservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * 订单服务调用方（payment-service → order-service）。
 * RestClient 显式配置 connect/read 超时，避免下游慢响应无限阻塞（设计 §0 / 评审 P1-01）。
 */
@Component
public class OrderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public OrderClient(@Value("${mall.order-service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public OrderDTO getOrder(String orderNo) {
        try {
            ResponseEntity<OrderDTO> resp = restClient.get()
                    .uri("/api/orders/{orderNo}", orderNo)
                    .retrieve()
                    .toEntity(OrderDTO.class);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("订单服务不可用，请稍后重试");
        }
    }

    public OrderDTO confirmPayment(String orderNo, ConfirmPaymentRequest request) {
        try {
            ResponseEntity<OrderDTO> resp = restClient.post()
                    .uri("/api/orders/{orderNo}/confirm-payment", orderNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(OrderDTO.class);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("订单服务不可用，请稍后重试");
        }
    }
}
