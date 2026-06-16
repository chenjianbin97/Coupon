package com.example.coupon.dto;

import com.example.coupon.entity.OrderItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDTO {

    private Long id;

    private String orderNo;

    private Long userId;

    private Long shopId;

    private String couponCode;

    private BigDecimal originalAmount;

    private BigDecimal amount;

    private Integer status;

    private LocalDateTime createTime;

    private List<OrderItem> items;
}
