package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message_template")
public class MessageTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String pushTitle;

    private String pushBody;

    private String smsContent;

    private String variables;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
