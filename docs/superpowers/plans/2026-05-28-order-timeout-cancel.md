# 订单超时自动取消 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 订单创建后 15 分钟未支付，通过 RabbitMQ 延迟消息自动取消订单并释放优惠券。

**Architecture:** submit() 通过 TransactionSynchronization.afterCommit() 发送延迟消息到 x-delayed-message 交换机，15 分钟后消息路由到消费队列，OrderTimeoutListener 消费后原子取消订单并释放券。支付和取消通过 `UPDATE WHERE status=PENDING_PAY` 竞争，支付优先。

**Tech Stack:** Spring Boot 3.2, RabbitMQ 3.13.7 + rabbitmq_delayed_message_exchange plugin, Redis 7, MyBatis-Plus 3.5.7, Java 17

---

### Task 1: Create OrderTimeoutMessage DTO

**Files:**
- Create: `src/main/java/com/example/coupon/dto/OrderTimeoutMessage.java`

- [ ] **Step 1: Create the DTO class**

```java
package com.example.coupon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OrderTimeoutMessage implements Serializable {

    private String messageId;

    private String orderNo;

    private Long userId;

    private String couponCode;

    private LocalDateTime sentAt;
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/dto/OrderTimeoutMessage.java
git commit -m "feat: add OrderTimeoutMessage DTO for delayed cancel"
```

---

### Task 2: Add RabbitMQ delayed exchange configuration

**Files:**
- Modify: `src/main/java/com/example/coupon/config/RabbitMQConfig.java:17-72`

- [ ] **Step 1: Add constants**

Add after the existing `COUPON_RECEIVE_ROUTING_KEY` constant (line 38):

```java
    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.cancel.exchange";
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.cancel.queue";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout.cancel";
```

- [ ] **Step 2: Add imports**

Add to imports section:

```java
import org.springframework.amqp.core.CustomExchange;
import java.util.HashMap;
import java.util.Map;
```

- [ ] **Step 3: Add the delayed exchange bean**

Add after the `couponReceiveBinding()` bean (after line 73):

```java
    @Bean
    public CustomExchange orderTimeoutExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(ORDER_TIMEOUT_EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue orderTimeoutQueue() {
        return new Queue(ORDER_TIMEOUT_QUEUE, true);
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue())
                .to(orderTimeoutExchange())
                .with(ORDER_TIMEOUT_ROUTING_KEY)
                .noargs();
    }
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/coupon/config/RabbitMQConfig.java
git commit -m "feat: add delayed message exchange for order timeout cancel"
```

---

### Task 3: Create OrderTimeoutListener

**Files:**
- Create: `src/main/java/com/example/coupon/listener/OrderTimeoutListener.java`

- [ ] **Step 1: Create the listener**

```java
package com.example.coupon.listener;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.enums.OrderStatusEnum;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.OrderTimeoutMessage;
import com.example.coupon.entity.Order;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.OrderMapper;
import com.example.coupon.service.UserCouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
public class OrderTimeoutListener {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE)
    public void onTimeoutMessage(OrderTimeoutMessage message) {
        log.info("收到超时取消消息，orderNo={}，messageId={}", message.getOrderNo(), message.getMessageId());

        String msgKey = String.format(RedisConstant.IDEMPOTENT_MSG_KEY, message.getMessageId());
        Boolean first = redisTemplate.opsForValue().setIfAbsent(msgKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("消息已处理，跳过，messageId={}", message.getMessageId());
            return;
        }

        try {
            Order order = orderMapper.selectOne(new QueryWrapper<Order>()
                    .eq("order_no", message.getOrderNo()));
            if (order == null) {
                log.warn("订单不存在，跳过，orderNo={}", message.getOrderNo());
                return;
            }
            if (order.getStatus() != OrderStatusEnum.PENDING_PAY.getStatus()) {
                log.info("订单状态非待支付，跳过，orderNo={}，status={}",
                        message.getOrderNo(), order.getStatus());
                return;
            }

            int rows = orderMapper.update(null, new UpdateWrapper<Order>()
                    .eq("order_no", message.getOrderNo())
                    .eq("status", OrderStatusEnum.PENDING_PAY.getStatus())
                    .set("status", OrderStatusEnum.CANCELLED.getStatus())
                    .set("update_time", LocalDateTime.now()));
            if (rows == 0) {
                log.info("订单已被支付或取消，跳过，orderNo={}", message.getOrderNo());
                return;
            }

            if (message.getCouponCode() != null) {
                userCouponService.lambdaUpdate()
                        .eq(UserCoupon::getCouponCode, message.getCouponCode())
                        .eq(UserCoupon::getStatus, UserCouponStatusEnum.LOCKED.getStatus())
                        .set(UserCoupon::getStatus, UserCouponStatusEnum.UNUSED.getStatus())
                        .update();
            }

            log.info("超时取消订单成功，orderNo={}，userId={}", message.getOrderNo(), message.getUserId());
        } catch (DuplicateKeyException e) {
            log.debug("幂等写入冲突，视为成功，orderNo={}", message.getOrderNo());
        } catch (Exception e) {
            log.error("超时取消处理异常，orderNo={}，error={}", message.getOrderNo(), e.getMessage());
            redisTemplate.delete(msgKey);
            throw e;
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/listener/OrderTimeoutListener.java
git commit -m "feat: add OrderTimeoutListener for order timeout cancellation"
```

---

### Task 4: Send delayed message on order submit

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/OrderServiceImpl.java:32-126`

- [ ] **Step 1: Add new dependencies to OrderServiceImpl**

Add these imports:

```java
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.OrderTimeoutMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

Add these @Autowired fields:

```java
    @Autowired
    private RabbitTemplate rabbitTemplate;
```

- [ ] **Step 2: Add afterCommit callback in submit()**

After `orderMapper.insert(order);` (after line 112) and the `for` loop creating order items (lines 114-122), add before the `log.info` line (line 124):

```java
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        OrderTimeoutMessage msg = new OrderTimeoutMessage();
                        msg.setMessageId(java.util.UUID.randomUUID().toString());
                        msg.setOrderNo(order.getOrderNo());
                        msg.setUserId(userId);
                        msg.setCouponCode(dto.getCouponCode());
                        msg.setSentAt(LocalDateTime.now());

                        rabbitTemplate.convertAndSend(
                                RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                                RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                                msg,
                                m -> {
                                    m.getMessageProperties().setHeader("x-delay", 900000);
                                    return m;
                                });
                        log.info("已发送超时取消延迟消息，orderNo={}", order.getOrderNo());
                    }
                });
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coupon/service/impl/OrderServiceImpl.java
git commit -m "feat: send delayed cancel message on order submit"
```

---

### Task 5: Write integration test for timeout cancel

**Files:**
- Create: `src/test/java/com/example/coupon/OrderTimeoutCancelTest.java`

- [ ] **Step 1: Create the test class**

```java
package com.example.coupon;

import com.example.coupon.common.enums.OrderStatusEnum;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;
import com.example.coupon.dto.OrderTimeoutMessage;
import com.example.coupon.entity.Order;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.OrderMapper;
import com.example.coupon.service.OrderService;
import com.example.coupon.service.UserCouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class OrderTimeoutCancelTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Long SHOP_ID = 1L;
    private static final Long USER_ID = 88888L;
    private static final String COUPON_CODE = "TEST-TIMEOUT-2026";

    private String orderNo;

    @BeforeEach
    void setUp() {
        orderNo = null;
    }

    @AfterEach
    void tearDown() {
        if (orderNo != null) {
            orderMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Order>()
                    .eq("order_no", orderNo));
            userCouponService.lambdaUpdate()
                    .eq(UserCoupon::getCouponCode, COUPON_CODE)
                    .set(UserCoupon::getStatus, UserCouponStatusEnum.UNUSED.getStatus())
                    .update();
        }
    }

    private OrderSubmitRequestDTO buildSubmitDto() {
        OrderSubmitRequestDTO dto = new OrderSubmitRequestDTO();
        dto.setShopId(SHOP_ID);
        dto.setCouponCode(COUPON_CODE);
        dto.setOriginalAmount(new BigDecimal("100"));
        OrderSubmitRequestDTO.Item item = new OrderSubmitRequestDTO.Item();
        item.setGoodsId("G001");
        item.setGoodsName("测试商品");
        item.setQuantity(1);
        item.setPrice(new BigDecimal("100"));
        dto.setItems(List.of(item));
        return dto;
    }

    // ---------- 正常超时取消 ----------

    @Test
    void testTimeoutCancel() throws InterruptedException {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();
        assertEquals(OrderStatusEnum.PENDING_PAY.getStatus(), order.getStatus());

        // 验证券已锁定
        UserCoupon coupon = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, COUPON_CODE)
                .one();
        assertNotNull(coupon);
        assertEquals(UserCouponStatusEnum.LOCKED.getStatus(), coupon.getStatus());

        // 模拟超时取消：直接发一条无延迟的消息到队列（绕过 15min 等待）
        OrderTimeoutMessage msg = new OrderTimeoutMessage();
        msg.setMessageId(java.util.UUID.randomUUID().toString());
        msg.setOrderNo(orderNo);
        msg.setUserId(USER_ID);
        msg.setCouponCode(COUPON_CODE);
        msg.setSentAt(LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                msg,
                m -> {
                    m.getMessageProperties().setHeader("x-delay", 1000); // 1秒延迟
                    return m;
                });

        // 等待消息处理
        Thread.sleep(3000);

        // 验证订单已取消
        Order cancelled = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Order>()
                        .eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.CANCELLED.getStatus(), cancelled.getStatus());

        // 验证券已释放
        UserCoupon released = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, COUPON_CODE).one();
        assertEquals(UserCouponStatusEnum.UNUSED.getStatus(), released.getStatus());
    }

    // ---------- 幂等：同一消息重复投递 ----------

    @Test
    void testIdempotentMessage() {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();

        String messageId = java.util.UUID.randomUUID().toString();
        OrderTimeoutMessage msg = new OrderTimeoutMessage();
        msg.setMessageId(messageId);
        msg.setOrderNo(orderNo);
        msg.setUserId(USER_ID);
        msg.setCouponCode(COUPON_CODE);
        msg.setSentAt(LocalDateTime.now());

        // 发送两次相同 messageId 的消息
        for (int i = 0; i < 2; i++) {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                    RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                    msg,
                    m -> {
                        m.getMessageProperties().setHeader("x-delay", 500);
                        return m;
                    });
        }

        sleep(2000);

        // 只应取消一次，不会抛异常
        Order result = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Order>()
                        .eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.CANCELLED.getStatus(), result.getStatus());
    }

    // ---------- 支付优先：支付和取消并发 ----------

    @Test
    void testPayBeforeCancel() throws Exception {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();

        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 线程1: 支付
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                orderService.pay(orderNo, USER_ID);
            } catch (Exception ignored) {
            }
        });

        // 线程2: 超时取消
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                OrderTimeoutMessage msg = new OrderTimeoutMessage();
                msg.setMessageId(java.util.UUID.randomUUID().toString());
                msg.setOrderNo(orderNo);
                msg.setUserId(USER_ID);
                msg.setCouponCode(COUPON_CODE);
                msg.setSentAt(LocalDateTime.now());
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                        RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                        msg,
                        m -> {
                            m.getMessageProperties().setHeader("x-delay", 500);
                            return m;
                        });
            } catch (Exception ignored) {
            }
        });

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        Thread.sleep(2000);

        // 最终状态：支付成功（不应被取消）
        Order result = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Order>()
                        .eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.PAID.getStatus(), result.getStatus());

        // 券应为已使用（不是释放）
        UserCoupon coupon = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, COUPON_CODE).one();
        assertEquals(UserCouponStatusEnum.USED.getStatus(), coupon.getStatus());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -pl . -Dtest=OrderTimeoutCancelTest -q`
Expected: Tests pass (BUILD SUCCESS)

> **Note:** Tests require running MySQL/Redis/RabbitMQ containers. Start them with `docker start mysql redis rabbitmq` if not running. Ensure test coupon code `TEST-TIMEOUT-2026` in the DB or mocked accordingly.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/coupon/OrderTimeoutCancelTest.java
git commit -m "test: add integration tests for order timeout cancellation"
```

---

### Task 6: Final verification

- [ ] **Step 1: Run full test suite**

Run: `mvn test -q`
Expected: All tests pass

- [ ] **Step 2: Verify application starts**

Run: `mvn spring-boot:run` (wait for startup, then Ctrl+C)
Expected: Application starts without bean creation errors related to RabbitMQ

- [ ] **Step 3: Push branch**

```bash
git push origin <branch-name>
```
