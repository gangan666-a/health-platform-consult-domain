package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 创建/复用问诊会话请求（order-service → consult-service）。
 * 演示环境无登录鉴权：会话绑定目标药品，patientName 由服务端默认填充。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateConsultSessionRequest {

    private Long productId;
    private String productName;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }
}
