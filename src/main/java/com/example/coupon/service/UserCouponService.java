package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.coupon.dto.ReceiveCouponRequestDTO;
import com.example.coupon.dto.UseCouponRequestDTO;
import com.example.coupon.dto.UserCouponDTO;
import com.example.coupon.entity.UserCoupon;

import com.example.coupon.dto.AvailableCouponDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface UserCouponService extends IService<UserCoupon> {

    String asyncReceiveCoupon(ReceiveCouponRequestDTO dto, Long userId);

    String syncReceiveCoupon(ReceiveCouponRequestDTO dto, Long userId);

    String receiveCoupon(ReceiveCouponRequestDTO dto, Long userId);

    int batchReceiveForDistribution(Long templateId, List<Long> userIds, LocalDateTime expireTime);

    boolean useCoupon(UseCouponRequestDTO dto, Long userId);

    List<UserCouponDTO> listMyCoupons(Long userId);

    List<AvailableCouponDTO> listAvailableCoupons(Long userId, Long shopId, BigDecimal originalAmount, List<Long> goodsIds);
}
