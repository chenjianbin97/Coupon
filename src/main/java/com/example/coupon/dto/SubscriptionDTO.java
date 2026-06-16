package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SubscriptionDTO {

    private Long id;
    private Long templateId;
    private String templateName;
    private LocalDateTime validStartTime;
    private List<Integer> offsets;
    private Integer notifyMethod;
    private Integer status;
    private Integer lastNotifiedBits;
}
