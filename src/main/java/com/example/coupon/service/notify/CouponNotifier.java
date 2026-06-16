package com.example.coupon.service.notify;

@Deprecated
public interface CouponNotifier {

    @Deprecated
    int getChannel();

    @Deprecated
    void notify(Long userId, Long templateId, String message);
}
