package com.example.mall.productservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 商品服务统一 REST 错误处理：错误响应体统一为 {"message":"..."}
 */
@RestControllerAdvice
public class ProductExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBusiness(BusinessException ex) {
        return new ApiError(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOther(Exception ex) {
        return new ApiError("商品服务内部错误：" + ex.getMessage());
    }
}
