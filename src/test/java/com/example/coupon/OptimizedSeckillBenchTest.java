package com.example.coupon;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.Coupon;
import com.example.coupon.service.CouponTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class OptimizedSeckillBenchTest {

    @Autowired private CouponTemplateService couponTemplateService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedissonClient redissonClient;

    private Long templateId;
    private final Set<Long> localBloom = ConcurrentHashMap.newKeySet();
    private final Map<Long, CouponTemplateDTO> localCache = new ConcurrentHashMap<>();
    private final DefaultRedisScript<Long> luaScript = new DefaultRedisScript<>();
    private RBloomFilter<Long> remoteBloom;

    private static final int STOCK = 10000;
    private static final String LUA =
        "local usersKey=KEYS[1];local stockKey=KEYS[2];local userId=ARGV[1];" +
        "if redis.call('SISMEMBER',usersKey,userId)==1 then return -1 end;" +
        "local remain=redis.call('DECR',stockKey);" +
        "if remain<0 then redis.call('INCR',stockKey);return -2 end;" +
        "redis.call('SADD',usersKey,userId);return remain;";

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(99999L); user.setShopNumber(1L);
        UserContext.setUser(user);
        luaScript.setScriptText(LUA);
        luaScript.setResultType(Long.class);

        Coupon c = new Coupon();
        c.setName("opt_" + System.currentTimeMillis());
        c.setShopNumber(1L); c.setSource(1); c.setTarget(1); c.setGoods("all"); c.setType(1);
        c.setValidStartTime(LocalDateTime.now().minusDays(1));
        c.setValidEndTime(LocalDateTime.now().plusDays(7));
        c.setStock(STOCK);
        c.setReceiveRule("{\"limit\":99}");
        c.setConsumeRule("{\"minAmount\":100,\"discount\":20}");
        c.setStatus(0); c.setCreateTime(LocalDateTime.now()); c.setUpdateTime(LocalDateTime.now());
        couponTemplateService.save(c);
        couponTemplateService.publish(c.getId());
        templateId = c.getId();

        // 本地预热
        localBloom.add(templateId);
        localCache.put(templateId, couponTemplateService.getTemplateWithCache(templateId));

        // 远程 bloom filter
        remoteBloom = redissonClient.getBloomFilter(RedisConstant.BLOOM_TEMPLATE_KEY);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (templateId != null) {
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, templateId));
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, templateId));
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_KEY, templateId));
        }
    }

    // ============ 方案A: 本地 Bloom + 本地缓存 + Redis Lua ============
    @Test
    void testOptimizedLocal() throws Exception {
        System.out.println("\n[OPTIMIZED] 本地Bloom + 本地缓存 + Redis Lua");
        runBench("OPTIMIZED", (usersKey, stockKey, userId) -> {
            if (!localBloom.contains(templateId)) return false;
            if (localCache.get(templateId) == null) return false;
            Long r = redisTemplate.execute(luaScript, Arrays.asList(usersKey, stockKey), String.valueOf(userId));
            return r != null && r >= 0;
        });
    }

    // ============ 方案B: 远程 Bloom + Redis 缓存 + Redis Lua (当前) ============
    @Test
    void testCurrentRemote() throws Exception {
        String cacheKey = String.format(RedisConstant.COUPON_TEMPLATE_KEY, templateId);
        System.out.println("\n[CURRENT] 远程Bloom + Redis缓存 + Redis Lua");
        runBench("CURRENT", (usersKey, stockKey, userId) -> {
            if (!remoteBloom.contains(templateId)) return false;
            if (redisTemplate.opsForValue().get(cacheKey) == null) return false;
            Long r = redisTemplate.execute(luaScript, Arrays.asList(usersKey, stockKey), String.valueOf(userId));
            return r != null && r >= 0;
        });
    }

    // ============ 方案C: 只 Redis Lua (理论上界) ============
    @Test
    void testLuaOnly() throws Exception {
        System.out.println("\n[LUA-ONLY] 只调 Redis Lua（理论上界）");
        runBench("LUA-ONLY", (usersKey, stockKey, userId) -> {
            Long r = redisTemplate.execute(luaScript, Arrays.asList(usersKey, stockKey), String.valueOf(userId));
            return r != null && r >= 0;
        });
    }

    private void runBench(String label, Checker fn) throws Exception {
        String usersKey = String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, templateId);
        String stockKey = String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, templateId);

        for (int threads : new int[]{100, 300, 500}) {
            AtomicInteger success = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final long uid = 800000L + threads * 1000 + i;
                exec.submit(() -> {
                    ready.countDown();
                    try { start.await(); } catch (Exception e) {}
                    if (fn.check(usersKey, stockKey, uid)) success.incrementAndGet();
                    done.countDown();
                });
            }

            ready.await();
            long t1 = System.nanoTime();
            start.countDown();
            done.await();
            long t2 = System.nanoTime();
            exec.shutdown();

            double qps = success.get() * 1_000_000_000.0 / (t2 - t1);
            System.out.printf("  %s threads=%-4d  success=%-5d  wall=%.3fs  QPS=%.0f%n",
                    label, threads, success.get(), (t2 - t1) / 1e9, qps);
        }
    }

    interface Checker {
        boolean check(String usersKey, String stockKey, long userId);
    }
}
