package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponDistributionTaskDTO {

    private Long id;

    private Long templateId;

    private Long shopId;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    private Integer processedCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
