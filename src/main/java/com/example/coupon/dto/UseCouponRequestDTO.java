package com.example.coupon.dto;

import lombok.Data;

@Data
public class UseCouponRequestDTO {

    private String couponCode;

    private Long orderId;
}
