package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.common.enums.SubscriptionStatusEnum;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.SubscriptionDTO;
import com.example.coupon.dto.SubscriptionNotifyDTO;
import com.example.coupon.entity.CouponSubscription;
import com.example.coupon.mapper.CouponSubscriptionMapper;
import com.example.coupon.service.CouponSubscriptionService;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CouponSubscriptionServiceImpl extends ServiceImpl<CouponSubscriptionMapper, CouponSubscription>
        implements CouponSubscriptionService {

    private static final int[] OFFSET_MINUTES = {5, 10, 15, 30, 60};
    private static final int OFFSET_BITS = 5;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public void subscribe(Long userId, Long templateId, List<Integer> offsets, Integer method) {
        CouponTemplateDTO template = couponTemplateService.getTemplate(templateId);
        if (template == null) {
            throw new BusinessException("优惠券模板不存在");
        }

        CouponSubscription existing = getOne(new QueryWrapper<CouponSubscription>()
                .eq("user_id", userId).eq("template_id", templateId));
        if (existing != null) {
            throw new BusinessException("已订阅该优惠券");
        }

        int offsetBits = 0;
        for (Integer offset : offsets) {
            offsetBits |= toBit(offset);
        }

        CouponSubscription sub = new CouponSubscription();
        sub.setUserId(userId);
        sub.setTemplateId(templateId);
        sub.setNotifyOffsets(offsetBits);
        sub.setNotifyMethod(method != null ? method : 1);
        sub.setStatus(SubscriptionStatusEnum.ACTIVE.getStatus());
        sub.setCreateTime(LocalDateTime.now());
        save(sub);

        log.info("订阅成功，userId={}，templateId={}，offsets={}，method={}", userId, templateId, offsetBits, method);
    }

    @Override
    @Transactional
    public void unsubscribe(Long userId, Long templateId) {
        lambdaUpdate()
                .eq(CouponSubscription::getUserId, userId)
                .eq(CouponSubscription::getTemplateId, templateId)
                .set(CouponSubscription::getStatus, SubscriptionStatusEnum.CANCELLED.getStatus())
                .update();
        log.info("取消订阅，userId={}，templateId={}", userId, templateId);
    }

    @Scheduled(cron = "0 * * * * ?")
    public void checkAndNotify() {
        List<SubscriptionNotifyDTO> subs = baseMapper.selectPendingWithStartTime();
        if (subs.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (SubscriptionNotifyDTO sub : subs) {
            int pending = sub.getNotifyOffsets() & ~sub.getLastNotifiedBits();
            if (pending == 0) continue;

            int notified = 0;
            for (int bit = 0; bit < OFFSET_BITS; bit++) {
                if ((pending & (1 << bit)) == 0) continue;
                LocalDateTime notifyAt = sub.getValidStartTime().minusMinutes(OFFSET_MINUTES[bit]);
                if (now.isBefore(notifyAt)) continue;

                String messageId = UUID.randomUUID().toString();
                NotifyMessage nm = new NotifyMessage();
                nm.setMessageId(messageId);
                nm.setUserId(sub.getUserId());
                nm.setScene("COUPON_ONLINE");
                nm.setTemplateCode("COUPON_ONLINE");
                nm.setParams(Map.of("template_name",
                        sub.getTemplateName() != null ? sub.getTemplateName() : "未知模板"));
                nm.setPriority(1);
                nm.setSendTime(LocalDateTime.now());

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                        RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                        nm);

                log.info("通知消息已发送到MQ，messageId={}，userId={}，templateId={}",
                        messageId, sub.getUserId(), sub.getTemplateId());
                notified |= (1 << bit);
            }

            if (notified > 0) {
                int newBits = sub.getLastNotifiedBits() | notified;
                lambdaUpdate()
                        .eq(CouponSubscription::getId, sub.getId())
                        .set(CouponSubscription::getLastNotifiedBits, newBits)
                        .set(newBits == sub.getNotifyOffsets(),
                                CouponSubscription::getStatus, SubscriptionStatusEnum.ALL_NOTIFIED.getStatus())
                        .update();
            }
        }
    }

    private int toBit(int offsetMinutes) {
        for (int i = 0; i < OFFSET_BITS; i++) {
            if (OFFSET_MINUTES[i] == offsetMinutes) return 1 << i;
        }
        throw new IllegalArgumentException("不支持的通知偏移: " + offsetMinutes);
    }

    @Override
    public List<SubscriptionDTO> listMySubscriptions(Long userId) {
        return baseMapper.selectList(
                new QueryWrapper<CouponSubscription>()
                        .eq("user_id", userId)
                        .eq("status", SubscriptionStatusEnum.ACTIVE.getStatus())
                        .eq("del_flag", 0))
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional
    public void updateSubscription(Long userId, Long templateId, List<Integer> offsets, Integer method) {
        int offsetBits = 0;
        for (Integer offset : offsets) {
            offsetBits |= toBit(offset);
        }
        boolean updated = lambdaUpdate()
                .eq(CouponSubscription::getUserId, userId)
                .eq(CouponSubscription::getTemplateId, templateId)
                .ne(CouponSubscription::getStatus, SubscriptionStatusEnum.CANCELLED.getStatus())
                .set(CouponSubscription::getNotifyOffsets, offsetBits)
                .set(CouponSubscription::getNotifyMethod, method != null ? method : 1)
                .set(CouponSubscription::getLastNotifiedBits, 0)
                .update();
        if (!updated) {
            throw new BusinessException("订阅不存在或已取消");
        }
        log.info("修改订阅成功，userId={}，templateId={}，offsets={}，method={}", userId, templateId, offsetBits, method);
    }

    private SubscriptionDTO toDTO(CouponSubscription sub) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setId(sub.getId());
        dto.setTemplateId(sub.getTemplateId());
        dto.setOffsets(toOffsetList(sub.getNotifyOffsets()));
        dto.setNotifyMethod(sub.getNotifyMethod());
        dto.setStatus(sub.getStatus());
        dto.setLastNotifiedBits(sub.getLastNotifiedBits());
        return dto;
    }

    private List<Integer> toOffsetList(int bits) {
        List<Integer> result = new java.util.ArrayList<>();
        for (int i = 0; i < OFFSET_BITS; i++) {
            if ((bits & (1 << i)) != 0) {
                result.add(OFFSET_MINUTES[i]);
            }
        }
        return result;
    }

}
