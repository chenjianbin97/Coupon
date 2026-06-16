package com.example.coupon.controller;

import com.example.coupon.common.annotation.RateLimit;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.AvailableCouponDTO;
import com.example.coupon.dto.ReceiveCouponRequestDTO;
import com.example.coupon.dto.UseCouponRequestDTO;
import com.example.coupon.dto.UserCouponDTO;
import com.example.coupon.service.UserCouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/coupon")
public class UserCouponController {

    @Autowired
    private UserCouponService userCouponService;

    // @RateLimit(permits = 20, window = 60)  // 暂时注释用于 JMeter 性能对比
    @PostMapping("/receive")
    public Result receiveCoupon(@RequestBody ReceiveCouponRequestDTO dto) {
        String code = userCouponService.asyncReceiveCoupon(dto, UserContext.getUser().getId());
        return Result.success(code);
    }

    @PostMapping("/receive-sync")
    public Result syncReceiveCoupon(@RequestBody ReceiveCouponRequestDTO dto) {
        String code = userCouponService.syncReceiveCoupon(dto, UserContext.getUser().getId());
        return Result.success(code);
    }

    @PostMapping("/receive-db")
    public Result dbReceiveCoupon(@RequestBody ReceiveCouponRequestDTO dto) {
        String code = userCouponService.receiveCoupon(dto, UserContext.getUser().getId());
        return Result.success(code);
    }

    @PostMapping("/use")
    public Result useCoupon(@RequestBody UseCouponRequestDTO dto) {
        boolean used = userCouponService.useCoupon(dto, UserContext.getUser().getId());
        return Result.success(used);
    }

    @GetMapping("/my-list")
    public Result listMyCoupons() {
        List<UserCouponDTO> list = userCouponService.listMyCoupons(UserContext.getUser().getId());
        return Result.success(list);
    }

    @GetMapping("/available")
    public Result listAvailableCoupons(@RequestParam Long shopId,
                                       @RequestParam BigDecimal originalAmount,
                                       @RequestParam String goodsIds) {
        List<Long> ids = Arrays.stream(goodsIds.split(","))
                .map(Long::valueOf).toList();
        List<AvailableCouponDTO> list = userCouponService.listAvailableCoupons(
                UserContext.getUser().getId(), shopId, originalAmount, ids);
        return Result.success(list);
    }
}
