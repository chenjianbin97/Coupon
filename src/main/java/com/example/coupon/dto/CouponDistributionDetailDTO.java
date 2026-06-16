package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponDistributionDetailDTO {

    private Long id;

    private Long taskId;

    private Long userId;

    private Integer status;

    private String failReason;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
