package com.example.coupon.service.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogNotifier implements CouponNotifier {

    public static final int CHANNEL_IN_APP = 1 << 0;

    @Override
    public int getChannel() {
        return CHANNEL_IN_APP;
    }

    @Override
    public void notify(Long userId, Long templateId, String message) {
        log.info("[预约通知-站内信] userId={}, templateId={}, msg={}", userId, templateId, message);
    }
}
