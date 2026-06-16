package com.example.coupon.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderSubmitRequestDTO {

    @NotNull
    private Long shopId;

    @NotEmpty
    private String couponCode;

    @NotNull
    private BigDecimal originalAmount;

    @NotEmpty
    private List<Item> items;

    @Data
    public static class Item {
        private String goodsId;
        private String goodsName;
        private Integer quantity;
        private BigDecimal price;
    }
}
