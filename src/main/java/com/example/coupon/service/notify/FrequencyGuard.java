package com.example.coupon.service.notify;

import com.example.coupon.common.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FrequencyGuard {

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> LUA_SCRIPT;

    static {
        LUA_SCRIPT = new DefaultRedisScript<>();
        LUA_SCRIPT.setResultType(Long.class);
        LUA_SCRIPT.setScriptText("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            local count = redis.call('ZCARD', KEYS[1])
            if count < tonumber(ARGV[3]) then
                redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
                return 1
            else
                return 0
            end
            """);
    }

    public boolean allow(Long userId, int channel, long windowMs, int maxCount) {
        String key = String.format(RedisConstant.FREQUENCY_KEY, channel, userId);
        long now = System.currentTimeMillis();
        String messageId = now + ":" + Thread.currentThread().getId();

        Long result = redisTemplate.execute(
                LUA_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(maxCount),
                messageId);

        boolean allowed = Long.valueOf(1).equals(result);
        if (!allowed) {
            log.debug("频控拦截，userId={}，channel={}，windowMs={}，maxCount={}", userId, channel, windowMs, maxCount);
        }
        return allowed;
    }

    public boolean allow(Long userId, int channel) {
        return allow(userId, channel, 86400000L, 5);
    }
}
