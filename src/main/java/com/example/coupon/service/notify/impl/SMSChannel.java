package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SMSChannel implements MessageChannel {

    public static final int CHANNEL_SMS = 4;

    @Override
    public int getChannel() {
        return CHANNEL_SMS;
    }

    @Override
    public boolean send(Long userId, String phone, String title, String content) {
        // TODO: 对接阿里云/腾讯云短信 SDK
        // SendSmsResponse response = smsClient.send(phone, content);
        log.info("[SMS] userId={}, phone={}, content={}", userId, phone, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
