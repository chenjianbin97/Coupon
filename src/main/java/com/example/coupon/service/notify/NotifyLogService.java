package com.example.coupon.service.notify;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.entity.NotifyLog;
import com.example.coupon.mapper.NotifyLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class NotifyLogService extends ServiceImpl<NotifyLogMapper, NotifyLog> {

    public void record(NotifyMessage msg, int channel, String status, String title,
                       String content, String failReason) {
        NotifyLog logEntry = new NotifyLog();
        logEntry.setTraceId(msg.getMessageId());
        logEntry.setUserId(msg.getUserId());
        logEntry.setScene(msg.getScene());
        logEntry.setChannel(channel);
        logEntry.setStatus(status);
        logEntry.setTitle(title);
        logEntry.setContent(content);
        logEntry.setFailReason(failReason);
        logEntry.setSendTime(msg.getSendTime());
        logEntry.setCreateTime(LocalDateTime.now());
        save(logEntry);
        log.info("通知记录已保存，traceId={}，channel={}，status={}", msg.getMessageId(), channel, status);
    }
}
