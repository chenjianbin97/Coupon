package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.CouponTemplatePageRequestDTO;
import com.example.coupon.dto.CouponTemplateSaveRequestDTO;
import com.example.coupon.entity.Coupon;

import java.util.List;

public interface CouponTemplateService extends IService<Coupon> {

    boolean saveTemplate(CouponTemplateSaveRequestDTO dto, Long userId, String idempotentToken);

    CouponTemplateDTO getTemplate(Long id);

    CouponTemplateDTO getTemplateWithCache(Long id);

    List<CouponTemplateDTO> pageTemplate(CouponTemplatePageRequestDTO dto);

    boolean updateTemplate(CouponTemplateDTO dto);

    boolean deleteTemplate(Long id);

    boolean publish(Long id);

    boolean deductStock(Long id);
}
