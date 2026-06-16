package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum UserCouponStatusEnum {

    UNUSED(0, "未使用"),
    USED(1, "已使用"),
    LOCKED(2, "已锁定");

    private final int status;
    private final String desc;

    UserCouponStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
