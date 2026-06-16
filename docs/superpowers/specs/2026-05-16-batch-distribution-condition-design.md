# 批量优惠券分发 — 条件圈选 + 用户级结果追踪设计

## 背景

当前批量分发接口要求前端传入 `List<Long> userIds`，存在两个根本问题：
1. **无法追踪单用户结果**：只记录 `successCount` / `failCount`，无法定位具体哪些用户失败及失败原因。
2. **无法支撑大规模用户**：百万级 userId 通过 HTTP 传输会导致请求体过大、内存溢出、超时。

本设计同时解决上述两个问题。

## 设计决策

### 1. 条件圈选替代显式列表

前端不再传入 `userIds`，而是传入查询条件。后端根据条件从 `t_user` 表分页查询用户，再异步处理。

**理由**：
- 请求体极小（仅几个筛选参数）
- 服务端分页查询，内存可控
- 条件可保存为"人群包"复用
- 主流电商平台均采用此模式

### 2. 用户级明细表追踪结果

新增 `t_coupon_distribution_detail` 表，在任务创建时预写入所有目标用户为"待处理"状态；Listener 处理完逐条更新结果。

**理由**：
- 随时可查询每个用户的处理状态
- 支持对失败用户精准重试
- 与任务创建同属一个事务，原子性保证

## 数据模型

### 新增表

```sql
CREATE TABLE t_coupon_distribution_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理 1-成功 2-失败',
    fail_reason VARCHAR(500),
    retry_count INT DEFAULT 0,
    create_time DATETIME,
    update_time DATETIME,
    del_flag TINYINT DEFAULT 0,
    INDEX idx_task_id (task_id),
    UNIQUE KEY uk_task_user (task_id, user_id)
);
```

### 查询条件 DTO

基于当前 `t_user` 可用字段定义查询条件（预留扩展字段）：

```java
@Data
public class UserQueryConditionDTO {
    private Long shopNumber;        // 按商家筛选
    private Integer status;         // 用户状态
    private LocalDateTime registerTimeStart;  // 注册时间起
    private LocalDateTime registerTimeEnd;    // 注册时间止
    private Boolean hasPhone;       // 是否绑定手机
    private Boolean hasEmail;       // 是否绑定邮箱
}
```

**当前限制**：`t_user` 暂无消费金额、会员等级等业务字段，因此首批查询条件以基础属性为主。后续可在 `UserQueryConditionDTO` 中新增字段而无需改表结构。

## 接口变更

### 分发接口（修改）

```java
POST /coupon-distribution/distribute
{
    "templateId": 1,
    "userQuery": {
        "shopNumber": 100,
        "status": 0,
        "registerTimeStart": "2025-01-01T00:00:00",
        "registerTimeEnd": "2025-12-31T23:59:59",
        "hasPhone": true
    }
}
```

**响应**：`{"code":200,"message":"成功","data":123}`（返回 taskId）

### 新增接口

```java
// 查询任务明细（支持按状态过滤、分页）
GET /coupon-distribution/task/{id}/details?status=2&page=1&size=20

// 重试任务中所有失败用户
POST /coupon-distribution/task/{id}/retry
```

## 数据流

```
前端提交条件
    ↓
Controller 接收 templateId + UserQueryCondition
    ↓
Service 校验模板（存在、已发布、商户隔离）
    ↓
Service 分页查询符合条件的用户总数
    ↓
Service 校验库存 >= 用户数
    ↓
Service 创建 task（status=1 处理中）
    ↓
Service 批量写入 detail（status=0 待处理）
    ↓
Service 逐条发送 MQ 消息
    ↓
Listener 消费消息 → 调用 receiveCoupon → 更新 detail 状态
    ↓
Listener 更新 task 聚合进度 → 全部完成后 task status=2
```

## 分页查询策略

在 `createDistributeTask` 中，使用 MyBatis-Plus 分页查询用户，每页读取后即时写入 detail 并发送 MQ，避免一次性加载全部用户到内存。

伪代码：
```java
Page<User> page = new Page<>(1, 500);
do {
    page = userMapper.selectPage(page, queryWrapper);
    // 写入 detail + 发送 MQ（同一事务批次内）
} while (page.hasNext());
```

## 重试机制

`POST /coupon-distribution/task/{id}/retry`：
1. 查询 `task_id=? AND status=2` 的 detail 记录
2. 逐条设置 `status=0`、`retry_count+1`
3. 重新发送 MQ 消息
4. 将 task status 从 2 改回 1（若已完成）

**防重复**：`uk_task_user` 保证同一任务内同一用户仅有一条 detail；重试时只改 `status` 不改 `user_id`。

## 错误处理

- **模板校验失败**：立即抛 `BusinessException`，不创建任务
- **库存不足**：立即抛 `BusinessException`，不创建任务
- **MQ 发送失败**：`@Transactional` 回滚 task + detail 写入，task status 保持未创建
- **Listener 处理失败**：catch 异常，更新 detail `status=2` 并记录 `fail_reason`，不影响其他消息
- **detail 更新失败**：catch 并记录 ERROR 日志，不阻断 MQ ack

## 文件变更清单

### 新增
- `entity/CouponDistributionDetail.java`
- `dto/CouponDistributionDetailDTO.java`
- `dto/CouponDistributionDetailPageRequestDTO.java`
- `dto/UserQueryConditionDTO.java`
- `mapper/CouponDistributionDetailMapper.java`

### 修改
- `dto/CouponDistributeRequestDTO.java`：移除 `List<Long> userIds`，改为 `UserQueryConditionDTO userQuery`
- `service/CouponDistributionService.java`：新增 `listTaskDetails`、`retryFailedUsers`
- `service/impl/CouponDistributionServiceImpl.java`：分页查询用户、预写入 detail、实现重试
- `listener/CouponDistributionListener.java`：更新 detail 状态
- `controller/CouponDistributionController.java`：新增明细查询和重试接口
