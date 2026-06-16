package com.example.coupon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OrderTimeoutMessage implements Serializable {

    private String messageId;

    private String orderNo;

    private Long userId;

    private String couponCode;

    private LocalDateTime sentAt;
}
