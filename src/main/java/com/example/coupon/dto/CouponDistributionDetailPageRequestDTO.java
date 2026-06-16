package com.example.coupon.dto;

import lombok.Data;

@Data
public class CouponDistributionDetailPageRequestDTO {

    private Long taskId;

    private Integer status;

    private Long page = 1L;

    private Long size = 20L;
}
