package com.example.coupon.service.notify;

public interface MessageChannel {

    int getChannel();

    boolean send(Long userId, String target, String title, String content);

    boolean isAvailable();
}
