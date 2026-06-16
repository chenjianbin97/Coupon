# 多渠道智能消息分发平台 — 设计文档

## 1. 背景与目标

### 1.1 现状问题

当前通知系统只有骨架：

```
CouponNotifier (接口)
    └── LogNotifier (唯一实现，只打日志)
```

`CouponSubscriptionServiceImpl.checkAndNotify()` 中，定时扫描到该通知的订阅后，**同步遍历 notifier 直接调用**，没有 MQ 解耦，没有频控，没有渠道降级，没有发送追踪。

`CouponNotifier` 接口本身也有两个局限：
- 名字绑定"Coupon"，参数带 `templateId`，无法处理订单通知、退款通知等非券场景
- `notify(userId, templateId, message)` 只是纯文本，不支持模板变量

### 1.2 目标

把通知系统升级为**独立模块**：业务侧发 MQ 消息 → 分发引擎消费 → 按策略选渠道 → 发送 → 追踪。核心原则：

- **业务无感**：业务代码只需发一条 MQ 消息，不关心用户最终用什么渠道收到
- **渠道可插拔**：新增渠道只加一个 `MessageChannel` 实现类
- **与现有架构一致**：复用 RabbitMQ + Redis + MyBatis-Plus 技术栈，不引入新中间件

### 1.3 不做什么

- 不做实时消息推送（WebSocket/SSE），那是即时通讯的范畴
- 不做第三方渠道的完整对接（个推/阿里云短信仅做 stub，配上文档说明即可）
- 不改动现有订阅业务表 `t_coupon_subscription`

---

## 2. 架构设计

### 2.1 整体数据流

```
┌──────────────────────────────────────────────────────────────┐
│                        触发源                                 │
│                                                              │
│  定时扫描 (checkAndNotify)  业务事件 (券到账/支付/退款)        │
│         │                        │                           │
│         └────────┬───────────────┘                           │
│                  │ 发送 NotifyMessage 到 RabbitMQ             │
│                  ▼                                           │
│         notify.dispatch.queue                                │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│              NotifyDispatchListener（新增）                    │
│                                                              │
│  1. 幂等校验 (SETNX)                                         │
│  2. 调用 MessageDispatchEngine.dispatch()                    │
│  3. 失败删 key + rethrow → MQ 重试                           │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│            MessageDispatchEngine（新增）                      │
│                                                              │
│  输入: NotifyMessage                                         │
│                                                              │
│  步骤:                                                       │
│    1. 取消息模板 (t_message_template) → 渲染内容              │
│    2. 查用户偏好 (notifyMethod)                              │
│    3. 频控检查 (FrequencyGuard)                              │
│    4. 渠道路由: 按优先级排序 → 逐个尝试 → 成功则停             │
│                                                              │
│  输出: DispatchResult (成功/失败 + 使用的渠道)                 │
└──────────────────┬───────────────────────────────────────────┘
                   │
        ┌──────────┼──────────┬──────────┐
        ▼          ▼          ▼          ▼
   PushChannel  SMSChannel  LogChannel   ...（可扩展）
   (个推/极光)  (阿里云短信)  (已有,打日志)
        │          │          │
        ▼          ▼          ▼
   记录到 t_notify_log（traceId 追踪送达状态）
```

### 2.2 模块边界

新增代码全部放在 `service/notify/` 包下，不修改现有业务 Service（除 `checkAndNotify()` 一处调整）。

```
service/notify/
├── MessageChannel.java          # 新接口：纯渠道发送能力
├── MessageChannelImpl/
│   ├── LogChannel.java          # 站内日志（从 LogNotifier 迁移）
│   ├── PushChannel.java         # App Push（stub + 对接文档）
│   └── SMSChannel.java          # 短信（stub + 对接文档）
├── MessageDispatchEngine.java   # 分发引擎核心
├── FrequencyGuard.java          # 频控（Redis Lua 滑动窗口）
├── NotifyDispatchListener.java  # MQ 消费者
├── MessageTemplateService.java  # 消息模板管理
└── NotifyLogService.java        # 发送记录查询
```

---

## 3. 核心接口与数据模型

### 3.1 MessageChannel（替代 CouponNotifier）

```java
public interface MessageChannel {
    int getChannel();                                      // 渠道标识: 1=站内/Push 2=短信 3=邮件
    boolean send(Long userId, String target, String title, String content); // 发送
    boolean isAvailable();                                 // 渠道是否可达
}
```

`CouponNotifier` 保留不删，标记 `@Deprecated`。

### 3.2 NotifyMessage（MQ 消息体）

```java
public class NotifyMessage implements Serializable {
    private String messageId;          // UUID，幂等用
    private Long userId;               // 发给谁
    private String scene;              // 场景: COUPON_EXPIRING / COUPON_ONLINE / COUPON_RECEIVED / ORDER_PAID / ORDER_CANCELLED
    private String templateCode;       // 对应 t_message_template.code
    private Map<String, String> params;// 模板变量: {template_name, expire_time, ...}
    private int priority;              // 1=高(事务/提醒) 2=低(营销)
    private LocalDateTime sendTime;
}
```

场景标识 `scene` 不枚举 —— 用 String，业务侧自行定义，分发引擎不硬编码。

### 3.3 消息模板（t_message_template）

```sql
CREATE TABLE t_message_template (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64) NOT NULL,        -- 唯一标识: COUPON_EXPIRING
    name            VARCHAR(128),                -- 模板名称
    push_title      VARCHAR(256),                -- Push 标题模板
    push_body       VARCHAR(1024),               -- Push 正文模板
    sms_content     VARCHAR(512),                -- 短信模板
    variables       VARCHAR(512),                -- 变量列表 JSON: ["template_name","expire_time"]
    status          INT DEFAULT 1,               -- 1=启用 0=停用
    create_time     DATETIME,
    update_time     DATETIME,
    UNIQUE KEY uk_code (code)
);
```

模板变量用 `${variableName}` 占位。渲染时 `StrSubstitutor.replace(content, params)`。

### 3.4 发送日志（t_notify_log）

```sql
CREATE TABLE t_notify_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id        VARCHAR(64) NOT NULL,        -- 同 messageId，全链路追踪
    user_id         BIGINT,
    scene           VARCHAR(64),                 -- 场景
    channel         INT,                         -- 使用的渠道
    status          VARCHAR(32) DEFAULT 'SENT',  -- SENT / DELIVERED / CLICKED / FAILED
    title           VARCHAR(256),
    content         VARCHAR(2048),
    fail_reason     VARCHAR(512),                -- 失败原因
    send_time       DATETIME,
    deliver_time    DATETIME,
    create_time     DATETIME,
    INDEX idx_user_id (user_id),
    INDEX idx_trace_id (trace_id),
    INDEX idx_send_time (send_time)
);
```

---

## 4. 核心技术实现

### 4.1 频控：Redis Lua 滑动窗口

精确滑动窗口用 Sorted Set。每个用户每种渠道独立计数。

```lua
-- KEYS[1] = "freq:{channel}:userId:{userId}"
-- ARGV[1] = 当前时间戳 ms
-- ARGV[2] = 窗口大小 ms (如 86400000 = 24h)
-- ARGV[3] = 窗口内最大次数
-- ARGV[4] = 本次消息ID (用作 ZSet member, 保证唯一)

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
    return 1
else
    return 0
end
```

默认配置：同类渠道每用户每天上限 5 条，营销类渠道上限 2 条。窗口大小和上限可通过配置调整。

### 4.2 渠道路由与降级

```java
public DispatchResult dispatch(NotifyMessage msg) {
    // 1. 渲染模板
    MessageTemplate tpl = templateService.getByCode(msg.getTemplateCode());
    String title = render(tpl.getPushTitle(), msg.getParams());
    String content = render(tpl.getPushBody(), msg.getParams());

    // 2. 查用户偏好
    int preferChannels = preferenceService.getNotifyMethod(msg.getUserId());

    // 3. 收集可用渠道：偏好匹配 + 渠道可达 + 频控放行
    List<MessageChannel> candidates = channels.values().stream()
        .filter(ch -> (preferChannels & ch.getChannel()) != 0)
        .filter(MessageChannel::isAvailable)
        .filter(ch -> frequencyGuard.allow(msg.getUserId(), ch.getChannel()))
        .sorted(byCostAndPriority)
        .toList();

    // 4. 依次尝试，成功即停
    for (MessageChannel ch : candidates) {
        boolean ok = ch.send(msg.getUserId(), getTarget(msg.getUserId(), ch), title, content);
        notifyLogService.record(msg, ch.getChannel(), ok ? "SENT" : "FAILED");
        if (ok) return DispatchResult.success(ch.getChannel());
    }
    return DispatchResult.failed("所有渠道发送失败");
}
```

`getTarget()` 根据渠道类型获取用户在该渠道的地址：Push → deviceToken（从用户表取），短信 → phone（从用户表取）。

### 4.3 用户表补充

用户需要存储各渠道的目标地址。`t_user` 表需增加字段：

```sql
ALTER TABLE t_user ADD COLUMN device_token VARCHAR(256);   -- Push 用
ALTER TABLE t_user ADD COLUMN phone VARCHAR(20);            -- 短信用
ALTER TABLE t_user ADD COLUMN email VARCHAR(128);           -- 邮件用
```

已有的 `notifyMethod` 字段（在 `t_coupon_subscription` 表中）继续使用，保持位掩码语义。订阅级偏好覆盖用户全局偏好 — 先不做，MVP 阶段用订阅表 notifyMethod 即可

### 4.4 线程安全与并发

- **Dispatcher 无状态**：所有状态外挂到 Redis（频控窗口、渠道健康度）。Dispatcher 本身可以水平扩展，多实例并行消费无冲突。
- **频控窗口是近似准确**：`ZREMRANGEBYSCORE` + `ZADD` 中间有微小间隙，极端并发下可能超出窗口限制 1-2 条。这是可接受的 —— 频控的目标是防骚扰，不是金融级精确扣减。
- **渠道健康度**：每个渠道维护最近 100 次发送的 Redis Sorted Set，按时间戳存，按时间窗口统计成功率。`isAvailable()` 读取时实时计算。

### 4.5 改造现有 checkAndNotify()

当前逻辑（`CouponSubscriptionServiceImpl.java:85`）：定时扫描 → 直接遍历 notifier → 同步调用 `notifier.notify()`。

改为：定时扫描 → 构建 `NotifyMessage` → 发到 `notify.dispatch.queue` → 返回。

```java
// 改前:
notifier.notify(sub.getUserId(), sub.getTemplateId(), msg);

// 改后:
NotifyMessage nm = new NotifyMessage();
nm.setMessageId(UUID.randomUUID().toString());
nm.setUserId(sub.getUserId());
nm.setScene("COUPON_ONLINE");
nm.setTemplateCode("COUPON_ONLINE");
nm.setParams(Map.of("template_name", sub.getTemplateName()));
nm.setPriority(1);
nm.setSendTime(LocalDateTime.now());
rabbitTemplate.convertAndSend(NOTIFY_DISPATCH_EXCHANGE, NOTIFY_DISPATCH_KEY, nm);
```

同时删除 `CouponSubscriptionServiceImpl` 中对 `Map<Integer, CouponNotifier>` 的依赖。

### 4.6 RabbitMQ 新增队列

```java
public static final String NOTIFY_DISPATCH_QUEUE   = "notify.dispatch.queue";
public static final String NOTIFY_DISPATCH_EXCHANGE = "notify.dispatch.exchange";
public static final String NOTIFY_DISPATCH_KEY      = "notify.dispatch";
```

采用普通 Direct Exchange + 普通 Queue，不使用延迟队列。优先级通过消息体 `priority` 字段区分，高优先级的消息在业务侧早发，引擎侧按到达顺序处理。如需严格优先级，后续可拆为 `notify.high.queue` 和 `notify.low.queue` 两个队列。

---

## 5. 消息模板预置

系统启动时通过 `data.sql` 或 Flyway 迁移脚本预置以下模板：

| code | 场景 | Push 标题示例 |
|------|------|-------------|
| COUPON_ONLINE | 订阅的券上线提醒 | `${template_name} 即将开抢` |
| COUPON_EXPIRING | 券即将过期 | 您的 ${template_name} 即将过期 |
| COUPON_RECEIVED | 券到账通知 | ${template_name} 已到账 |
| ORDER_PAID | 支付成功 | 订单 ${order_no} 支付成功 |
| ORDER_CANCELLED | 订单取消 | 订单 ${order_no} 已取消 |
| TASK_COMPLETE | 批量发放完成 | ${task_name} 发放完成 |

---

## 6. 渠道实现说明

### 6.1 LogChannel（默认可用）

从 `LogNotifier` 改名迁移，始终可用，成本为 0，路由顺序排最后作为兜底。

### 6.2 PushChannel（Stub）

```java
@Component
public class PushChannel implements MessageChannel {
    @Override public int getChannel() { return 1; }

    @Override
    public boolean send(Long userId, String deviceToken, String title, String content) {
        // TODO: 对接个推/极光推送 SDK
        // GETui SDK: PushResult result = getuiClient.pushToSingle(deviceToken, title, content);
        log.info("[Push] userId={}, title={}, content={}", userId, title, content);
        return true;
    }

    @Override public boolean isAvailable() { return false; } // 默认不可用，对接后改为 true
}
```

默认不可用。对接第三方后修改 `isAvailable()` 返回 true。

### 6.3 SMSChannel（Stub）

同 PushChannel，默认不可用。对接阿里云/腾讯云短信后启用。

---

## 7. 与现有系统的兼容

| 现有代码 | 处理方式 |
|---------|---------|
| `CouponNotifier` 接口 | 保留，加 `@Deprecated` |
| `LogNotifier` | 保留，内部委托给 `LogChannel` |
| `CouponSubscriptionServiceImpl.notifierMap` | 删除该字段，改为注入 `RabbitTemplate` |
| `CouponSubscriptionServiceImpl.checkAndNotify()` | 改造：不遍历 notifier，改为发 MQ |
| 其他业务模块 | 不修改。后续需要通知时加一行 `rabbitTemplate.send()` 即可 |

---

## 8. 测试策略

- **单元测试**：`MessageDispatchEngine`（Mock 渠道）、`FrequencyGuard`（Testcontainers Redis）
- **集成测试**：`NotifyDispatchListener`（消费真实 MQ → 走完整链路 → 验证 `t_notify_log` 写入）
- **频控测试**：模拟同一用户短时间内触发 N 次通知，验证超过窗口上限后被拒绝

---

## 9. 文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/notify/MessageChannel.java` | 新增接口 | 替代 CouponNotifier |
| `service/notify/impl/LogChannel.java` | 新增 | 站内日志渠道 |
| `service/notify/impl/PushChannel.java` | 新增 | Push 渠道 stub |
| `service/notify/impl/SMSChannel.java` | 新增 | 短信渠道 stub |
| `service/notify/MessageDispatchEngine.java` | 新增 | 分发引擎 |
| `service/notify/FrequencyGuard.java` | 新增 | 频控 |
| `service/notify/NotifyDispatchListener.java` | 新增 | MQ 消费者 |
| `service/notify/MessageTemplateService.java` | 新增 | 模板管理 |
| `service/notify/NotifyLogService.java` | 新增 | 发送日志 |
| `dto/NotifyMessage.java` | 新增 | MQ 消息体 |
| `entity/MessageTemplate.java` | 新增 | 模板实体 |
| `entity/NotifyLog.java` | 新增 | 日志实体 |
| `mapper/MessageTemplateMapper.java` | 新增 | 模板 Mapper |
| `mapper/NotifyLogMapper.java` | 新增 | 日志 Mapper |
| `config/RabbitMQConfig.java` | 修改 | 新增 notify.dispatch 队列 |
| `entity/User.java` | 修改 | 新增 device_token / phone |
| `service/impl/CouponSubscriptionServiceImpl.java` | 修改 | checkAndNotify 改为发 MQ |
| `service/notify/CouponNotifier.java` | 修改 | 加 @Deprecated |
