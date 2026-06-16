package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserCouponDTO {

    private Long id;

    private Long userId;

    private Long templateId;

    private String couponCode;

    private Integer status;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    private LocalDateTime expireTime;

    private CouponTemplateDTO templateInfo;
}
