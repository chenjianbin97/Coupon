package com.example.coupon.service;

import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;

public interface OrderService {

    OrderDTO submit(OrderSubmitRequestDTO dto, Long userId);

    void pay(String orderNo, Long userId);

    void cancel(String orderNo, Long userId);
}
