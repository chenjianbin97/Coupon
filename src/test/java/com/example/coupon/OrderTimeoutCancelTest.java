package com.example.coupon;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.coupon.common.enums.OrderStatusEnum;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;
import com.example.coupon.dto.OrderTimeoutMessage;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.Order;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.CouponTemplateMapper;
import com.example.coupon.mapper.OrderMapper;
import com.example.coupon.mapper.UserCouponMapper;
import com.example.coupon.service.OrderService;
import com.example.coupon.service.UserCouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long SHOP_ID = 1L;
    private static final Long USER_ID = 88888L;

    private String orderNo;
    private Long templateId;
    private String couponCode;

    @BeforeEach
    void setUp() {
        orderNo = null;
        // Use a unique coupon code per test run to avoid unique constraint conflicts
        couponCode = "TMO-" + System.currentTimeMillis();

        // Create coupon template
        Coupon template = new Coupon();
        template.setName("超时取消测试券");
        template.setShopNumber(SHOP_ID);
        template.setSource(1);
        template.setTarget(1);
        template.setGoods("all");
        template.setType(1);
        template.setValidStartTime(LocalDateTime.now().plusDays(1));
        template.setValidEndTime(LocalDateTime.now().plusDays(7));
        template.setStock(100);
        template.setReceiveRule("{\"limit\": 1}");
        template.setConsumeRule("{\"minAmount\": 100, \"discount\": 20}");
        template.setStatus(1); // PUBLISHED
        template.setDelFlag(0);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        couponTemplateMapper.insert(template);
        templateId = template.getId();

        // Create user coupon with the unique coupon code
        UserCoupon uc = new UserCoupon();
        uc.setUserId(USER_ID);
        uc.setTemplateId(templateId);
        uc.setCouponCode(couponCode);
        uc.setStatus(UserCouponStatusEnum.UNUSED.getStatus());
        uc.setReceiveTime(LocalDateTime.now());
        uc.setExpireTime(LocalDateTime.now().plusDays(7));
        uc.setDelFlag(0);
        uc.setCreateTime(LocalDateTime.now());
        uc.setUpdateTime(LocalDateTime.now());
        userCouponMapper.insert(uc);
    }

    @AfterEach
    void tearDown() {
        // Hard-delete order (bypass @TableLogic logical delete)
        if (orderNo != null) {
            jdbcTemplate.update("DELETE FROM t_order WHERE order_no = ?", orderNo);
        }
        // Hard-delete user coupon
        jdbcTemplate.update("DELETE FROM t_user_coupon WHERE coupon_code = ?", couponCode);
        // Hard-delete template
        if (templateId != null) {
            jdbcTemplate.update("DELETE FROM t_coupon_template WHERE id = ?", templateId);
        }
    }

    private OrderSubmitRequestDTO buildSubmitDto() {
        OrderSubmitRequestDTO dto = new OrderSubmitRequestDTO();
        dto.setShopId(SHOP_ID);
        dto.setCouponCode(couponCode);
        dto.setOriginalAmount(new BigDecimal("100"));
        OrderSubmitRequestDTO.Item item = new OrderSubmitRequestDTO.Item();
        item.setGoodsId("G001");
        item.setGoodsName("测试商品");
        item.setQuantity(1);
        item.setPrice(new BigDecimal("100"));
        dto.setItems(List.of(item));
        return dto;
    }

    @Test
    void testTimeoutCancel() throws InterruptedException {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();
        assertEquals(OrderStatusEnum.PENDING_PAY.getStatus(), order.getStatus());

        // Coupon should be locked after submit
        UserCoupon coupon = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, couponCode).one();
        assertNotNull(coupon);
        assertEquals(UserCouponStatusEnum.LOCKED.getStatus(), coupon.getStatus());

        // Send a delayed timeout message (1s delay)
        OrderTimeoutMessage msg = new OrderTimeoutMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setOrderNo(orderNo);
        msg.setUserId(USER_ID);
        msg.setCouponCode(couponCode);
        msg.setSentAt(LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                msg,
                m -> {
                    m.getMessageProperties().setHeader("x-delay", 1000);
                    return m;
                });

        // Wait for the message to be processed
        Thread.sleep(3000);

        // Order should be cancelled
        Order cancelled = orderMapper.selectOne(
                new QueryWrapper<Order>().eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.CANCELLED.getStatus(), cancelled.getStatus());

        // Coupon should be released back to UNUSED
        UserCoupon released = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, couponCode).one();
        assertEquals(UserCouponStatusEnum.UNUSED.getStatus(), released.getStatus());
    }

    @Test
    void testIdempotentMessage() {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();

        // Send two identical messages (same messageId)
        String messageId = UUID.randomUUID().toString();
        OrderTimeoutMessage msg = new OrderTimeoutMessage();
        msg.setMessageId(messageId);
        msg.setOrderNo(orderNo);
        msg.setUserId(USER_ID);
        msg.setCouponCode(couponCode);
        msg.setSentAt(LocalDateTime.now());

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

        // Order should be cancelled exactly once
        Order result = orderMapper.selectOne(
                new QueryWrapper<Order>().eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.CANCELLED.getStatus(), result.getStatus());
    }

    @Test
    void testPayBeforeCancel() throws Exception {
        OrderDTO order = orderService.submit(buildSubmitDto(), USER_ID);
        orderNo = order.getOrderNo();

        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: pay the order
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                orderService.pay(orderNo, USER_ID);
            } catch (Exception ignored) {
            }
        });

        // Thread 2: send timeout cancel message
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                OrderTimeoutMessage msg = new OrderTimeoutMessage();
                msg.setMessageId(UUID.randomUUID().toString());
                msg.setOrderNo(orderNo);
                msg.setUserId(USER_ID);
                msg.setCouponCode(couponCode);
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
        // Wait for the delayed cancel message to be processed
        Thread.sleep(2000);

        // Pay should win (the cancel message has 500ms delay and checks status=PENDING_PAY)
        Order result = orderMapper.selectOne(
                new QueryWrapper<Order>().eq("order_no", orderNo));
        assertEquals(OrderStatusEnum.PAID.getStatus(), result.getStatus());

        // Coupon should be marked as USED
        UserCoupon coupon = userCouponService.lambdaQuery()
                .eq(UserCoupon::getCouponCode, couponCode).one();
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
