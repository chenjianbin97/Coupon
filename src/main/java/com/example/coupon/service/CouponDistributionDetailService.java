package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.coupon.entity.CouponDistributionDetail;

public interface CouponDistributionDetailService extends IService<CouponDistributionDetail> {

    void removePhysicalByTaskId(Long taskId);
}
