package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_coupon_distribution_task")
public class CouponDistributionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long shopId;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    private Integer processedCount;

    private Integer status;

    private String queryCondition;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
