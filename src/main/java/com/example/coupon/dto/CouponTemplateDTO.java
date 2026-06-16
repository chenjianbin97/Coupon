package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponTemplateDTO {

    private Long id;

    private String name;

    private Long shopNumber;

    private Integer source;

    private Integer target;

    private String goods;

    private Integer type;

    private LocalDateTime validStartTime;

    private LocalDateTime validEndTime;

    private String receiveRule;

    private String consumeRule;

    private Integer status;
}
