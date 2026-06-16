package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.coupon.dto.SubscriptionDTO;
import com.example.coupon.entity.CouponSubscription;

import java.util.List;

public interface CouponSubscriptionService extends IService<CouponSubscription> {

    void subscribe(Long userId, Long templateId, List<Integer> offsets, Integer method);

    void unsubscribe(Long userId, Long templateId);

    List<SubscriptionDTO> listMySubscriptions(Long userId);

    void updateSubscription(Long userId, Long templateId, List<Integer> offsets, Integer method);
}
