package com.example.mall.common.model;

/**
 * 统一的 REST 错误响应体：{"message":"..."}
 * 调用方（RestClient）解析 message 后转为本地业务异常。
 */
public class ApiError {

    private String message;

    public ApiError() {
    }

    public ApiError(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
