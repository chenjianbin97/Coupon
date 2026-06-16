package com.example.coupon;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.Coupon;
import com.example.coupon.mapper.CouponTemplateMapper;
import com.example.coupon.service.CouponTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class CacheVsDbPerformanceTest {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @Autowired
    private RedissonClient redissonClient;

    private static final int THREADS = 10000;
    private static final int TEMPLATE_COUNT = 10000;
    private List<Long> templateIds = new ArrayList<>();
    private final String batchSuffix = String.valueOf(System.currentTimeMillis());

    @BeforeEach
    void setUp() throws InterruptedException {
        UserDTO mockUser = new UserDTO();
        mockUser.setId(99999L);
        mockUser.setShopNumber(1L);
        UserContext.setUser(mockUser);

        System.out.println("[PERF] 开始批量创建 " + TEMPLATE_COUNT + " 个模板...");
        Instant createStart = Instant.now();

        List<Coupon> batch = new ArrayList<>(500);
        for (int i = 0; i < TEMPLATE_COUNT; i++) {
            Coupon coupon = new Coupon();
            coupon.setName("perf_compare_" + batchSuffix + "_" + i);
            coupon.setShopNumber(1L);
            coupon.setSource(1);
            coupon.setTarget(1);
            coupon.setGoods("all");
            coupon.setType(1);
            coupon.setValidStartTime(LocalDateTime.now().minusDays(1));
            coupon.setValidEndTime(LocalDateTime.now().plusDays(7));
            coupon.setStock(100);
            coupon.setReceiveRule("{\"limit\": 1}");
            coupon.setConsumeRule("{\"minAmount\": 100, \"discount\": 20}");
            coupon.setStatus(1);
            coupon.setCreateTime(LocalDateTime.now());
            coupon.setUpdateTime(LocalDateTime.now());
            batch.add(coupon);

            if (batch.size() == 500 || i == TEMPLATE_COUNT - 1) {
                couponTemplateService.saveBatch(batch);
                for (Coupon c : batch) {
                    templateIds.add(c.getId());
                }
                batch.clear();
            }
        }

        Duration createDuration = Duration.between(createStart, Instant.now());
        System.out.println("[PERF] 模板创建完成，共 " + templateIds.size() + " 个，耗时: " + createDuration.toMillis() + " ms");

        // 将新模板加入布隆过滤器
        RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisConstant.BLOOM_TEMPLATE_KEY);
        for (Long id : templateIds) {
            bloom.add(id);
        }

        // 预热 Redis 缓存
        System.out.println("[PERF] 开始预热 Redis 缓存...");
        Instant warmStart = Instant.now();
        ExecutorService warmExecutor = Executors.newFixedThreadPool(50);
        CountDownLatch warmLatch = new CountDownLatch(templateIds.size());
        for (Long id : templateIds) {
            warmExecutor.submit(() -> {
                try {
                    couponTemplateService.getTemplateWithCache(id);
                } finally {
                    warmLatch.countDown();
                }
            });
        }
        warmLatch.await();
        warmExecutor.shutdown();
        Duration warmDuration = Duration.between(warmStart, Instant.now());
        System.out.println("[PERF] Redis 缓存预热完成，耗时: " + warmDuration.toMillis() + " ms");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (!templateIds.isEmpty()) {
            couponTemplateMapper.deleteBatchIds(templateIds);
            System.out.println("[PERF] 清理完成，删除 " + templateIds.size() + " 个模板");
        }
    }

    @Test
    void testRedisCacheHit() throws InterruptedException {
        System.out.println("[PERF] ===== 测试 Redis 缓存命中 =====");
        runConcurrentTest(true);
    }

    @Test
    void testDbDiskRead() throws Exception {
        // 重启 MySQL 清空 Buffer Pool
        System.out.println("[PERF] 正在重启 MySQL 以清空 Buffer Pool...");
        Process restart = new ProcessBuilder("docker", "restart", "mysql").start();
        restart.waitFor();

        // 等待 MySQL 就绪
        System.out.println("[PERF] 等待 MySQL 就绪...");
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            try {
                couponTemplateMapper.selectCount(null);
                ready = true;
                break;
            } catch (Exception e) {
                Thread.sleep(2000);
            }
        }
        if (!ready) {
            fail("MySQL 启动超时");
        }
        System.out.println("[PERF] MySQL 已就绪，Buffer Pool 已清空");

        System.out.println("[PERF] ===== 测试 DB 磁盘读 =====");
        runConcurrentTest(false);
    }

    private void runConcurrentTest(boolean useCache) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyLatch = new CountDownLatch(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        List<Long> ids = templateIds;

        long[] latencies = new long[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            executor.submit(() -> {
                UserDTO threadUser = new UserDTO();
                threadUser.setId(99999L);
                threadUser.setShopNumber(1L);
                UserContext.setUser(threadUser);
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    Long id = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));

                    long t1 = System.nanoTime();
                    CouponTemplateDTO dto;
                    if (useCache) {
                        dto = couponTemplateService.getTemplateWithCache(id);
                    } else {
                        dto = couponTemplateService.getTemplate(id);
                    }
                    long t2 = System.nanoTime();
                    latencies[idx] = t2 - t1;

                    if (dto != null && dto.getId().equals(id)) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("[ERROR] " + e.getMessage());
                } finally {
                    UserContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        String mode = useCache ? "Redis 缓存" : "DB 磁盘读";
        System.out.println("[PERF] " + THREADS + " 个线程就绪，开始 " + mode + " 测试...");

        Instant start = Instant.now();
        startLatch.countDown();
        doneLatch.await();
        Duration duration = Duration.between(start, Instant.now());
        executor.shutdown();

        // 计算延迟分位数
        java.util.Arrays.sort(latencies);
        long p50 = latencies[THREADS / 2] / 1000;
        long p99 = latencies[THREADS * 99 / 100] / 1000;
        long max = latencies[THREADS - 1] / 1000;

        System.out.println("[PERF] ===== " + mode + " 测试结果 =====");
        System.out.println("[PERF] 并发线程数: " + THREADS);
        System.out.println("[PERF] 成功: " + successCount.get() + ", 失败: " + failCount.get());
        System.out.println("[PERF] 总耗时: " + duration.toMillis() + " ms (" + String.format("%.1f", duration.toMillis() / 1000.0) + " 秒)");
        System.out.println("[PERF] QPS: " + String.format("%.1f", THREADS * 1000.0 / duration.toMillis()));
        System.out.println("[PERF] P50 延迟: " + p50 + " μs, P99: " + p99 + " μs, Max: " + max + " μs");

        assertEquals(THREADS, successCount.get(), "所有并发请求都应成功");
    }
}
