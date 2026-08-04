package com.example.mall.common.exception;

/**
 * 业务异常，message 会直接展示给用户 / 返回给调用方
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
