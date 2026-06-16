package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponReceiveMessage implements Serializable {

    private Long userId;

    private Long templateId;

    private String couponCode;

    private LocalDateTime expireTime;

    private String messageId;
}
