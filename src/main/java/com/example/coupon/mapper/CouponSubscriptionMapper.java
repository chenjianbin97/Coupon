package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.dto.SubscriptionNotifyDTO;
import com.example.coupon.entity.CouponSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CouponSubscriptionMapper extends BaseMapper<CouponSubscription> {

    @Select("""
        SELECT s.*, t.valid_start_time, t.name as template_name
        FROM t_coupon_subscription s
        JOIN t_coupon_template t ON t.id = s.template_id
        WHERE s.status = 0
          AND s.notify_offsets != s.last_notified_bits
          AND t.valid_start_time <= DATE_ADD(NOW(), INTERVAL 60 MINUTE)
          AND t.valid_start_time >= NOW()
          AND t.status = 1
        ORDER BY t.valid_start_time ASC
        LIMIT 500
    """)
    List<SubscriptionNotifyDTO> selectPendingWithStartTime();
}
