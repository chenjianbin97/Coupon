# 多渠道智能消息分发平台 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将通知系统从同步 LogNotifier 升级为独立 MQ 驱动的多渠道消息分发模块，支持渠道可插拔、频控、模板渲染、发送追踪。

**Architecture:** 业务侧发 NotifyMessage 到 RabbitMQ → NotifyDispatchListener 消费 → MessageDispatchEngine 执行渠道路由（查偏好 → 频控 → 逐个尝试） → MessageChannel 发送 → 记录到 t_notify_log。

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis-Plus 3.5.7, RabbitMQ, Redis (Lua), MySQL, Lombok, Apache Commons Text (StrSubstitutor)

---

### Task 1: Add Redis constants for frequency control and channel health

**Files:**
- Modify: `src/main/java/com/example/coupon/common/constant/RedisConstant.java`

- [ ] **Step 1: Add new Redis key patterns**

```java
// Add to RedisConstant class, after existing constants:

public static final String FREQUENCY_KEY = "freq:%d:userId:%d";

public static final String CHANNEL_HEALTH_KEY = "channel:health:%d";
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/common/constant/RedisConstant.java
git commit -m "feat: add Redis key patterns for frequency control and channel health

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Create NotifyMessage DTO

**Files:**
- Create: `src/main/java/com/example/coupon/dto/NotifyMessage.java`

- [ ] **Step 1: Create NotifyMessage class**

```java
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/dto/NotifyMessage.java
git commit -m "feat: add NotifyMessage DTO for MQ notification dispatch

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Create MessageTemplate entity

**Files:**
- Create: `src/main/java/com/example/coupon/entity/MessageTemplate.java`

- [ ] **Step 1: Create MessageTemplate entity**

```java
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/entity/MessageTemplate.java
git commit -m "feat: add MessageTemplate entity

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Create MessageTemplateMapper

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/MessageTemplateMapper.java`

- [ ] **Step 1: Create mapper interface**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.MessageTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageTemplateMapper extends BaseMapper<MessageTemplate> {
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/mapper/MessageTemplateMapper.java
git commit -m "feat: add MessageTemplateMapper

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Create NotifyLog entity

**Files:**
- Create: `src/main/java/com/example/coupon/entity/NotifyLog.java`

- [ ] **Step 1: Create NotifyLog entity**

```java
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/entity/NotifyLog.java
git commit -m "feat: add NotifyLog entity

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Create NotifyLogMapper

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/NotifyLogMapper.java`

- [ ] **Step 1: Create mapper interface**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.NotifyLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotifyLogMapper extends BaseMapper<NotifyLog> {
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/mapper/NotifyLogMapper.java
git commit -m "feat: add NotifyLogMapper

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: SQL migration script — new tables + User table extension

**Files:**
- Create: `src/main/resources/db/migration/V2__notify_dispatch.sql`

Note: If Flyway is not in use, create as `src/main/resources/schema-notify.sql` for manual execution. Check existing migration approach first.

- [ ] **Step 1: Check if Flyway is configured**

Run: `cd /d D:\personal\java_project\coupon && ls src/main/resources/db/ 2>/dev/null || echo "no db dir"`

- [ ] **Step 2: Create SQL migration file**

Based on result of step 1, create the appropriate file. Content:

```sql
-- Message template table
CREATE TABLE IF NOT EXISTS t_message_template (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(128),
    push_title      VARCHAR(256),
    push_body       VARCHAR(1024),
    sms_content     VARCHAR(512),
    variables       VARCHAR(512),
    status          INT DEFAULT 1,
    create_time     DATETIME,
    update_time     DATETIME,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Notify log table
CREATE TABLE IF NOT EXISTS t_notify_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id        VARCHAR(64) NOT NULL,
    user_id         BIGINT,
    scene           VARCHAR(64),
    channel         INT,
    status          VARCHAR(32) DEFAULT 'SENT',
    title           VARCHAR(256),
    content         VARCHAR(2048),
    fail_reason     VARCHAR(512),
    send_time       DATETIME,
    deliver_time    DATETIME,
    create_time     DATETIME,
    INDEX idx_user_id (user_id),
    INDEX idx_trace_id (trace_id),
    INDEX idx_send_time (send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Add device_token to t_user
ALTER TABLE t_user ADD COLUMN device_token VARCHAR(256) NULL;
```

- [ ] **Step 3: Execute migration**

Run: `docker exec -i coupon-mysql mysql -uroot -proot coupon < src/main/resources/db/migration/V2__notify_dispatch.sql`
(Adjust container name and credentials per your Docker setup)

- [ ] **Step 4: Verify tables exist**

Run: `docker exec -i coupon-mysql mysql -uroot -proot coupon -e "SHOW TABLES LIKE 't_notify%'; SHOW COLUMNS FROM t_user LIKE 'device_token';"`
Expected: Both tables listed, device_token column shown

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V2__notify_dispatch.sql
git commit -m "feat: add notify_dispatch DB schema (t_message_template, t_notify_log, t_user.device_token)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Add device_token to User entity

**Files:**
- Modify: `src/main/java/com/example/coupon/entity/User.java`

- [ ] **Step 1: Add deviceToken field**

Add the field to User.java, after the `email` field (line 24):

```java
private String deviceToken;
```

Place it in the file after the `email` field declaration at line 24.

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/entity/User.java
git commit -m "feat: add deviceToken field to User entity

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Create MessageChannel interface

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/MessageChannel.java`

- [ ] **Step 1: Create MessageChannel interface**

```java
package com.example.coupon.service.notify;

public interface MessageChannel {

    int getChannel();

    boolean send(Long userId, String target, String title, String content);

    boolean isAvailable();
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/MessageChannel.java
git commit -m "feat: add MessageChannel interface for pluggable notification channels

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: Create LogChannel

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/impl/LogChannel.java`

- [ ] **Step 1: Create LogChannel**

Create directory `src/main/java/com/example/coupon/service/notify/impl/` first if it doesn't exist.

```java
package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogChannel implements MessageChannel {

    public static final int CHANNEL_LOG = 1;

    @Override
    public int getChannel() {
        return CHANNEL_LOG;
    }

    @Override
    public boolean send(Long userId, String target, String title, String content) {
        log.info("[通知-站内] userId={}, title={}, content={}", userId, title, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/impl/LogChannel.java
git commit -m "feat: add LogChannel - always-available logging channel

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: Create PushChannel (stub)

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/impl/PushChannel.java`

- [ ] **Step 1: Create PushChannel stub**

```java
package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushChannel implements MessageChannel {

    public static final int CHANNEL_PUSH = 2;

    @Override
    public int getChannel() {
        return CHANNEL_PUSH;
    }

    @Override
    public boolean send(Long userId, String deviceToken, String title, String content) {
        // TODO: 对接个推/极光推送 SDK
        // PushResult result = getuiClient.pushToSingle(deviceToken, title, content);
        log.info("[Push] userId={}, deviceToken={}, title={}, content={}", userId, deviceToken, title, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/impl/PushChannel.java
git commit -m "feat: add PushChannel stub (isAvailable=false until SDK integration)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: Create SMSChannel (stub)

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/impl/SMSChannel.java`

- [ ] **Step 1: Create SMSChannel stub**

```java
package com.example.coupon.service.notify.impl;

import com.example.coupon.service.notify.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SMSChannel implements MessageChannel {

    public static final int CHANNEL_SMS = 4;

    @Override
    public int getChannel() {
        return CHANNEL_SMS;
    }

    @Override
    public boolean send(Long userId, String phone, String title, String content) {
        // TODO: 对接阿里云/腾讯云短信 SDK
        // SendSmsResponse response = smsClient.send(phone, content);
        log.info("[SMS] userId={}, phone={}, content={}", userId, phone, content);
        return true;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/impl/SMSChannel.java
git commit -m "feat: add SMSChannel stub (isAvailable=false until SMS SDK integration)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 13: Mark CouponNotifier @Deprecated

**Files:**
- Modify: `src/main/java/com/example/coupon/service/notify/CouponNotifier.java`

- [ ] **Step 1: Add @Deprecated annotation**

```java
package com.example.coupon.service.notify;

@Deprecated
public interface CouponNotifier {

    @Deprecated
    int getChannel();

    @Deprecated
    void notify(Long userId, Long templateId, String message);
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS (will have deprecation warnings, which is fine)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/CouponNotifier.java
git commit -m "feat: mark CouponNotifier @Deprecated, superseded by MessageChannel

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 14: Create FrequencyGuard — Redis Lua sliding window

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/FrequencyGuard.java`

- [ ] **Step 1: Create FrequencyGuard with Lua script**

```java
package com.example.coupon.service.notify;

import com.example.coupon.common.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FrequencyGuard {

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> LUA_SCRIPT;

    static {
        LUA_SCRIPT = new DefaultRedisScript<>();
        LUA_SCRIPT.setResultType(Long.class);
        LUA_SCRIPT.setScript("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            local count = redis.call('ZCARD', KEYS[1])
            if count < tonumber(ARGV[3]) then
                redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
                return 1
            else
                return 0
            end
            """);
    }

    public boolean allow(Long userId, int channel, long windowMs, int maxCount) {
        String key = String.format(RedisConstant.FREQUENCY_KEY, channel, userId);
        long now = System.currentTimeMillis();
        String messageId = now + ":" + Thread.currentThread().getId();

        Long result = redisTemplate.execute(
                LUA_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(maxCount),
                messageId);

        boolean allowed = Long.valueOf(1).equals(result);
        if (!allowed) {
            log.debug("频控拦截，userId={}，channel={}，windowMs={}，maxCount={}", userId, channel, windowMs, maxCount);
        }
        return allowed;
    }

    public boolean allow(Long userId, int channel) {
        return allow(userId, channel, 86400000L, 5);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/FrequencyGuard.java
git commit -m "feat: add FrequencyGuard with Redis Lua sliding window

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 15: Create MessageTemplateService

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/MessageTemplateService.java`

- [ ] **Step 1: Add Apache Commons Text dependency if not present**

Check: `cd /d D:\personal\java_project\coupon && grep "commons-text" pom.xml`
If not found, add to pom.xml dependencies:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-text</artifactId>
    <version>1.11.0</version>
</dependency>
```

- [ ] **Step 2: Create MessageTemplateService**

```java
package com.example.coupon.service.notify;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.mapper.MessageTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class MessageTemplateService extends ServiceImpl<MessageTemplateMapper, MessageTemplate> {

    public MessageTemplate getByCode(String code) {
        return getOne(new QueryWrapper<MessageTemplate>()
                .eq("code", code)
                .eq("status", 1));
    }

    public String render(String template, Map<String, String> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        return StringSubstitutor.replace(template, params, "${", "}");
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/MessageTemplateService.java
git add pom.xml
git commit -m "feat: add MessageTemplateService with template rendering via StrSubstitutor

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 16: Create NotifyLogService

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/NotifyLogService.java`

- [ ] **Step 1: Create NotifyLogService**

```java
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/NotifyLogService.java
git commit -m "feat: add NotifyLogService for send tracking

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 17: Create MessageDispatchEngine

**Files:**
- Create: `src/main/java/com/example/coupon/service/notify/MessageDispatchEngine.java`

- [ ] **Step 1: Create DispatchResult record**

Add inside `MessageDispatchEngine.java`:

```java
package com.example.coupon.service.notify;

import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.entity.User;
import com.example.coupon.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public DispatchResult dispatch(NotifyMessage msg) {
        // 1. Render template
        MessageTemplate tpl = templateService.getByCode(msg.getTemplateCode());
        if (tpl == null) {
            log.warn("消息模板不存在，code={}", msg.getTemplateCode());
            return DispatchResult.failed("模板不存在: " + msg.getTemplateCode());
        }

        String title = templateService.render(tpl.getPushTitle(), msg.getParams());
        String content = templateService.render(tpl.getPushBody(), msg.getParams());

        // 2. Resolve user contact info
        User user = userMapper.selectById(msg.getUserId());
        if (user == null) {
            log.warn("用户不存在，userId={}", msg.getUserId());
            return DispatchResult.failed("用户不存在: " + msg.getUserId());
        }

        // 3. Sort channels by cost: Log(1) < Push(2) < SMS(4)
        List<MessageChannel> sorted = channels.values().stream()
                .filter(MessageChannel::isAvailable)
                .filter(ch -> frequencyGuard.allow(msg.getUserId(), ch.getChannel()))
                .sorted(Comparator.comparingInt(MessageChannel::getChannel))
                .toList();

        if (sorted.isEmpty()) {
            log.warn("无可用渠道，userId={}，scene={}", msg.getUserId(), msg.getScene());
            return DispatchResult.failed("无可用渠道");
        }

        // 4. Try each channel, failover to next
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
            case 2 -> user.getDeviceToken();   // Push
            case 4 -> user.getPhone();         // SMS
            default -> null;
        };
    }

    private String resolveChannelContent(MessageTemplate tpl, int channel, Map<String, String> params) {
        return switch (channel) {
            case 4 -> templateService.render(tpl.getSmsContent(), params); // SMS uses smsContent
            default -> null; // Use default push content
        };
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/service/notify/MessageDispatchEngine.java
git commit -m "feat: add MessageDispatchEngine with channel routing and failover

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 18: Add notify.dispatch queue to RabbitMQConfig

**Files:**
- Modify: `src/main/java/com/example/coupon/config/RabbitMQConfig.java`
- Modify: `src/main/java/com/example/coupon/common/constant/RedisConstant.java`

- [ ] **Step 1: Add RabbitMQ queue constants and beans**

In `RabbitMQConfig.java`, add after the existing `ORDER_TIMEOUT_ROUTING_KEY` line:

```java
public static final String NOTIFY_DISPATCH_QUEUE = "notify.dispatch.queue";
public static final String NOTIFY_DISPATCH_EXCHANGE = "notify.dispatch.exchange";
public static final String NOTIFY_DISPATCH_KEY = "notify.dispatch";
```

Add bean definitions after the existing order timeout binding method:

```java
@Bean
public Queue notifyDispatchQueue() {
    return new Queue(NOTIFY_DISPATCH_QUEUE, true);
}

@Bean
public DirectExchange notifyDispatchExchange() {
    return new DirectExchange(NOTIFY_DISPATCH_EXCHANGE, true, false);
}

@Bean
public Binding notifyDispatchBinding() {
    return BindingBuilder.bind(notifyDispatchQueue())
            .to(notifyDispatchExchange())
            .with(NOTIFY_DISPATCH_KEY);
}
```

- [ ] **Step 2: Add idempotent key constant**

In `RedisConstant.java`, add:

```java
public static final String IDEMPOTENT_NOTIFY_KEY = "idempotent:notify:%s";
```

- [ ] **Step 3: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coupon/config/RabbitMQConfig.java
git add src/main/java/com/example/coupon/common/constant/RedisConstant.java
git commit -m "feat: add notify.dispatch queue/exchange/binding to RabbitMQ config

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 19: Create NotifyDispatchListener

**Files:**
- Create: `src/main/java/com/example/coupon/listener/NotifyDispatchListener.java`

- [ ] **Step 1: Create NotifyDispatchListener**

```java
package com.example.coupon.listener;

import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.service.notify.MessageDispatchEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDispatchListener {

    private final MessageDispatchEngine dispatchEngine;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.NOTIFY_DISPATCH_QUEUE)
    public void onNotifyMessage(NotifyMessage msg) {
        log.info("收到通知分发消息，messageId={}，userId={}，scene={}，priority={}",
                msg.getMessageId(), msg.getUserId(), msg.getScene(), msg.getPriority());

        String msgKey = String.format(RedisConstant.IDEMPOTENT_NOTIFY_KEY, msg.getMessageId());
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(msgKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("消息已处理，跳过，messageId={}", msg.getMessageId());
            return;
        }

        try {
            MessageDispatchEngine.DispatchResult result = dispatchEngine.dispatch(msg);
            log.info("通知分发完成，messageId={}，success={}，channel={}，message={}",
                    msg.getMessageId(), result.success(), result.channel(), result.message());
        } catch (DuplicateKeyException e) {
            log.debug("幂等写入冲突，视为成功，messageId={}", msg.getMessageId());
        } catch (Exception e) {
            log.error("通知分发处理异常，messageId={}，error={}", msg.getMessageId(), e.getMessage());
            redisTemplate.delete(msgKey);
            throw e;
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coupon/listener/NotifyDispatchListener.java
git commit -m "feat: add NotifyDispatchListener - MQ consumer for notification dispatch

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 20: Update SubscriptionNotifyDTO and SQL to include template name

**Files:**
- Modify: `src/main/java/com/example/coupon/dto/SubscriptionNotifyDTO.java`
- Modify: `src/main/java/com/example/coupon/mapper/CouponSubscriptionMapper.java`

- [ ] **Step 1: Add templateName field to SubscriptionNotifyDTO**

```java
package com.example.coupon.dto;

import com.example.coupon.entity.CouponSubscription;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SubscriptionNotifyDTO extends CouponSubscription {

    private LocalDateTime validStartTime;

    private String templateName;
}
```

- [ ] **Step 2: Update selectPendingWithStartTime SQL**

Modify the `@Select` annotation in `CouponSubscriptionMapper.java`:

```java
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
```

- [ ] **Step 3: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coupon/dto/SubscriptionNotifyDTO.java
git add src/main/java/com/example/coupon/mapper/CouponSubscriptionMapper.java
git commit -m "feat: add templateName to SubscriptionNotifyDTO for notification rendering

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 21: Refactor checkAndNotify() to send NotifyMessage via MQ

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/CouponSubscriptionServiceImpl.java`

- [ ] **Step 1: Replace notifier injection with RabbitTemplate**

Remove these fields (lines 37-40):
```java
@Autowired
private List<CouponNotifier> notifiers;
private Map<Integer, CouponNotifier> notifierMap;
```

Add in their place:
```java
@Autowired
private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
```

Remove this import:
```java
import com.example.coupon.service.notify.CouponNotifier;
```

Add these imports:
```java
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import java.util.UUID;
import java.util.Map;
```

- [ ] **Step 2: Replace checkAndNotify() implementation**

Replace the entire method (`@Scheduled` through closing brace of the method, lines 84-125):

```java
@Scheduled(cron = "0 * * * * ?")
public void checkAndNotify() {
    List<SubscriptionNotifyDTO> subs = baseMapper.selectPendingWithStartTime();
    if (subs.isEmpty()) return;

    LocalDateTime now = LocalDateTime.now();
    for (SubscriptionNotifyDTO sub : subs) {
        int pending = sub.getNotifyOffsets() & ~sub.getLastNotifiedBits();
        if (pending == 0) continue;

        int notified = 0;
        for (int bit = 0; bit < OFFSET_BITS; bit++) {
            if ((pending & (1 << bit)) == 0) continue;
            LocalDateTime notifyAt = sub.getValidStartTime().minusMinutes(OFFSET_MINUTES[bit]);
            if (now.isBefore(notifyAt)) continue;

            String messageId = UUID.randomUUID().toString();
            NotifyMessage nm = new NotifyMessage();
            nm.setMessageId(messageId);
            nm.setUserId(sub.getUserId());
            nm.setScene("COUPON_ONLINE");
            nm.setTemplateCode("COUPON_ONLINE");
            nm.setParams(Map.of("template_name",
                    sub.getTemplateName() != null ? sub.getTemplateName() : "未知模板"));
            nm.setPriority(1);
            nm.setSendTime(LocalDateTime.now());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                    RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                    nm);

            log.info("通知消息已发送到MQ，messageId={}，userId={}，templateId={}",
                    messageId, sub.getUserId(), sub.getTemplateId());
            notified |= (1 << bit);
        }

        if (notified > 0) {
            int newBits = sub.getLastNotifiedBits() | notified;
            lambdaUpdate()
                    .eq(CouponSubscription::getId, sub.getId())
                    .set(CouponSubscription::getLastNotifiedBits, newBits)
                    .set(newBits == sub.getNotifyOffsets(),
                            CouponSubscription::getStatus, SubscriptionStatusEnum.ALL_NOTIFIED.getStatus())
                    .update();
        }
    }
}
```

- [ ] **Step 3: Remove unused import for CouponNotifier and related util imports**

Remove:
```java
import com.example.coupon.service.notify.CouponNotifier;
```
And remove the unused `buildNotifierMap()` method (if present, this was `notifierMap` init method — it's now dead code since `notifierMap` was deleted).

- [ ] **Step 4: Verify compilation**

Run: `cd /d D:\personal\java_project\coupon && mvn compile -q`
Expected: BUILD SUCCESS (may have deprecation warnings from CouponNotifier)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/coupon/service/impl/CouponSubscriptionServiceImpl.java
git commit -m "feat: refactor checkAndNotify() to send NotifyMessage via MQ instead of synchronous notifier calls

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 22: Seed message template data

**Files:**
- Create: `src/main/resources/db/data-notify-templates.sql`

- [ ] **Step 1: Create seed data SQL**

```sql
-- Message template seed data
INSERT IGNORE INTO t_message_template (code, name, push_title, push_body, sms_content, variables, status, create_time, update_time)
VALUES
('COUPON_ONLINE',    '订阅券上线通知', '${template_name} 即将开抢',   '您订阅的${template_name}即将开始领取，快去看看吧', '【优惠券】您订阅的${template_name}即将开抢，限时领取！', '["template_name"]', 1, NOW(), NOW()),
('COUPON_EXPIRING',  '券即将过期提醒', '您的${template_name}即将过期', '您的${template_name}将在${expire_time}过期，记得使用', '【优惠券】您的${template_name}即将过期，快去使用！', '["template_name","expire_time"]', 1, NOW(), NOW()),
('COUPON_RECEIVED',  '券到账通知',     '${template_name}已到账',     '${template_name}已到账，有效期至${expire_time}', '【优惠券】${template_name}已到账，快去使用！', '["template_name","expire_time"]', 1, NOW(), NOW()),
('ORDER_PAID',       '支付成功通知',   '订单${order_no}支付成功',   '您的订单${order_no}已支付成功，金额${amount}元', '【订单】订单${order_no}支付成功，金额${amount}元', '["order_no","amount"]', 1, NOW(), NOW()),
('ORDER_CANCELLED',  '订单取消通知',   '订单${order_no}已取消',     '您的订单${order_no}已取消，如有优惠券会自动退回', '【订单】订单${order_no}已取消，优惠券已退回', '["order_no"]', 1, NOW(), NOW()),
('TASK_COMPLETE',    '分发任务完成',   '${task_name}发放完成',      '${task_name}已完成，共发放${success_count}张优惠券', NULL, '["task_name","success_count"]', 1, NOW(), NOW());
```

- [ ] **Step 2: Execute seed data**

Run: `docker exec -i coupon-mysql mysql -uroot -proot coupon < src/main/resources/db/data-notify-templates.sql`
Expected: Query OK, 6 rows affected

- [ ] **Step 3: Verify data**

Run: `docker exec -i coupon-mysql mysql -uroot -proot coupon -e "SELECT code, name FROM t_message_template;"`
Expected: 6 rows listed

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/data-notify-templates.sql
git commit -m "feat: add seed data for 6 notification message templates

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 23: Write integration test — NotifyDispatchListener full pipeline

**Files:**
- Create: `src/test/java/com/example/coupon/NotifyDispatchTest.java`

- [ ] **Step 1: Create integration test**

```java
package com.example.coupon;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.entity.NotifyLog;
import com.example.coupon.mapper.MessageTemplateMapper;
import com.example.coupon.mapper.NotifyLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class NotifyDispatchTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotifyLogMapper notifyLogMapper;

    @Autowired
    private MessageTemplateMapper messageTemplateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 99999L;
    private String messageId;

    @BeforeEach
    void setUp() {
        messageId = UUID.randomUUID().toString();

        // Ensure template exists
        MessageTemplate existing = messageTemplateMapper.selectOne(
                new QueryWrapper<MessageTemplate>().eq("code", "COUPON_ONLINE"));
        if (existing == null) {
            MessageTemplate tpl = new MessageTemplate();
            tpl.setCode("COUPON_ONLINE");
            tpl.setName("测试模板");
            tpl.setPushTitle("${template_name} 即将开抢");
            tpl.setPushBody("您订阅的${template_name}即将开始");
            tpl.setSmsContent("【优惠券】${template_name}即将开抢");
            tpl.setVariables("[\"template_name\"]");
            tpl.setStatus(1);
            tpl.setCreateTime(LocalDateTime.now());
            tpl.setUpdateTime(LocalDateTime.now());
            messageTemplateMapper.insert(tpl);
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_notify_log WHERE trace_id = ?", messageId);
    }

    @Test
    void testFullDispatchPipeline() throws Exception {
        NotifyMessage msg = new NotifyMessage();
        msg.setMessageId(messageId);
        msg.setUserId(USER_ID);
        msg.setScene("COUPON_ONLINE");
        msg.setTemplateCode("COUPON_ONLINE");
        msg.setParams(Map.of("template_name", "满100减20"));
        msg.setPriority(1);
        msg.setSendTime(LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                msg);

        Thread.sleep(2000);

        List<NotifyLog> logs = notifyLogMapper.selectList(
                new QueryWrapper<NotifyLog>().eq("trace_id", messageId));
        assertFalse(logs.isEmpty(), "应有至少一条发送记录");
        assertEquals("SENT", logs.get(0).getStatus());
        assertEquals(1, logs.get(0).getChannel(), "默认应走LogChannel");
    }

    @Test
    void testIdempotentMessage() throws Exception {
        NotifyMessage msg = new NotifyMessage();
        msg.setMessageId(messageId);
        msg.setUserId(USER_ID);
        msg.setScene("COUPON_ONLINE");
        msg.setTemplateCode("COUPON_ONLINE");
        msg.setParams(Map.of("template_name", "满100减20"));
        msg.setPriority(1);
        msg.setSendTime(LocalDateTime.now());

        for (int i = 0; i < 2; i++) {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                    RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                    msg);
        }

        Thread.sleep(2000);

        List<NotifyLog> logs = notifyLogMapper.selectList(
                new QueryWrapper<NotifyLog>().eq("trace_id", messageId));
        assertEquals(1, logs.size(), "幂等保证：同一messageId只处理一次");
    }
}
```

- [ ] **Step 2: Run the test**

Run: `cd /d D:\personal\java_project\coupon && mvn test -Dtest=NotifyDispatchTest -q`
Expected: Tests pass (2/2)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/coupon/NotifyDispatchTest.java
git commit -m "test: add NotifyDispatchTest for full pipeline and idempotency

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 24: Run all existing tests to verify no regressions

- [ ] **Step 1: Run full test suite**

Run: `cd /d D:\personal\java_project\coupon && mvn test -q`
Expected: All tests pass, no regressions

- [ ] **Step 2: If any test fails, investigate and fix before proceeding**

---

## Plan Self-Review Summary

**Spec coverage check:**
- Section 3.1 MessageChannel interface → Task 9
- Section 3.2 NotifyMessage → Task 2
- Section 3.3 MessageTemplate → Tasks 3, 4, 15
- Section 3.4 NotifyLog → Tasks 5, 6, 16
- Section 4.1 FrequencyGuard → Task 14
- Section 4.2 DispatchEngine routing → Task 17
- Section 4.3 User table → Tasks 7, 8
- Section 4.5 checkAndNotify refactor → Tasks 20, 21
- Section 4.6 RabbitMQ queue → Task 18
- Section 5 Template seed data → Task 22
- Section 6.1 LogChannel → Task 10
- Section 6.2 PushChannel → Task 11
- Section 6.3 SMSChannel → Task 12
- Section 7 Compatibility → Task 13 (deprecate CouponNotifier)
- Section 8 Testing → Task 23

**Placeholder scan:** No TBD/TODO in code. PushChannel and SMSChannel stubs have intentional TODO comments marking SDK integration points — these are not plan gaps, they're intentional stubs per spec Section 1.3.

**Type consistency verified:**
- `MessageChannel.getChannel()` returns `int` throughout
- `NotifyMessage.messageId` is `String` throughout
- Channel constants: Log=1, Push=2, SMS=4 consistent across all tasks
- `FrequencyGuard.allow()` signature consistent between definition (Task 14) and usage (Task 17)
