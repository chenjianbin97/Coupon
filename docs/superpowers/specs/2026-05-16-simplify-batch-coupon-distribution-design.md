# 简化批量发券流程设计

## 背景

当前 `batchReceiveForDistribution` 返回 `BatchReceiveResult`（包含 `successUserIds` 和 `failMap`），用于处理两种失败场景：

1. 用户已领取过该模板优惠券
2. 库存不足

但业务约束明确规定：**分发任务中的优惠券，用户不能自行领取**。同时 `TaskExecutionListener.processPage` 使用 `TransactionTemplate` 保证单页事务原子性，库存不足时会抛异常导致整页回滚。因此 `failMap` 机制是多余的——在一页 500 个用户的粒度上，要么全部成功，要么全部回滚，不存在"部分成功部分失败"的状态。

## 目标

消除 `BatchReceiveResult` 和 `failMap` 的复杂性，简化 `batchReceiveForDistribution` 和 `TaskExecutionListener` 的交互。

## 设计决策

### 1. 删除 `BatchReceiveResult`

该类及其 `successUserIds`、`failMap` 字段不再使用。

### 2. `batchReceiveForDistribution` 改为返回 `void`

去掉预检查 `t_user_coupon` 的 `SELECT` 查询。流程简化为：

1. `deductStockBatch(templateId, userIds.size())` — 库存不足抛 `BusinessException`，事务回滚
2. `saveBatch(coupons)` — 批量插入 `t_user_coupon`
3. `saveBatch` 外 `try-catch DuplicateKeyException`，静默跳过（幂等兜底）

**为什么可以去掉预检查：**
- 业务约束保证用户不会自行领取分发券
- `TransactionTemplate` 原子性保证 MQ 重试时不会出现"user_coupon 已插入但 detail 未更新"的中间状态
- `uk_user_template` 唯一索引 + `DuplicateKeyException` try-catch 构成最终防线

### 3. `TaskExecutionListener.processPage` 简化

变更前：
```java
BatchReceiveResult result = userCouponService.batchReceiveForDistribution(...);
int successCount = result.getSuccessUserIds().size();
int failCount = result.getFailMap().size();
// 分别更新 success 和 fail 的 detail
// failCount 计入 task 进度
```

变更后：
```java
userCouponService.batchReceiveForDistribution(...);
int successCount = newUserIds.size();
int failCount = 0;
// 批量更新所有 detail 为 status=1
```

### 4. 事务边界确认

```
onMessage()
  └─ txTemplate.execute()              // TransactionTemplate 开启事务
       └─ processPage()
            ├─ 查 detail 幂等（跳过 status=1）
            ├─ 创建 detail（status=0）
            ├─ batchReceiveForDistribution()  // @Transactional(REQUIRED)
            │    ├─ 扣减库存
            │    └─ saveBatch(user_coupon)
            ├─ 更新 detail（status=1）
            └─ 更新 task 进度
```

`batchReceiveForDistribution` 的 `@Transactional(REQUIRED)` 参与 `TransactionTemplate` 的同一事务。要么全部提交，要么全部回滚。MQ 重试时 `detail` 表无记录，重新走完整流程。

### 5. 测试调整

- 删除 `UserCouponServiceBatchTest.testBatchReceivePartialDuplicate`
- 保留 `UserCouponServiceBatchTest.testBatchReceiveInsufficientStock`（验证库存不足事务回滚）
- 删除 `IdempotencyTest.testBatchReceiveDuplicateUser`

## 验收标准

1. `BatchReceiveResult` 类被删除，编译无错误
2. `batchReceiveForDistribution` 返回 `void`，逻辑精简为"扣库存 + 批量插入"
3. `TaskExecutionListener` 不再处理 `failMap`，`successCount = newUserIds.size()`
4. 库存不足时仍抛异常，事务回滚，数据一致
5. 所有现有测试通过（调整后的测试集）
