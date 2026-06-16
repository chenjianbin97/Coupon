package com.example.coupon.listener;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.service.notify.MessageDispatchEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDispatchListener {

    private final MessageDispatchEngine dispatchEngine;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.NOTIFY_DISPATCH_QUEUE)
    public void onNotifyMessage(NotifyMessage msg) {
        log.info("收到通知分发消息，messageId={}，userId={}，scene={}，priority={}",
                msg.getMessageId(), msg.getUserId(), msg.getScene(), msg.getPriority());

        String msgKey = String.format(RedisConstant.IDEMPOTENT_NOTIFY_KEY, msg.getMessageId());
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(msgKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("消息已处理，跳过，messageId={}", msg.getMessageId());
            return;
        }

        try {
            MessageDispatchEngine.DispatchResult result = dispatchEngine.dispatch(msg);
            log.info("通知分发完成，messageId={}，success={}，channel={}，message={}",
                    msg.getMessageId(), result.success(), result.channel(), result.message());
        } catch (DuplicateKeyException e) {
            log.debug("幂等写入冲突，视为成功，messageId={}", msg.getMessageId());
        } catch (Exception e) {
            log.error("通知分发处理异常，messageId={}，error={}", msg.getMessageId(), e.getMessage());
            redisTemplate.delete(msgKey);
            throw e;
        }
    }
}
