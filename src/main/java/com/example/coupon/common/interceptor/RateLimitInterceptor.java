package com.example.coupon.common.interceptor;

import com.example.coupon.common.annotation.RateLimit;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.common.result.ResultCode;
import com.example.coupon.dto.UserDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String RATE_LIMIT_LUA =
            "local key = KEYS[1] " +
            "local window = tonumber(ARGV[1]) * 1000 " +
            "local permits = tonumber(ARGV[2]) " +
            "local now = tonumber(ARGV[3]) " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " +
            "local count = redis.call('ZCARD', key) " +
            "if count < permits then " +
            "    redis.call('ZADD', key, now, now .. ':' .. count) " +
            "    redis.call('EXPIRE', key, math.ceil(ARGV[1]) + 1) " +
            "    return 1 " +
            "end " +
            "return 0";

    private final DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>();

    {
        rateLimitScript.setScriptText(RATE_LIMIT_LUA);
        rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        RateLimit annotation = hm.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }

        try {
            UserDTO user = UserContext.getUser();
            if (user == null) {
                log.warn("用户未认证，跳过限流检查，uri={}", request.getRequestURI());
                return true;
            }

            String key = String.format(RedisConstant.RATELIMIT_KEY, user.getId(), request.getRequestURI());
            Long result = redisTemplate.execute(rateLimitScript, List.of(key),
                    String.valueOf(annotation.window()),
                    String.valueOf(annotation.permits()),
                    String.valueOf(System.currentTimeMillis()));

            if (result != null && result == 0) {
                log.warn("限流触发，userId={}, uri={}, permits={}, window={}s",
                        user.getId(), request.getRequestURI(), annotation.permits(), annotation.window());
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        objectMapper.writeValueAsString(Result.error(ResultCode.TOO_MANY_REQUESTS)));
                return false;
            }
        } catch (Exception e) {
            log.error("限流检查异常，放行请求，uri={}", request.getRequestURI(), e);
        }

        return true;
    }
}
