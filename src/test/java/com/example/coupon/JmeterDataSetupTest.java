package com.example.coupon;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.User;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class JmeterDataSetupTest {

    @Autowired
    private UserService userService;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int USER_COUNT = Integer.getInteger("jmeter.users", 500);
    private static final int STOCK = Integer.getInteger("jmeter.stock", 500);
    private Long templateId;
    private final String batchSuffix = String.valueOf(System.currentTimeMillis());

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
    }

    @Test
    void prepareJmeterData() throws Exception {
        // 1. 创建并发布优惠券模板
        System.out.println("[SETUP] 创建优惠券模板...");
        Coupon coupon = new Coupon();
        coupon.setName("jmeter_seckill_" + batchSuffix);
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
        coupon.setStatus(0);
        coupon.setCreateTime(LocalDateTime.now());
        coupon.setUpdateTime(LocalDateTime.now());
        couponTemplateService.save(coupon);
        couponTemplateService.publish(coupon.getId());
        templateId = coupon.getId();

        // 加布隆过滤器
        RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisConstant.BLOOM_TEMPLATE_KEY);
        bloom.add(templateId);

        System.out.println("[SETUP] 模板创建完成，id=" + templateId + "，stock=" + STOCK);

        // 2. 创建测试用户并存 token 到 Redis
        System.out.println("[SETUP] 创建 " + USER_COUNT + " 个测试用户...");

        String csvPath = Paths.get("target", "jmeter_tokens.csv").toAbsolutePath().toString();
        try (PrintWriter csv = new PrintWriter(new FileWriter(csvPath))) {
            csv.println("token,userId");

            for (int i = 1; i <= USER_COUNT; i++) {
                String username = "jmeter_user_" + batchSuffix + "_" + i;
                String password = "123456";

                // 创建用户
                User user = new User();
                user.setUsername(username);
                user.setPassword(password);
                user.setShopNumber(1L);
                user.setCreateTime(LocalDateTime.now());
                user.setUpdateTime(LocalDateTime.now());
                userService.save(user);

                // 登录获取 token
                String token = userService.login(username, password);

                csv.println(token + "," + user.getId());

                if (i % 100 == 0) {
                    System.out.println("[SETUP] 已创建 " + i + " / " + USER_COUNT + " 个用户");
                }
            }
        }

        System.out.println("[SETUP] Token CSV 已生成: " + csvPath);
        System.out.println("[SETUP] 模板 ID: " + templateId + " (请写入 JMeter 变量 templateId)");
        System.out.println("[SETUP] ======== 数据准备完成 ========");

        // 保持数据不删除，供 JMeter 使用
        // 输出关键信息到文件方便 JMeter 读取
        String infoPath = Paths.get("target", "jmeter_test_info.properties").toAbsolutePath().toString();
        try (PrintWriter pw = new PrintWriter(new FileWriter(infoPath))) {
            pw.println("templateId=" + templateId);
            pw.println("userCount=" + USER_COUNT);
            pw.println("stock=" + STOCK);
            pw.println("csvPath=" + csvPath.replace("\\", "/"));
        }
        System.out.println("[SETUP] 测试信息已写入: " + infoPath);
    }
}
