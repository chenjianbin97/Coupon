package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum CouponTypeEnum {

    FULL_REDUCTION(1, "满减券"),
    DISCOUNT(2, "折扣券"),
    DIRECT_REDUCTION(3, "直降券");

    private final int type;
    private final String desc;

    CouponTypeEnum(int type, String desc) {
        this.type = type;
        this.desc = desc;
    }
}
