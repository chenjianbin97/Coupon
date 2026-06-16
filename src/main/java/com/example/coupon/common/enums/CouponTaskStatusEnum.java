package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum CouponTaskStatusEnum {

    PENDING(0, "待处理"),
    IN_PROGRESS(1, "执行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败");

    private final int status;
    private final String desc;

    CouponTaskStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
