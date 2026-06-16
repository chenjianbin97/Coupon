package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum CouponTemplateStatusEnum {

    DRAFT(0, "未发布"),
    PUBLISHED(1, "已发布");

    private final int status;
    private final String desc;

    CouponTemplateStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
