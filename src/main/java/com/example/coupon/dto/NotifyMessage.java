package com.example.coupon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class NotifyMessage implements Serializable {

    private String messageId;

    private Long userId;

    private String scene;

    private String templateCode;

    private Map<String, String> params;

    private int priority;

    private LocalDateTime sendTime;
}
