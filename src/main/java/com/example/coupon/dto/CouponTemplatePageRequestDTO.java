package com.example.coupon.dto;

import lombok.Data;

@Data
public class CouponTemplatePageRequestDTO {

    private Long page = 1L;

    private Long size = 10L;

    private String name;

    private Long shopNumber;

    private Integer status;
}
