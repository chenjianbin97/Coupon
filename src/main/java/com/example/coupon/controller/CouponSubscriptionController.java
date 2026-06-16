package com.example.coupon.controller;

import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.SubscriptionDTO;
import com.example.coupon.service.CouponSubscriptionService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CouponSubscriptionController {

    @Autowired
    private CouponSubscriptionService subscriptionService;

    @Data
    public static class SubscribeRequest {
        private List<Integer> offsets;
        private Integer method;
    }

    @GetMapping("/user/subscriptions")
    public Result listMySubscriptions() {
        List<SubscriptionDTO> list = subscriptionService.listMySubscriptions(UserContext.getUser().getId());
        return Result.success(list);
    }

    @PostMapping("/coupon-template/{templateId}/subscribe")
    public Result subscribe(@PathVariable Long templateId, @RequestBody SubscribeRequest request) {
        subscriptionService.subscribe(
                UserContext.getUser().getId(),
                templateId,
                request.getOffsets(),
                request.getMethod());
        return Result.success(true);
    }

    @PutMapping("/coupon-template/{templateId}/subscribe")
    public Result updateSubscription(@PathVariable Long templateId, @RequestBody SubscribeRequest request) {
        subscriptionService.updateSubscription(
                UserContext.getUser().getId(),
                templateId,
                request.getOffsets(),
                request.getMethod());
        return Result.success(true);
    }

    @DeleteMapping("/coupon-template/{templateId}/subscribe")
    public Result unsubscribe(@PathVariable Long templateId) {
        subscriptionService.unsubscribe(UserContext.getUser().getId(), templateId);
        return Result.success(true);
    }
}
