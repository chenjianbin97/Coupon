package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushChannel implements MessageChannel {

    public static final int CHANNEL_PUSH = 2;

    @Override
    public int getChannel() {
        return CHANNEL_PUSH;
    }

    @Override
    public boolean send(Long userId, String deviceToken, String title, String content) {
        // TODO: 对接个推/极光推送 SDK
        // PushResult result = getuiClient.pushToSingle(deviceToken, title, content);
        log.info("[Push] userId={}, deviceToken={}, title={}, content={}", userId, deviceToken, title, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
