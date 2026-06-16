package com.example.coupon.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流注解，基于 Redis Lua + ZSET 实现。
 * 按 (userId, requestURI) 粒度计数，窗口随请求时刻动态滑动，无固定边界突增问题。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 窗口内允许的最大请求数 */
    int permits() default 10;

    /** 窗口大小，单位秒 */
    int window() default 60;
}
