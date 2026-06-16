package com.example.coupon.common.result;

import lombok.Getter;

@Getter
public enum ResultCode {
    
    SUCCESS(200, "成功"),
    ERROR(500, "服务器错误"),
    NOT_FOUND(404, "资源不存在"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试");
    
    private final Integer code;
    private final String message;
    
    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
