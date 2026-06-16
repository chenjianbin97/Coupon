package com.example.coupon.controller;

import com.example.coupon.common.annotation.RateLimit;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;
import com.example.coupon.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @RateLimit(permits = 5, window = 60)
    @PostMapping("/submit")
    public Result submit(@Valid @RequestBody OrderSubmitRequestDTO dto) {
        OrderDTO order = orderService.submit(dto, UserContext.getUser().getId());
        return Result.success(order);
    }

    @PostMapping("/{orderNo}/pay")
    public Result pay(@PathVariable String orderNo) {
        orderService.pay(orderNo, UserContext.getUser().getId());
        return Result.success(true);
    }

    @PostMapping("/{orderNo}/cancel")
    public Result cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo, UserContext.getUser().getId());
        return Result.success(true);
    }
}
