package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_coupon_subscription")
public class CouponSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long templateId;

    private Integer notifyOffsets;

    private Integer lastNotifiedBits;

    private Integer notifyMethod;

    private Integer status;

    private LocalDateTime createTime;

    @TableLogic
    private Integer delFlag;
}
