package com.example.mall.recommendservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 推荐服务统一 REST 错误处理：错误响应体统一为 {"message":"..."}
 * <ul>
 *   <li>业务异常（BusinessException）：400，message 透传（文案面向用户，安全）；</li>
 *   <li>请求体 JSON 非法（HttpMessageNotReadableException）：400 通用文案，不回带请求体原文；</li>
 *   <li>其余异常：500 固定通用文案，不回显内部信息/SQL/堆栈（P2-04 脱敏）。</li>
 * </ul>
 */
@RestControllerAdvice
public class RecommendationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RecommendationExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBusiness(BusinessException ex) {
        return new ApiError(ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex) {
        return new ApiError("请求体格式错误");
    }

    /** 请求体缺少必填字段（@Valid 触发）→ 400 固定通用文案，不回显字段名 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex) {
        return new ApiError("请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOther(Exception ex) {
        log.error("推荐服务内部错误", ex);
        return new ApiError("推荐服务内部错误，请稍后重试");
    }
}
