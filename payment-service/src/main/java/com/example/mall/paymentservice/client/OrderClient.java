package com.example.mall.paymentservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConfirmPaymentRequest;
import com.example.mall.common.model.OrderDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 订单服务调用方（payment-service → order-service）
 */
@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(@Value("${mall.order-service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
