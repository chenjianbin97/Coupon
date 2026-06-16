package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum MessageTemplateStatusEnum {

    DISABLED(0, "停用"),
    ENABLED(1, "启用");

    private final int status;
    private final String desc;

    MessageTemplateStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
