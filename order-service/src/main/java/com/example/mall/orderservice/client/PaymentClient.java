package com.example.mall.orderservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.PaymentDTO;
import com.example.mall.common.model.PaymentRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 支付服务调用方（order-service 前端 → payment-service）
 */
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${mall.payment-service.url}") String baseUrl, RestClient.Builder builder) {
        // builder 为 RestClientConfig 注入的带超时配置的实例（connect 3s / read 5s）
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public PaymentDTO executePayment(String orderNo, String payMethod, String payBank, String payCardNo) {
        PaymentRequest request = new PaymentRequest(orderNo, payMethod, payBank, payCardNo);
        try {
            ResponseEntity<PaymentDTO> resp = restClient.post()
                    .uri("/api/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(PaymentDTO.class);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("支付服务不可用，请稍后重试");
        }
    }
}
