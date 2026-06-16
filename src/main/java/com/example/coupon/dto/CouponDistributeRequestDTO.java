package com.example.coupon.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CouponDistributeRequestDTO {

    @NotNull(message = "优惠券模板ID不能为空")
    private Long templateId;

    @NotEmpty(message = "用户列表不能为空")
    private List<Long> userIds;
}
