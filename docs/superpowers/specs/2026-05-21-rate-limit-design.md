# 接口限流设计

## 概述

为 Controller 层增加按用户+接口维度的固定窗口限流，通过 `@RateLimit` 注解声明限流规则，`RateLimitInterceptor` 基于 Redis 执行计数和拦截。

## 决策汇总

| 维度 | 选择 |
|------|------|
| 粒度 | 按用户 + 按接口 |
| 配置方式 | 注解驱动 `@RateLimit` |
| 算法 | 固定窗口（Redis INCR + EXPIRE） |
| 超限响应 | 直接拒绝，返回 429 + JSON |
| 实现方式 | 独立 HandlerInterceptor |

## 架构

```
请求 → TokenInterceptor(认证) → RateLimitInterceptor(限流) → Controller
                                    │
                                    │ Redis INCR
                                    ▼
                        key: rateLimit:{userId}:{URI}
                             固定窗口: INCR + EXPIRE
                                    │
                              ┌─────┴─────┐
                              │ 超限？     │
                              └─────┬─────┘
                         ┌──────────┴──────────┐
                         ▼                      ▼
                     返回 429              放行到 Controller
```

## 新增文件

### `common/annotation/RateLimit.java`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int permits() default 10;   // 窗口内允许次数
    int window() default 60;    // 窗口大小（秒）
}
```

### `common/interceptor/RateLimitInterceptor.java`

`preHandle()` 逻辑：
1. 检查 handler 是否为 `HandlerMethod`，不是则放行
2. 检查方法上是否有 `@RateLimit` 注解，没有则放行
3. 从 `UserContext.getUser().getId()` 获取 userId
4. 构建 Redis key: `rateLimit:{userId}:{requestURI}`
5. `INCR key`；若返回值 == 1，设置 `EXPIRE key window`
6. 若计数 > permits，返回 429 + JSON `{"code":429,"message":"请求过于频繁，请稍后再试"}`，`return false`
7. 否则 `return true` 放行

## 修改文件

### `config/WebConfig.java`

在 `TokenInterceptor` 之后注册 `RateLimitInterceptor`，确保限流时用户已认证：

```java
registry.addInterceptor(rateLimitInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns("/user/login", "/user/save",
                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
```

### `common/constant/RedisConstant.java`

新增：

```java
public static final String RATELIMIT_KEY = "rateLimit:%s:%s";
```

### `common/result/ResultCode.java`

新增：

```java
TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试");
```

## Redis Key 设计

- 格式：`rateLimit:{userId}:{requestURI}`
- 示例：`rateLimit:1001:/order/submit`
- TTL：`@RateLimit.window()` 秒
- 过期策略：窗口自动滚动，无需手动清理

## 边界与限制

- 仅支持方法级注解，不支持类级别
- 注解值即最终值，不支持运行时动态修改
- Redis 操作失败时放行（偏安全侧，避免误拦截）
- 仅限 HTTP 入口，不限制 MQ 消费侧
- userId 来源于 `UserContext`，依赖 `TokenInterceptor` 先执行

## 使用示例

```java
@PostMapping("/receive")
@RateLimit(permits = 20, window = 60)  // 每用户每分钟最多领券20次
public Result asyncReceive(...) { ... }

@PostMapping("/order/submit")
@RateLimit(permits = 5, window = 60)   // 每用户每分钟最多下单5次
public Result submit(...) { ... }
```

不标注的方法不限流。
