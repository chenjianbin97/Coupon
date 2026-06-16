# 异步批量优惠券分发设计

## 背景与目标

当前 `createDistributeTask` 同步完成用户圈选、创建 detail、发送 MQ 等全部操作，前端需等待数十秒（百万级用户）。

**目标：**
- 前端调用后**毫秒级返回** taskId
- 后台由 MQ 消费者异步执行整个分发任务
- 消费者内部采用**批量操作**（批量 insert detail + 批量发券）提升吞吐量
- 去掉中间领券 MQ 队列，简化为**单级 MQ 架构**

---

## 架构概述

### 前端同步阶段（`createDistributeTask`）

1. 校验模板存在且已发布
2. `COUNT` 查询估算用户数量
3. 校验库存 >= 用户数
4. 创建 `CouponDistributionTask`（`status=0` 待执行）
5. 发送**一条**任务执行 MQ 消息到 `coupon.task.execute.queue`
6. 立即返回 `taskId`

### MQ 消费者阶段（`TaskExecutionListener`）

1. 接收 `CouponTaskExecuteMessage`
2. 将 task `status` 改为 `1`（执行中）
3. 分页查询符合条件的用户（`pageSize=500`）
4. 每页处理：
   - 批量 insert `CouponDistributionDetail`（`status=0`）
   - 调用 `batchReceiveForDistribution(templateId, userIdList)` **批量发券（仅走 DB 路径）**
   - 根据返回结果批量 update detail（`status=1/2` + `fail_reason`）
   - 更新 task 进度（`processed_count/success_count/fail_count`）
5. 全部完成后，task `status` 改为 `2`（已完成）

---

## 数据流

```
Frontend ──POST /coupon-distribution/distribute──┐
                                                  ▼
                                      CouponDistributionService.createDistributeTask
                                                  │
                                                  ├── 创建 task（status=0）
                                                  ├── 发送 CouponTaskExecuteMessage
                                                  └── 返回 taskId ◄── Frontend
                                                                      │
                                                                      ▼
                                                         coupon.task.execute.queue
                                                                      │
                                                                      ▼
                                                           TaskExecutionListener
                                                                      │
                                                  ┌───────────────────┼───────────────────┐
                                                  ▼                   ▼                   ▼
                                           分页查询用户         批量insert detail      批量发券
                                                  │                   │                   │
                                                  ▼                   ▼                   ▼
                                           更新task进度      批量update detail      更新task进度
                                                  │                   │                   │
                                                  └───────────────────┴───────────────────┘
                                                                      │
                                                                      ▼
                                                           task status = 2（完成）
```

---

## 关键接口设计

### MQ 消息 DTO

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponTaskExecuteMessage implements Serializable {
    private Long taskId;
    private Long templateId;
    private UserQueryConditionDTO userQuery;
}
```

> `CouponDistributeMessage` 将删除，原 `coupon.distribute.queue` 废弃。

### 批量发券接口

```java
public interface UserCouponService {
    // 原有接口保持不变
    String receiveCoupon(ReceiveCouponRequestDTO dto, Long userId);
    String receiveCouponForDistribution(Long templateId, Long userId);

    // 新增：批量发券（仅走 DB 路径）
    BatchReceiveResult batchReceiveForDistribution(Long templateId, List<Long> userIds);
}

@Data
public class BatchReceiveResult {
    private List<Long> successUserIds;
    private Map<Long, String> failMap;  // userId -> failReason
}
```

**`batchReceiveForDistribution` 实现（DB 路径）：**

1. 批量扣减库存：`UPDATE t_coupon_template SET stock = stock - N WHERE id = ? AND stock >= N`
2. 若库存不足，全部失败，返回 `failMap`（每个 userId -> "优惠券库存不足"）
3. 库存扣减成功，批量 insert `t_user_coupon`
4. 依赖 `uk_user_template (user_id, template_id)` 唯一索引捕获已领取用户
5. 将 `DuplicateKeyException` 映射为已领取失败，其余为成功
6. 返回 `BatchReceiveResult`

### 消费者

```java
@Slf4j
@Component
public class TaskExecutionListener {

    @Autowired
    private UserService userService;
    @Autowired
    private UserCouponService userCouponService;
    @Autowired
    private CouponDistributionTaskService taskService;
    @Autowired
    private CouponDistributionDetailService detailService;

    @Transactional
    @RabbitListener(queues = "coupon.task.execute.queue")
    public void onMessage(CouponTaskExecuteMessage message) {
        // 1. 标记执行中
        taskService.updateStatus(message.getTaskId(), 1);

        // 2. 分页处理
        int pageNum = 1;
        int pageSize = 500;
        Page<User> page;
        do {
            page = userService.page(new Page<>(pageNum, pageSize), buildWrapper(message.getUserQuery()));
            List<User> users = page.getRecords();
            if (!users.isEmpty()) {
                // 批量 insert detail
                List<CouponDistributionDetail> details = toDetails(message.getTaskId(), users);
                detailService.saveBatch(details);

                // 批量发券
                List<Long> userIds = users.stream().map(User::getId).toList();
                BatchReceiveResult result = userCouponService.batchReceiveForDistribution(
                        message.getTemplateId(), userIds);

                // 批量 update detail
                batchUpdateDetails(message.getTaskId(), result);

                // 更新 task 进度
                updateTaskProgress(message.getTaskId(), result);
            }
            pageNum++;
        } while (page.hasNext());

        // 3. 标记完成
        taskService.updateStatus(message.getTaskId(), 2);
    }
}
```

---

## 数据库变更

### Task 状态枚举扩展

```
0 - 待执行（前端创建 task 后）
1 - 执行中（消费者开始处理）
2 - 已完成
3 - 执行失败（消费者抛异常中断）
```

无需新建表，仅扩展 `status` 取值含义。

---

## 错误处理

### 消费者执行异常

- 消费者方法加 `@Transactional`
- 执行过程中任何异常（DB 断开、库存不足等）都会导致事务回滚
- 回滚前将 task `status` 改为 `3`（执行失败）
- 抛异常让 MQ 消息**重新入队**（Spring AMQP 默认重试策略）
- 下次消费时重新执行整个任务

### 重试机制

- 消费者内部**不自动重试单页**（避免重复发券）
- 系统级重试依赖 MQ 消息重新入队
- 运营端可通过 `retryFailedUsers` 接口触发整任务重试
- 整任务重试前需**删除该 task 下所有 detail**，避免唯一索引冲突

---

## 测试策略

### 单元测试

1. `batchReceiveForDistribution` 全部成功
2. `batchReceiveForDistribution` 部分用户已领取（`DuplicateKeyException`）
3. `batchReceiveForDistribution` 库存不足（N > stock）

### 集成测试

1. `createDistributeTask` 调用后**秒返回**，task `status=0`
2. 消费者执行后，task 状态流转 `0→1→2`
3. detail 记录数和状态正确（成功/失败比例符合预期）
4. 消费者异常后 task `status=3`，MQ 重试后重新执行成功

---

## 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 批量发券路径 | **仅走 DB 路径** | Redis 批量 Lua 复杂度高，后台分发无需高并发预热优势 |
| 消费者事务 | **整任务一个 `@Transactional`** | 保证原子性：失败时全部回滚，下次消费重新执行 |
| MQ 队列合并 | **删除领券队列，只保留任务队列** | 减少中间环节，消费者直接完成领券 + 状态更新 |
| 分页策略 | **消费者内部分页（pageSize=500）** | 防止百万级用户内存溢出，每页独立批量操作 |
| 重试粒度 | **整任务重试** | 事务原子性要求，部分成功部分回滚会导致数据不一致 |
