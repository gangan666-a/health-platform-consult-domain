package com.example.mall.orderservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

/**
 * 远程服务调用错误的统一转换：把对方返回的 {"message":"..."} 转成本地业务异常
 */
public final class ClientSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientSupport() {
    }

    public static BusinessException toBusinessException(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                ApiError error = MAPPER.readValue(body, ApiError.class);
                if (error.getMessage() != null && !error.getMessage().isBlank()) {
                    return new BusinessException(error.getMessage());
                }
            } catch (Exception ignored) {
                // 响应体不是约定的 JSON，走兜底提示
            }
        }
        return new BusinessException("远程服务调用失败（HTTP " + ex.getStatusCode().value() + "）");
    }
}
