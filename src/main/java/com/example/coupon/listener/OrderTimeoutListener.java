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
