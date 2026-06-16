package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.mapper.CouponDistributionDetailMapper;
import com.example.coupon.service.CouponDistributionDetailService;
import org.springframework.stereotype.Service;

@Service
public class CouponDistributionDetailServiceImpl extends ServiceImpl<CouponDistributionDetailMapper, CouponDistributionDetail> implements CouponDistributionDetailService {

    @Override
    public void removePhysicalByTaskId(Long taskId) {
        baseMapper.delete(new QueryWrapper<CouponDistributionDetail>().eq("task_id", taskId));
    }
}
