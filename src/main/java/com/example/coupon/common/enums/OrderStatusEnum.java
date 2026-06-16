package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum OrderStatusEnum {
    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    CANCELLED(2, "已取消");

    private final int status;
    private final String desc;

    OrderStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
