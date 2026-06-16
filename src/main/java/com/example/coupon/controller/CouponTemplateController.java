package com.example.coupon.controller;

import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.CouponTemplatePageRequestDTO;
import com.example.coupon.dto.CouponTemplateSaveRequestDTO;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.service.CouponTemplateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/coupon-template")
public class CouponTemplateController {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @PostMapping("/save")
    public Result saveTemplate(@Valid @RequestBody CouponTemplateSaveRequestDTO dto,
                               @RequestHeader(value = "Idempotent-Token", required = false) String idempotentToken) {
        boolean saved = couponTemplateService.saveTemplate(dto, UserContext.getUser().getId(), idempotentToken);
        return Result.success(saved);
    }

    @GetMapping("/{id}")
    public Result getTemplate(@PathVariable Long id) {
        CouponTemplateDTO dto = couponTemplateService.getTemplate(id);
        return Result.success(dto);
    }

    @GetMapping("/{id}/cached")
    public Result getTemplateWithCache(@PathVariable Long id) {
        CouponTemplateDTO dto = couponTemplateService.getTemplateWithCache(id);
        return Result.success(dto);
    }

    @GetMapping("/list")
    public Result pageTemplate(CouponTemplatePageRequestDTO dto) {
        List<CouponTemplateDTO> list = couponTemplateService.pageTemplate(dto);
        return Result.success(list);
    }

    @PutMapping("/update")
    public Result updateTemplate(@RequestBody CouponTemplateDTO dto) {
        boolean updated = couponTemplateService.updateTemplate(dto);
        return Result.success(updated);
    }

    @DeleteMapping("/{id}")
    public Result deleteTemplate(@PathVariable Long id) {
        boolean deleted = couponTemplateService.deleteTemplate(id);
        return Result.success(deleted);
    }

    @PostMapping("/publish")
    public Result publish(@RequestParam Long id) {
        boolean published = couponTemplateService.publish(id);
        return Result.success(published);
    }
}
