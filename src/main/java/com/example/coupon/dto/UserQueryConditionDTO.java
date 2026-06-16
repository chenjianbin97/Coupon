package com.example.coupon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class UserQueryConditionDTO implements Serializable {

    private Long shopNumber;

    private Integer status;

    private LocalDateTime registerTimeStart;

    private LocalDateTime registerTimeEnd;

    private Boolean hasPhone;

    private Boolean hasEmail;
}
