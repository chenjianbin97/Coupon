package com.example.coupon.dto;

import com.example.coupon.entity.CouponSubscription;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SubscriptionNotifyDTO extends CouponSubscription {

    private LocalDateTime validStartTime;

    private String templateName;
}
