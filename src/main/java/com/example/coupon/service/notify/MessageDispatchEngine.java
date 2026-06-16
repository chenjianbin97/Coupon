package com.example.coupon.service.notify;

import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.entity.User;
import com.example.coupon.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDispatchEngine {

    private final Map<String, MessageChannel> channels;
    private final MessageTemplateService templateService;
    private final NotifyLogService notifyLogService;
    private final FrequencyGuard frequencyGuard;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    public DispatchResult dispatch(NotifyMessage msg) {
        // 1. Render template
        MessageTemplate tpl = templateService.getByCode(msg.getTemplateCode());
        if (tpl == null) {
            log.warn("消息模板不存在，code={}", msg.getTemplateCode());
            return DispatchResult.failed("模板不存在: " + msg.getTemplateCode());
        }

        String title = templateService.render(tpl.getPushTitle(), msg.getParams());
        String content = templateService.render(tpl.getPushBody(), msg.getParams());

        // 2. 内容去重
        String fingerprint = msg.getUserId() + ":" + msg.getScene() + ":" + title + ":" + content;
        String dedupKey = "dedup:content:" + md5Hex(fingerprint);
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(isNew)) {
            log.info("内容去重拦截，userId={}，scene={}", msg.getUserId(), msg.getScene());
            return DispatchResult.failed("内容去重");
        }

        // 3. Resolve user contact info
        User user = userMapper.selectById(msg.getUserId());
        if (user == null) {
            log.warn("用户不存在，userId={}", msg.getUserId());
            return DispatchResult.failed("用户不存在: " + msg.getUserId());
        }

        // 4. Sort channels by cost: Log(1) < Push(2) < SMS(4)
        List<MessageChannel> sorted = channels.values().stream()
                .filter(MessageChannel::isAvailable)
                .filter(ch -> frequencyGuard.allow(msg.getUserId(), ch.getChannel()))
                .sorted(Comparator.comparingInt(MessageChannel::getChannel))
                .toList();

        if (sorted.isEmpty()) {
            log.warn("无可用渠道，userId={}，scene={}", msg.getUserId(), msg.getScene());
            return DispatchResult.failed("无可用渠道");
        }

        // 5. Try each channel, failover to next
        for (MessageChannel ch : sorted) {
            String target = resolveTarget(user, ch.getChannel());
            String channelContent = resolveChannelContent(tpl, ch.getChannel(), msg.getParams());
            try {
                boolean ok = ch.send(msg.getUserId(), target, title,
                        channelContent != null ? channelContent : content);
                String status = ok ? "SENT" : "FAILED";
                notifyLogService.record(msg, ch.getChannel(), status, title,
                        channelContent != null ? channelContent : content, ok ? null : "发送失败");
                if (ok) {
                    return DispatchResult.success(ch.getChannel());
                }
            } catch (Exception e) {
                log.warn("渠道发送异常，channel={}，userId={}，error={}",
                        ch.getChannel(), msg.getUserId(), e.getMessage());
                notifyLogService.record(msg, ch.getChannel(), "FAILED", title, content, e.getMessage());
            }
        }

        return DispatchResult.failed("所有渠道发送失败");
    }

    private String resolveTarget(User user, int channel) {
        return switch (channel) {
            case 2 -> user.getDeviceToken();
            case 4 -> user.getPhone();
            default -> null;
        };
    }

    private String resolveChannelContent(MessageTemplate tpl, int channel, Map<String, String> params) {
        return switch (channel) {
            case 4 -> templateService.render(tpl.getSmsContent(), params);
            default -> null;
        };
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public record DispatchResult(int channel, boolean success, String message) {
        public static DispatchResult success(int channel) {
            return new DispatchResult(channel, true, "success");
        }

        public static DispatchResult failed(String message) {
            return new DispatchResult(-1, false, message);
        }
    }
}
