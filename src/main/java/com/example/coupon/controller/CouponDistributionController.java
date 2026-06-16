package com.example.coupon.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.CouponDistributeRequestDTO;
import com.example.coupon.dto.CouponDistributionDetailDTO;
import com.example.coupon.dto.CouponDistributionTaskDTO;
import com.example.coupon.service.CouponDistributionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupon-distribution")
public class CouponDistributionController {

    @Autowired
    private CouponDistributionService couponDistributionService;

    @PostMapping("/distribute")
    public Result<Long> distribute(@Valid @RequestBody CouponDistributeRequestDTO dto) {
        Long taskId = couponDistributionService.createDistributeTask(dto, UserContext.getUser().getId());
        return Result.success(taskId);
    }

    @GetMapping("/task/{id}")
    public Result<CouponDistributionTaskDTO> getTask(@PathVariable Long id) {
        CouponDistributionTaskDTO dto = couponDistributionService.getTask(id);
        return Result.success(dto);
    }

    @GetMapping("/task/{id}/details")
    public Result<Page<CouponDistributionDetailDTO>> listDetails(
            @PathVariable Long id,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size) {
        Page<CouponDistributionDetailDTO> result = couponDistributionService.listTaskDetails(id, status, page, size);
        return Result.success(result);
    }

    @PostMapping("/task/{id}/retry")
    public Result<Long> retryFailed(@PathVariable Long id) {
        Long count = couponDistributionService.retryFailedUsers(id, UserContext.getUser().getId());
        return Result.success(count);
    }
}
