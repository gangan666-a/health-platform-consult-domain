package com.example.mall.productservice.controller;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 商品服务统一 REST 错误处理：错误响应体统一为 {"message":"..."}
 * <ul>
 *   <li>业务异常（BusinessException）：400，message 透传（文案面向用户，安全）；</li>
 *   <li>参数非法（类型不匹配/缺失，如 quantity 传非整数）：400 通用文案，而非 500；</li>
 *   <li>其余异常：500 固定通用文案，不回显内部信息/SQL/堆栈（P2-04 脱敏）。</li>
 * </ul>
 */
@RestControllerAdvice
public class ProductExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBusiness(BusinessException ex) {
        return new ApiError(ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleMissingParam(MissingServletRequestParameterException ex) {
        // 契约收紧（QA-P2）：quantity 为必填参数，缺参明确提示，避免静默默认 1 误扣
        return new ApiError("quantity 不能为空");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // quantity 非整数（如 1.5 / abc）：参数绑定失败，400 通用文案
        return new ApiError("参数非法");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOther(Exception ex) {
        log.error("商品服务内部错误", ex);
        return new ApiError("商品服务内部错误，请稍后重试");
    }
}
