package com.example.coupon;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.dto.ReceiveCouponRequestDTO;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.CouponTemplateMapper;
import com.example.coupon.mapper.UserCouponMapper;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserCouponService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SeckillStrategyPerformanceTest {

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int THREADS = 500;
    private static final int STOCK = 1000;

    private Long asyncTemplateId;
    private Long syncTemplateId;
    private Long dbTemplateId;

    // ---- helpers ----

    private Long createAndPublishTemplate(String name) {
        Coupon coupon = new Coupon();
        coupon.setName(name);
        coupon.setShopNumber(1L);
        coupon.setSource(1);
        coupon.setTarget(1);
        coupon.setGoods("all");
        coupon.setType(1);
        coupon.setValidStartTime(LocalDateTime.now().minusDays(1));
        coupon.setValidEndTime(LocalDateTime.now().plusDays(7));
        coupon.setStock(STOCK);
        coupon.setReceiveRule("{\"limit\": 1}");
        coupon.setConsumeRule("{\"minAmount\": 100, \"discount\": 20}");
        // 必须先存为 DRAFT，publish 方法检查 status == DRAFT 才会更新并初始化 Redis 库存
        coupon.setStatus(0);
        coupon.setCreateTime(LocalDateTime.now());
        coupon.setUpdateTime(LocalDateTime.now());
        couponTemplateService.save(coupon);
        couponTemplateService.publish(coupon.getId());
        return coupon.getId();
    }

    private void cleanupTemplate(Long templateId) {
        if (templateId == null) return;
        userCouponMapper.delete(new QueryWrapper<UserCoupon>().eq("template_id", templateId));
        couponTemplateMapper.deleteById(templateId);
        redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, templateId));
        redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, templateId));
        redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_KEY, templateId));
    }

    private static class BenchResult {
        long totalMs;
        int success;
        int fail;
        long[] latencies; // microseconds
    }

    private BenchResult runBenchmark(java.util.function.BiConsumer<ReceiveCouponRequestDTO, Long> strategyFn)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyLatch = new CountDownLatch(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        long[] latencies = new long[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            final long userId = 100000L + i + 1;
            executor.submit(() -> {
                UserDTO threadUser = new UserDTO();
                threadUser.setId(userId);
                threadUser.setShopNumber(1L);
                UserContext.setUser(threadUser);
                try {
                    ReceiveCouponRequestDTO dto = new ReceiveCouponRequestDTO();
                    readyLatch.countDown();
                    startLatch.await();

                    long t1 = System.nanoTime();
                    strategyFn.accept(dto, userId);
                    long t2 = System.nanoTime();
                    latencies[idx] = t2 - t1;
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    latencies[idx] = -1;
                } finally {
                    UserContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        Instant start = Instant.now();
        startLatch.countDown();
        doneLatch.await();
        Duration duration = Duration.between(start, Instant.now());
        executor.shutdown();

        BenchResult r = new BenchResult();
        r.totalMs = duration.toMillis();
        r.success = successCount.get();
        r.fail = failCount.get();
        r.latencies = Arrays.stream(latencies).filter(v -> v >= 0).sorted().toArray();
        return r;
    }

    private void printResult(String label, BenchResult r) {
        int n = r.latencies.length;
        long p50 = n > 0 ? r.latencies[n / 2] / 1000 : 0;
        long p99 = n > 0 ? r.latencies[n * 99 / 100] / 1000 : 0;
        long max = n > 0 ? r.latencies[n - 1] / 1000 : 0;
        double qps = r.totalMs > 0 ? r.success * 1000.0 / r.totalMs : 0;

        System.out.println("[BENCH] ===== " + label + " =====");
        System.out.printf("[BENCH] 成功: %d, 失败: %d, 总耗时: %d ms (%.1f s)%n",
                r.success, r.fail, r.totalMs, r.totalMs / 1000.0);
        System.out.printf("[BENCH] QPS: %.1f, P50: %d μs, P99: %d μs, Max: %d μs%n",
                qps, p50, p99, max);
    }

    // ---- setup / teardown ----

    @BeforeEach
    void setUp() {
        UserDTO mockUser = new UserDTO();
        mockUser.setId(99999L);
        mockUser.setShopNumber(1L);
        UserContext.setUser(mockUser);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        cleanupTemplate(asyncTemplateId);
        cleanupTemplate(syncTemplateId);
        cleanupTemplate(dbTemplateId);
    }

    // ---- tests ----

    @Test
    void testAsyncStrategy() throws Exception {
        asyncTemplateId = createAndPublishTemplate("seckill_async_" + System.currentTimeMillis());
        System.out.println("[BENCH] 异步策略 (Lua + MQ) 开始，templateId=" + asyncTemplateId);

        BenchResult r = runBenchmark((dto, userId) -> {
            dto.setTemplateId(asyncTemplateId);
            userCouponService.asyncReceiveCoupon(dto, userId);
        });

        printResult("异步策略 (Lua Redis 预扣 + MQ 异步写库)", r);

        // 等待 MQ 消费者完成 DB 写入
        Thread.sleep(3000);
        long dbCount = userCouponMapper.selectCount(
                new QueryWrapper<UserCoupon>().eq("template_id", asyncTemplateId));
        System.out.println("[BENCH] 异步策略 DB 实际写入记录数: " + dbCount);

        assertTrue(r.success >= STOCK, "至少应有库存数量的成功请求");
    }

    @Test
    void testSyncStrategy() throws Exception {
        syncTemplateId = createAndPublishTemplate("seckill_sync_" + System.currentTimeMillis());
        System.out.println("[BENCH] 同步策略 (Lua + DB) 开始，templateId=" + syncTemplateId);

        BenchResult r = runBenchmark((dto, userId) -> {
            dto.setTemplateId(syncTemplateId);
            userCouponService.syncReceiveCoupon(dto, userId);
        });

        printResult("同步策略 (Lua Redis 预扣 + 同步 DB 写)", r);

        long dbCount = userCouponMapper.selectCount(
                new QueryWrapper<UserCoupon>().eq("template_id", syncTemplateId));
        assertEquals(r.success, dbCount, "DB 记录数应等于成功数");
    }

    @Test
    void testPureDbStrategy() throws Exception {
        dbTemplateId = createAndPublishTemplate("seckill_db_" + System.currentTimeMillis());
        System.out.println("[BENCH] 纯DB策略开始，templateId=" + dbTemplateId);

        BenchResult r = runBenchmark((dto, userId) -> {
            dto.setTemplateId(dbTemplateId);
            userCouponService.receiveCoupon(dto, userId);
        });

        printResult("纯DB策略 (UPDATE stock + INSERT user_coupon)", r);

        long dbCount = userCouponMapper.selectCount(
                new QueryWrapper<UserCoupon>().eq("template_id", dbTemplateId));
        assertEquals(r.success, dbCount, "DB 记录数应等于成功数");
    }

    @Test
    void testAllStrategiesInOneRun() throws Exception {
        // 一次运行三种策略，方便直接对比
        System.out.println("\n[BENCH] ========== 秒杀三种策略性能对比 ==========");
        System.out.println("[BENCH] 并发线程: " + THREADS + ", 库存: " + STOCK);

        // Async
        asyncTemplateId = createAndPublishTemplate("seckill_cmp_async_" + System.currentTimeMillis());
        BenchResult asyncR = runBenchmark((dto, userId) -> {
            dto.setTemplateId(asyncTemplateId);
            userCouponService.asyncReceiveCoupon(dto, userId);
        });
        printResult("1. 异步 (Lua + MQ)", asyncR);
        Thread.sleep(3000);

        // Sync
        syncTemplateId = createAndPublishTemplate("seckill_cmp_sync_" + System.currentTimeMillis());
        BenchResult syncR = runBenchmark((dto, userId) -> {
            dto.setTemplateId(syncTemplateId);
            userCouponService.syncReceiveCoupon(dto, userId);
        });
        printResult("2. 同步 (Lua + DB)", syncR);

        // Pure DB
        dbTemplateId = createAndPublishTemplate("seckill_cmp_db_" + System.currentTimeMillis());
        BenchResult dbR = runBenchmark((dto, userId) -> {
            dto.setTemplateId(dbTemplateId);
            userCouponService.receiveCoupon(dto, userId);
        });
        printResult("3. 纯DB (UPDATE + INSERT)", dbR);

        // 汇总对比
        System.out.println("\n[BENCH] ========== 汇总对比 ==========");
        System.out.printf("%-30s %10s %10s %10s%n", "策略", "QPS", "P50(μs)", "P99(μs)");
        System.out.println("---------------------------------------------------------------");
        printSummaryRow("1.异步 (Lua+MQ)", asyncR);
        printSummaryRow("2.同步 (Lua+DB)", syncR);
        printSummaryRow("3.纯DB", dbR);
        System.out.println();
    }

    private void printSummaryRow(String label, BenchResult r) {
        int n = r.latencies.length;
        long p50 = n > 0 ? r.latencies[n / 2] / 1000 : 0;
        long p99 = n > 0 ? r.latencies[n * 99 / 100] / 1000 : 0;
        double qps = r.totalMs > 0 ? r.success * 1000.0 / r.totalMs : 0;
        System.out.printf("%-30s %10.1f %10d %10d%n", label, qps, p50, p99);
    }
}
