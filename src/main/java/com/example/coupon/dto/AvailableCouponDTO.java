package com.example.coupon.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AvailableCouponDTO {
    private String couponCode;
    private String templateName;
    private Integer type;
    private String typeDesc;
    private String discountDesc;
    private LocalDateTime expireTime;
}
