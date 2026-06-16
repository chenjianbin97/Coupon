package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum SubscriptionStatusEnum {

    ACTIVE(0, "有效"),
    ALL_NOTIFIED(1, "全部通知完"),
    CANCELLED(2, "已取消");

    private final int status;
    private final String desc;

    SubscriptionStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
