package com.example.coupon.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
public class CouponTaskExecuteMessage implements Serializable {

    private Long taskId;

    private Long templateId;

    private List<Long> userIds;

    private String messageId;

    public CouponTaskExecuteMessage(Long taskId, Long templateId, List<Long> userIds, String messageId) {
        this.taskId = taskId;
        this.templateId = templateId;
        this.userIds = userIds;
        this.messageId = messageId;
    }
}
