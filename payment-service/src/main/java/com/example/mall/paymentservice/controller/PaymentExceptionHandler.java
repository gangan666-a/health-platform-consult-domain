package com.example.mall.paymentservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 支付服务统一 REST 错误处理：错误响应体统一为 {"message":"..."}
 */
@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBusiness(BusinessException ex) {
        return new ApiError(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOther(Exception ex) {
        // 不回显 ex.getMessage()，避免异常详情/请求体（含卡号等敏感信息）泄露（评审 P2）
        return new ApiError("支付服务内部错误，请稍后重试");
    }
}
