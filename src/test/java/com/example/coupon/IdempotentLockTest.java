package com.example.coupon;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.dto.CouponTemplateSaveRequestDTO;
import com.example.coupon.service.CouponTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class IdempotentLockTest {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Long userId = 99999L;
    private final String token = "test-token-uuid-1234";

    @AfterEach
    void tearDown() {
        redisTemplate.delete(String.format(RedisConstant.IDEMPOTENT_KEY, RedisConstant.SAVE_TEMPLATE_SCENE, token));
        redisTemplate.delete(String.format(RedisConstant.USER_LOCK_KEY, userId, RedisConstant.SAVE_TEMPLATE_SCENE));
    }

    private CouponTemplateSaveRequestDTO buildDto() {
        CouponTemplateSaveRequestDTO dto = new CouponTemplateSaveRequestDTO();
        dto.setName("锁测试券_" + System.currentTimeMillis());
        dto.setShopNumber(1L);
        dto.setSource(1);
        dto.setTarget(1);
        dto.setGoods("all");
        dto.setType(1);
        dto.setValidStartTime(LocalDateTime.now().plusDays(1));
        dto.setValidEndTime(LocalDateTime.now().plusDays(7));
        dto.setStock(100);
        dto.setReceiveRule("{\"limit\": 1}");
        dto.setConsumeRule("{\"minAmount\": 100, \"discount\": 20}");
        return dto;
    }

    @Test
    void testRequestLevelLock() {
        CouponTemplateSaveRequestDTO dto = buildDto();

        // 第一次请求成功
        boolean first = couponTemplateService.saveTemplate(dto, userId, token);
        assertTrue(first);

        // 同一 token 第二次请求被请求级锁拦住
        BusinessException ex = assertThrows(BusinessException.class, () ->
                couponTemplateService.saveTemplate(buildDto(), userId, token)
        );
        assertEquals("请勿重复提交", ex.getMessage());
    }

    @Test
    void testUserLevelLock() throws InterruptedException {
        CouponTemplateSaveRequestDTO dto1 = buildDto();
        String token1 = "token-a-" + System.currentTimeMillis();
        String token2 = "token-b-" + System.currentTimeMillis();

        // 第一次请求成功
        boolean first = couponTemplateService.saveTemplate(dto1, userId, token1);
        assertTrue(first);

        // 不同 token、同一用户、3 秒内第二次请求被用户级锁拦住
        BusinessException ex = assertThrows(BusinessException.class, () ->
                couponTemplateService.saveTemplate(buildDto(), userId, token2)
        );
        assertEquals("操作过于频繁，请稍后再试", ex.getMessage());

        // 清理用户级锁后再次请求成功（需换新的 token，因为 token2 的请求级锁已在第二次尝试时被设置）
        redisTemplate.delete(String.format(RedisConstant.USER_LOCK_KEY, userId, RedisConstant.SAVE_TEMPLATE_SCENE));
        String token3 = "token-c-" + System.currentTimeMillis();
        boolean third = couponTemplateService.saveTemplate(buildDto(), userId, token3);
        assertTrue(third);
    }

    @Test
    void testNoIdempotentToken() {
        // 不传 token 时，只受用户级锁约束
        CouponTemplateSaveRequestDTO dto = buildDto();
        boolean first = couponTemplateService.saveTemplate(dto, userId, null);
        assertTrue(first);
    }
}
