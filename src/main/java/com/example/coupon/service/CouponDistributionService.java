package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.dto.CouponDistributeRequestDTO;
import com.example.coupon.dto.CouponDistributionDetailDTO;
import com.example.coupon.dto.CouponDistributionTaskDTO;

public interface CouponDistributionService {

    Long createDistributeTask(CouponDistributeRequestDTO dto, Long operatorId);

    CouponDistributionTaskDTO getTask(Long id);

    Page<CouponDistributionDetailDTO> listTaskDetails(Long taskId, Integer status, Long page, Long size);

    Long retryFailedUsers(Long taskId, Long operatorId);
}
