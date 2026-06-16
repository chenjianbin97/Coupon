package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum CouponDetailStatusEnum {

    PENDING(0, "待处理"),
    SUCCESS(1, "成功"),
    FAILED(2, "失败");

    private final int status;
    private final String desc;

    CouponDetailStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
