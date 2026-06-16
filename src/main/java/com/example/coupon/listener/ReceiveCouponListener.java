package com.example.coupon.listener;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.CouponReceiveMessage;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserCouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
public class ReceiveCouponListener {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private UserCouponService userCouponService;

    @Transactional(timeout = 30)
    @RabbitListener(queues = RabbitMQConfig.COUPON_RECEIVE_QUEUE)
    public void onMessage(CouponReceiveMessage message) {
        log.info("处理领券消息，userId={}，templateId={}，messageId={}",
                message.getUserId(), message.getTemplateId(), message.getMessageId());

        String msgKey = String.format(RedisConstant.IDEMPOTENT_MSG_KEY, message.getMessageId());
        Boolean first = redisTemplate.opsForValue().setIfAbsent(msgKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("消息已处理，跳过，messageId={}", message.getMessageId());
            return;
        }

        try {
            if (!couponTemplateService.deductStock(message.getTemplateId())) {
                throw new BusinessException("优惠券库存不足");
            }

            UserCoupon uc = new UserCoupon();
            uc.setUserId(message.getUserId());
            uc.setTemplateId(message.getTemplateId());
            uc.setCouponCode(message.getCouponCode());
            uc.setStatus(UserCouponStatusEnum.UNUSED.getStatus());
            uc.setReceiveTime(LocalDateTime.now());
            uc.setExpireTime(message.getExpireTime());
            userCouponService.save(uc);

            log.info("领券异步处理完成，userId={}，templateId={}", message.getUserId(), message.getTemplateId());
        } catch (DuplicateKeyException e) {
            log.info("用户已领取该优惠券，视为成功，userId={}，templateId={}", message.getUserId(), message.getTemplateId());
            redisTemplate.delete(msgKey);
            throw e;
        } catch (Exception e) {
            redisTemplate.delete(msgKey);
            throw e;
        }
    }
}
