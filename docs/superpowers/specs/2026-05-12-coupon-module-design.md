# Coupon 模块 Implementation Plan

## 背景

为优惠券系统添加完整的分层架构，包括优惠券模板管理和用户优惠券业务操作。

## 设计原则

- 与现有 User 模块风格保持一致
- 使用 MyBatis-Plus 通用 CRUD
- Token 拦截器继续生效

## 表结构

### t_user_coupon

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint(20) | 主键，自增 |
| user_id | bigint(20) | 用户ID |
| template_id | bigint(20) | 优惠券模板ID |
| coupon_code | varchar(64) | 唯一券码 |
| status | tinyint(1) | 0：未使用 1：已使用 2：已过期 |
| receive_time | datetime | 领取时间 |
| use_time | datetime | 使用时间 |
| order_id | bigint(20) | 使用时关联的订单ID |
| create_time | datetime | 创建时间 |
| update_time | datetime | 修改时间 |
| del_flag | tinyint(1) | 删除标识 |

## 接口设计

### CouponTemplateController

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /coupon-template/save | 创建模板 |
| GET | /coupon-template/{id} | 查询模板 |
| GET | /coupon-template/list | 分页列表 |
| PUT | /coupon-template/update | 更新模板 |
| DELETE | /coupon-template/{id} | 删除模板 |
| POST | /coupon-template/publish | 发放/增加库存 |

### UserCouponController

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /coupon/receive | 领取优惠券 |
| POST | /coupon/use | 核销优惠券 |
| GET | /coupon/my-list | 查询我的优惠券 |

## DTO 设计

- CouponTemplateDTO：模板基础字段
- CouponTemplatePageDTO：分页查询条件
- UserCouponDTO：用户优惠券信息
- ReceiveCouponDTO：领取请求
- UseCouponDTO：核销请求

## 业务流程

1. 领取：检查库存 → 检查限领 → 扣减库存 → 生成用户券
2. 核销：验证券状态 → 验证有效期 → 更新为已使用
