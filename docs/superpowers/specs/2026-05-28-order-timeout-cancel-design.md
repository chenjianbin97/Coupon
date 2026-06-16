# 订单超时自动取消 — 设计文档

**日期**: 2026-05-28  
**方案**: RabbitMQ Delayed Message Exchange（延迟消息）

## 需求

订单创建后 15 分钟内未支付，系统自动取消订单，归还优惠券（LOCKED → UNUSED）。并发场景下支付优先：只要支付先到，取消就跳过。

## 架构

```
submit() → 创建订单 + 锁券
         → afterCommit → 发送延迟消息 (x-delay=900000ms)
                              ↓
                        15分钟后到达
                              ↓
OrderTimeoutListener → SETNX幂等 → 状态检查 → 原子取消 → 释放券
```

## 改动清单

| 文件 | 改动 | 类型 |
|------|------|------|
| `config/RabbitMQConfig.java` | 新增 CustomExchange(x-delayed-message)、Queue、Binding + 3个常量 | 修改 |
| `dto/OrderTimeoutMessage.java` | 新建消息 DTO（messageId, orderNo, userId, couponCode, sentAt） | 新增 |
| `listener/OrderTimeoutListener.java` | 新建消费监听器 | 新增 |
| `service/impl/OrderServiceImpl.java` | submit() 末尾新增 afterCommit 发送延迟消息 | 修改 |

## RabbitMQ 配置

- **交换机**: `order.timeout.cancel.exchange` — 类型 `x-delayed-message`，参数 `x-delayed-type=direct`，持久化
- **队列**: `order.timeout.cancel.queue` — 持久化
- **路由键**: `order.timeout.cancel`
- **延迟**: 消息级别 `x-delay` header，固定 900000ms（15分钟）

## 消息体

```java
public class OrderTimeoutMessage {
    String messageId;   // UUID，幂等去重
    String orderNo;     // 订单号
    Long userId;        // 用户ID
    String couponCode;  // 券码（用于释放）
    LocalDateTime sentAt; // 发送时间（排查用）
}
```

## 消费逻辑

1. SETNX `idempotent:msg:{messageId}` NX EX 3600 → 幂等过滤
2. SELECT order WHERE order_no = ? → 不存在或 status != 0 → 跳过
3. UPDATE order SET status=2 WHERE order_no=? AND status=0 → 原子取消
4. UPDATE user_coupon SET status=0 WHERE coupon_code=? AND status=2 → 释放券
5. DuplicateKeyException → 视为成功；其他异常 → 删幂等Key → throw → MQ重试

## 并发安全

- **支付 vs 超时取消**: 双方都用 `UPDATE WHERE status=PENDING_PAY`，谁先执行谁生效，后到者 affectedRows=0 后跳过
- **手动取消 vs 超时取消**: 同理，原子更新保证只生效一次
- **消息重复投递**: Redis SETNX 消息级幂等 + DB WHERE 条件双重防护
- **事务回滚**: afterCommit 后发消息，不存在 DB 回滚但消息已发的窗口

## 事务边界

submit() 使用 `TransactionSynchronization.afterCommit()` 发送延迟消息。DB 事务成功提交后才发消息。若 afterCommit 中发送失败 → 打印 ERROR 日志（订单存在但无自动取消保护，可接受的降级）。

## 异常处理

| 场景 | 行为 |
|------|------|
| 订单已支付 | 状态非 PENDING_PAY → 直接 return |
| 订单已被手动取消 | 同理 return |
| 券已被使用（极端并发） | UPDATE affectedRows=0 → 跳过（幂等） |
| DB 异常 | 删幂等Key → throw → MQ 自动重试 |
| 消息格式错误 | catch + log.error + return（不重试） |

## 测试要点

- 单测：订单超时自动取消，券正确释放
- 并发：支付与超时取消同时到达，支付优先
- 幂等：同一条消息重复投递，只取消一次
- 边界：已支付/已取消的订单收到超时消息，正常跳过
