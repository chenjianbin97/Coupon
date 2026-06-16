package com.example.coupon.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponTemplateSaveRequestDTO {

    @NotBlank(message = "优惠券名称不能为空")
    @Size(max = 64, message = "优惠券名称长度不能超过64")
    private String name;

    @NotNull(message = "店铺编号不能为空")
    private Long shopNumber;

    @NotNull(message = "来源不能为空")
    private Integer source;

    @NotNull(message = "目标用户不能为空")
    private Integer target;

    @NotBlank(message = "适用商品不能为空")
    private String goods;

    @NotNull(message = "优惠券类型不能为空")
    @Min(value = 1, message = "优惠券类型无效")
    @Max(value = 3, message = "优惠券类型无效")
    private Integer type;

    @NotNull(message = "有效期开始时间不能为空")
    private LocalDateTime validStartTime;

    @NotNull(message = "有效期结束时间不能为空")
    private LocalDateTime validEndTime;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @NotBlank(message = "领取规则不能为空")
    private String receiveRule;

    @NotBlank(message = "消费规则不能为空")
    private String consumeRule;
}
