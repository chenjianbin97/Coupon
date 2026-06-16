package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_notify_log")
public class NotifyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long userId;

    private String scene;

    private Integer channel;

    private String status;

    private String title;

    private String content;

    private String failReason;

    private LocalDateTime sendTime;

    private LocalDateTime deliverTime;

    private LocalDateTime createTime;
}
