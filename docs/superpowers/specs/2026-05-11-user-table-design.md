# 用户信息表设计

## 背景

在 `one_coupon_rebuild` 数据库中新增用户信息表，为优惠券系统提供用户基础数据支撑。后续优惠券领取、发放等业务将关联此表。

## 设计原则

- **精简优先**：当前学习重点不在用户模块，字段保持最小可用集合
- **风格一致**：与现有 `t_coupon_template` 表保持相同的命名规范和审计字段
- **扩展预留**：用户名/手机号/邮箱均设置唯一索引，支持多方式登录

## 表结构

**表名：** `t_user`

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| `id` | bigint(20) | NO | auto_increment | 主键ID |
| `username` | varchar(64) | YES | NULL | 用户名，唯一 |
| `password` | varchar(256) | YES | NULL | 密码 |
| `phone` | varchar(20) | YES | NULL | 手机号，唯一 |
| `email` | varchar(64) | YES | NULL | 邮箱，唯一 |
| `nickname` | varchar(64) | YES | NULL | 昵称 |
| `avatar` | varchar(512) | YES | NULL | 头像URL |
| `status` | tinyint(1) | YES | NULL | 用户状态：0正常 1禁用 |
| `create_time` | datetime | YES | NULL | 创建时间 |
| `update_time` | datetime | YES | NULL | 修改时间 |
| `del_flag` | tinyint(1) | YES | NULL | 删除标识：0未删除 1已删除 |

## 索引设计

| 索引名 | 类型 | 字段 | 说明 |
|--------|------|------|------|
| PRIMARY | 主键 | `id` | 聚簇索引 |
| `uk_username` | 唯一索引 | `username` | 用户名登录 |
| `uk_phone` | 唯一索引 | `phone` | 手机号登录 |
| `uk_email` | 唯一索引 | `email` | 邮箱登录 |

## 与现有系统关联

- 后续优惠券领取记录表通过 `user_id`（bigint）外键逻辑关联此表
- 审计字段（`create_time`/`update_time`/`del_flag`）与 `t_coupon_template` 完全一致，便于统一拦截处理

## 后续扩展建议

如需增强用户模块，可逐步添加：
- `salt` 字段 + 密码加密策略（当前明文存储，仅用于学习）
- `last_login_time` / `last_login_ip` 登录轨迹审计
- `gender` / `birthday` 等扩展个人信息
