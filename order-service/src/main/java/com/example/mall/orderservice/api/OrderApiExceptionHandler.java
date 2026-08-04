package com.example.mall.orderservice.api;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 订单服务 REST 错误处理：返回 JSON {"message":"..."}。
 * 仅作用于 api 包，避免与 web 包的 HTML 错误处理冲突。
 */
@RestControllerAdvice(basePackages = "com.example.mall.orderservice.api")
public class OrderApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBusiness(BusinessException ex) {
        return new ApiError(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOther(Exception ex) {
        return new ApiError("订单服务内部错误：" + ex.getMessage());
    }
}
