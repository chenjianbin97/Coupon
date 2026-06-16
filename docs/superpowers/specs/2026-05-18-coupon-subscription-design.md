# 优惠券预约通知功能设计

## 目标

用户可以在优惠券生效前预约多个时间点的通知提醒，到期后通过指定渠道触达。

## 数据模型

```sql
CREATE TABLE t_coupon_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    notify_offsets TINYINT NOT NULL DEFAULT 0 COMMENT '时间偏移位掩码',
    last_notified_bits TINYINT NOT NULL DEFAULT 0 COMMENT '已通知位掩码',
    notify_method TINYINT NOT NULL DEFAULT 1 COMMENT '渠道位掩码',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-有效 1-全部通知完 2-已取消',
    create_time DATETIME NOT NULL,
    del_flag TINYINT DEFAULT 0,
    UNIQUE KEY uk_user_template (user_id, template_id),
    INDEX idx_status (status)
);
```

**时间偏移位掩码（notify_offsets）：**

| 位 | 偏移 | 含义 |
|----|------|------|
| bit 0 | 5 分钟前 | validStartTime - 5min |
| bit 1 | 10 分钟前 | |
| bit 2 | 15 分钟前 | |
| bit 3 | 30 分钟前 | |
| bit 4 | 60 分钟前 | |

示例：用户选 5 分钟前 + 30 分钟前 = bit0 \| bit3 = 1 \| 8 = 9

**渠道位掩码（notify_method）：**

| 位 | 值 | 渠道 |
|----|-----|------|
| bit 0 | 1 | 站内信 |
| bit 1 | 2 | 短信 |
| bit 2 | 4 | Push |
| bit 3 | 8 | 邮件 |
| bit 4 | 16 | 微信 |

示例：站内信 + 短信 = 1 \| 2 = 3

## 通知接口

```java
public interface CouponNotifier {
    int getChannel();
    void notify(Long userId, Long templateId, LocalDateTime notifyAt);
}
```

简单实现：`LogNotifier`（日志输出）。后续扩展 `SmsNotifier`、`PushNotifier` 实现同一接口注入即可。

## 定时任务

每分钟轮询，SQL 只拉 1 小时窗口内将开始的模板订阅：

```sql
SELECT s.*, t.valid_start_time
FROM t_coupon_subscription s
JOIN t_coupon_template t ON t.id = s.template_id
WHERE s.status = 0
  AND s.notify_offsets != s.last_notified_bits
  AND t.valid_start_time <= DATE_ADD(NOW(), INTERVAL 60 MINUTE)
  AND t.valid_start_time >= NOW()
  AND t.status = 1
ORDER BY t.valid_start_time ASC
LIMIT 500;
```

Java 端逐位比对时间偏移，到期的批量发通知，更新 `last_notified_bits`。全部发完则 `status = 1`。

## API

```
POST   /coupon-template/{id}/subscribe     { offsets: [5, 30], method: 3 }
DELETE /coupon-template/{id}/subscribe
```

## 错误处理

通知实现（LogNotifier）不涉及 IO 失败。扩展实现（短信/Push）：
- 单用户失败不影响本批
- 汇总失败数记 WARN
- 不重试，下轮轮询补上（last_notified_bits 未更新）
