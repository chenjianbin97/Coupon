package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogChannel implements MessageChannel {

    public static final int CHANNEL_LOG = 1;

    @Override
    public int getChannel() {
        return CHANNEL_LOG;
    }

    @Override
    public boolean send(Long userId, String target, String title, String content) {
        log.info("[通知-站内] userId={}, title={}, content={}", userId, title, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
