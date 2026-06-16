# 提交订单功能设计

## 目标

用户提交订单时锁券，支付成功核销，取消/超时释放券。

## 优惠券状态流转

```
UNUSED(0) ──下单──→ LOCKED(2)
  ↑                   │
  │                   ├── 支付成功 ──→ USED(1)
  │                   │
  └── 取消/超时 ──────┘
```

## 数据模型

```sql
CREATE TABLE t_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) UNIQUE NOT NULL,
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    coupon_code VARCHAR(64),
    original_amount DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status TINYINT DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消',
    create_time DATETIME,
    update_time DATETIME,
    del_flag TINYINT DEFAULT 0,
    INDEX idx_user_id (user_id)
);

CREATE TABLE t_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    goods_id VARCHAR(100),
    goods_name VARCHAR(200) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    INDEX idx_order_id (order_id)
);
```

## 实付金额计算规则

| 券类型 | consume_rule | 计算 |
|--------|-------------|------|
| 1-满减 | `minAmount`, `discount` | `original >= minAmount → amount = original - discount` |
| 2-折扣 | `discountRate` | `amount = original × discountRate` |
| 3-直降 | `directAmount` | `amount = max(0, original - directAmount)` |

满减不满足最低消费 → 抛 `"未达到最低消费金额"`。

## API

```
POST /order/submit
  body: { shopId, couponCode, originalAmount, items: [{ goodsId, goodsName, quantity, price }] }
  → 锁券 + 创建订单

POST /order/{orderNo}/pay
  → 核销券（LOCKED→USED）

POST /order/{orderNo}/cancel
  → 释放券（LOCKED→UNUSED）
```

## 下单流程（一个事务）

1. 校验 `originalAmount == SUM(items.price × quantity)`
2. 查 `user_coupon`，校验券属于当前用户且状态为 `UNUSED`
3. 查 `user_coupon`，校验券未过期 `expire_time > NOW()`
4. 查 `t_coupon_template`，校验 `shop_number == shopId`（券属于该商家）
5. 按 template type + consume_rule 计算实付 `amount`
6. 原子锁券：`UPDATE user_coupon SET status=LOCKED WHERE coupon_code=? AND status=UNUSED AND expire_time > NOW()`，affectedRows=0 则抛异常
7. 生成 `order_no`
8. INSERT t_order + INSERT t_order_item

## `UserCouponStatusEnum` 新增

```java
UNUSED(0), USED(1), LOCKED(2);
```

## UserCoupon 表变更

- 新增 `expire_time DATETIME` — 领券时从模板 `valid_end_time` 填充
- 删除 `order_id` — 订单信息迁移到 `t_order`

```sql
ALTER TABLE t_user_coupon ADD expire_time DATETIME AFTER use_time;
ALTER TABLE t_user_coupon DROP COLUMN order_id;
```
