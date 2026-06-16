package com.example.coupon;

import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.interceptor.RateLimitInterceptor;
import com.example.coupon.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RateLimitTest {

    @Autowired
    private RateLimitInterceptor interceptor;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Long USER_ID = 88888L;

    private static final String SLIDING_WINDOW_LUA =
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

    private final DefaultRedisScript<Long> luaScript = new DefaultRedisScript<>();

    {
        luaScript.setScriptText(SLIDING_WINDOW_LUA);
        luaScript.setResultType(Long.class);
    }

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserContext.setUser(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldPassThroughNonHandlerMethod() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertTrue(result, "non-HandlerMethod should pass through");
        assertEquals(200, res.getStatus());
    }

    @Test
    void slidingWindowAllowsRequestsWithinLimit() {
        String key = "rateLimit:" + USER_ID + ":/test/sliding";
        try {
            for (int i = 0; i < 5; i++) {
                Long r = redisTemplate.execute(luaScript, List.of(key),
                        "60", "10", String.valueOf(System.currentTimeMillis()));
                assertEquals(1L, r, "request " + i + " within limit should pass");
            }
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void slidingWindowBlocksWhenExceedingLimit() {
        String key = "rateLimit:" + USER_ID + ":/test/sliding";
        int permits = 3;
        long now = System.currentTimeMillis();
        try {
            for (int i = 0; i < permits; i++) {
                redisTemplate.execute(luaScript, List.of(key),
                        "60", String.valueOf(permits), String.valueOf(now));
            }
            Long blocked = redisTemplate.execute(luaScript, List.of(key),
                    "60", String.valueOf(permits), String.valueOf(now));

            assertEquals(0L, blocked, "exceeding limit should return 0 (block)");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void slidingWindowExpiresAfterWindowPasses() {
        String key = "rateLimit:" + USER_ID + ":/test/expire";
        int windowSec = 1;
        int permits = 2;
        try {
            long past = System.currentTimeMillis() - (windowSec + 1) * 1000L;
            long now = System.currentTimeMillis();

            // Insert one old entry
            redisTemplate.execute(luaScript, List.of(key),
                    String.valueOf(windowSec), String.valueOf(permits), String.valueOf(past));

            // Old entry should be cleaned up by ZREMRANGEBYSCORE, and now we have room
            Long r = redisTemplate.execute(luaScript, List.of(key),
                    String.valueOf(windowSec), String.valueOf(permits), String.valueOf(now));
            assertEquals(1L, r, "old entries outside window should be cleaned up, new request passes");
        } finally {
            redisTemplate.delete(key);
        }
    }
}
