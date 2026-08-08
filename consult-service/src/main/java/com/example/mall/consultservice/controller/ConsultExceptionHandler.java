package com.example.mall.consultservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一 REST 错误体 {@code {"message":"..."}}（与各服务一致，调用方解析 message 转业务异常）。
 * 业务异常 → 400 透传精确文案；参数校验 → 400；其余 → 500。
 */
@RestControllerAdvice
public class ConsultExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsultExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError("参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception ex) {
        log.error("未捕获异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("服务器内部错误"));
    }
}
