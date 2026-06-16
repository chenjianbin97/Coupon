package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_coupon_template")
public class Coupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long shopNumber;

    private Integer source;

    private Integer target;

    private String goods;

    private Integer type;

    private LocalDateTime validStartTime;

    private LocalDateTime validEndTime;

    private Integer stock;

    private String receiveRule;

    private String consumeRule;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
