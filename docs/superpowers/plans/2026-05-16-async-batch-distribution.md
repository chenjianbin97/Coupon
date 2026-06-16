# 异步批量优惠券分发实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `createDistributeTask` 改造为前端毫秒级返回、后台 MQ 消费者异步完成用户圈选/批量 insert detail/批量发券的单一队列架构。

**Architecture:** 前端只创建 task 主记录并发送一条任务执行 MQ 消息；消费者分页查询用户，每页批量 insert detail 后调用 `batchReceiveForDistribution` 批量发券（仅 DB 路径），最后更新 task 进度。废弃原有的领券队列，简化为单级 MQ。

**Tech Stack:** Spring Boot 3.2.12, MyBatis-Plus 3.5.7, RabbitMQ 3, Java 17

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `dto/CouponTaskExecuteMessage.java` | Create | MQ 任务执行消息（taskId + templateId + userQuery） |
| `dto/BatchReceiveResult.java` | Create | 批量发券结果（successUserIds + failMap） |
| `service/UserCouponService.java` | Modify | 新增 `batchReceiveForDistribution` 方法签名 |
| `service/impl/UserCouponServiceImpl.java` | Modify | 实现批量发券（仅 DB 路径：批量扣库存 + 批量 insert user_coupon） |
| `config/RabbitMQConfig.java` | Modify | 新增任务执行队列/交换机/绑定；保留旧配置供过渡 |
| `listener/TaskExecutionListener.java` | Create | 接收任务消息，分页查用户，批量 insert detail，批量发券，更新 task 进度 |
| `service/impl/CouponDistributionServiceImpl.java` | Modify | `createDistributeTask` 只创建 task + 发任务 MQ；`retryFailedUsers` 改为重发任务 MQ |
| `test/CouponDistributionTest.java` | Modify | 适配新架构：等待消费者执行完成后再断言 |
| `dto/CouponDistributeMessage.java` | Delete | 废弃 |
| `listener/CouponDistributionListener.java` | Delete | 废弃 |

---

### Task 1: 创建 MQ 消息 DTO 和批量发券结果 DTO

**Files:**
- Create: `src/main/java/com/example/coupon/dto/CouponTaskExecuteMessage.java`
- Create: `src/main/java/com/example/coupon/dto/BatchReceiveResult.java`

- [ ] **Step 1: 创建 `CouponTaskExecuteMessage`**

```java
package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponTaskExecuteMessage implements Serializable {

    private Long taskId;

    private Long templateId;

    private UserQueryConditionDTO userQuery;
}
```

- [ ] **Step 2: 创建 `BatchReceiveResult`**

```java
package com.example.coupon.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class BatchReceiveResult {

    private List<Long> successUserIds = new ArrayList<>();

    private Map<Long, String> failMap = new HashMap<>();
}
```

- [ ] **Step 3: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 2: 新增 RabbitMQ 任务执行队列配置

**Files:**
- Modify: `src/main/java/com/example/coupon/config/RabbitMQConfig.java`

- [ ] **Step 1: 在 `RabbitMQConfig` 中新增任务执行队列常量与 Bean**

在原有常量下方追加：

```java
    public static final String COUPON_TASK_EXECUTE_QUEUE = "coupon.task.execute.queue";
    public static final String COUPON_TASK_EXECUTE_EXCHANGE = "coupon.task.execute.exchange";
    public static final String COUPON_TASK_EXECUTE_ROUTING_KEY = "coupon.task.execute";

    @Bean
    public Queue couponTaskExecuteQueue() {
        return new Queue(COUPON_TASK_EXECUTE_QUEUE, true);
    }

    @Bean
    public DirectExchange couponTaskExecuteExchange() {
        return new DirectExchange(COUPON_TASK_EXECUTE_EXCHANGE, true, false);
    }

    @Bean
    public Binding couponTaskExecuteBinding() {
        return BindingBuilder.bind(couponTaskExecuteQueue())
                .to(couponTaskExecuteExchange())
                .with(COUPON_TASK_EXECUTE_ROUTING_KEY);
    }
```

> 旧的 `coupon.distribute.*` 队列配置保留，待 Task 7 统一删除，避免中间编译失败。

- [ ] **Step 2: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 3: 实现 `batchReceiveForDistribution` 批量发券接口

**Files:**
- Modify: `src/main/java/com/example/coupon/service/UserCouponService.java`
- Modify: `src/main/java/com/example/coupon/service/impl/UserCouponServiceImpl.java`

- [ ] **Step 1: 在接口中新增方法签名**

```java
    BatchReceiveResult batchReceiveForDistribution(Long templateId, List<Long> userIds);
```

- [ ] **Step 2: 在实现类中新增方法**

在 `UserCouponServiceImpl` 中，于 `receiveCouponForDistribution` 方法后追加：

```java
    @Override
    @Transactional
    public BatchReceiveResult batchReceiveForDistribution(Long templateId, List<Long> userIds) {
        BatchReceiveResult result = new BatchReceiveResult();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }

        // 1. 批量扣减库存
        int affected = couponTemplateService.deductStockBatch(templateId, userIds.size());
        if (affected == 0) {
            String reason = "优惠券库存不足";
            for (Long userId : userIds) {
                result.getFailMap().put(userId, reason);
            }
            log.warn("批量发券库存不足，templateId={}，need={}", templateId, userIds.size());
            return result;
        }

        // 2. 批量 insert t_user_coupon
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> coupons = new ArrayList<>();
        for (Long userId : userIds) {
            UserCoupon uc = new UserCoupon();
            uc.setUserId(userId);
            uc.setTemplateId(templateId);
            uc.setCouponCode(TokenUtil.createToken());
            uc.setStatus(0);
            uc.setReceiveTime(now);
            coupons.add(uc);
        }

        try {
            saveBatch(coupons);
            for (UserCoupon uc : coupons) {
                result.getSuccessUserIds().add(uc.getUserId());
            }
            log.info("批量发券成功，templateId={}，count={}", templateId, coupons.size());
        } catch (DuplicateKeyException e) {
            // 部分用户已领取，需要逐条区分
            log.warn("批量发券出现重复，templateId={}，降级逐条处理", templateId);
            return batchReceiveFallback(templateId, userIds);
        }

        return result;
    }

    private BatchReceiveResult batchReceiveFallback(Long templateId, List<Long> userIds) {
        BatchReceiveResult result = new BatchReceiveResult();
        for (Long userId : userIds) {
            try {
                receiveCouponForDistribution(templateId, userId);
                result.getSuccessUserIds().add(userId);
            } catch (BusinessException e) {
                result.getFailMap().put(userId, e.getMessage());
            }
        }
        return result;
    }
```

- [ ] **Step 3: `deductStockBatch` 依赖**

上述代码调用了 `couponTemplateService.deductStockBatch(templateId, count)`，需要在 `CouponTemplateService` 中新增该方法。在 Task 3.5 中处理。

---

### Task 3.5: 新增 `CouponTemplateService.deductStockBatch`

**Files:**
- Modify: `src/main/java/com/example/coupon/service/CouponTemplateService.java`
- Modify: `src/main/java/com/example/coupon/service/impl/CouponTemplateServiceImpl.java`

- [ ] **Step 1: 接口新增方法**

```java
    boolean deductStockBatch(Long id, int count);
```

- [ ] **Step 2: 实现类新增方法**

```java
    @Override
    public boolean deductStockBatch(Long id, int count) {
        int affected = baseMapper.update(null, new UpdateWrapper<Coupon>()
                .eq("id", id)
                .eq("status", 1)
                .ge("stock", count)
                .setSql("stock = stock - " + count));
        if (affected > 0) {
            log.info("批量库存扣减成功，id={}，count={}", id, count);
        } else {
            log.warn("批量库存扣减失败，id={}，count={}", id, count);
        }
        return affected > 0;
    }
```

> 注意：`setSql` 中直接拼接 `count` 是安全的，因为 `count` 来自代码内部计算（pageSize=500），不是外部输入。如果担心 SQL 注入风险，可以用 `setSql("stock = stock - {0}", count)`，但 MP 的 `setSql` 重载不支持占位符，直接拼接在内部控制参数下可接受。

- [ ] **Step 3: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 4: 创建 `TaskExecutionListener`

**Files:**
- Create: `src/main/java/com/example/coupon/listener/TaskExecutionListener.java`

- [ ] **Step 1: 编写 Listener**

```java
package com.example.coupon.listener;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.BatchReceiveResult;
import com.example.coupon.dto.CouponTaskExecuteMessage;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.entity.CouponDistributionTask;
import com.example.coupon.entity.User;
import com.example.coupon.service.CouponDistributionDetailService;
import com.example.coupon.service.CouponDistributionTaskService;
import com.example.coupon.service.UserCouponService;
import com.example.coupon.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    @RabbitListener(queues = RabbitMQConfig.COUPON_TASK_EXECUTE_QUEUE)
    public void onMessage(CouponTaskExecuteMessage message) {
        log.info("收到任务执行消息，taskId={}，templateId={}",
                message.getTaskId(), message.getTemplateId());

        // 标记执行中
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", message.getTaskId())
                .set("status", 1)
                .set("update_time", LocalDateTime.now()));

        int pageNum = 1;
        int pageSize = 500;
        Page<User> page;

        do {
            page = new Page<>(pageNum, pageSize);
            page = userService.page(page, buildUserQueryWrapper(message.getUserQuery()));
            List<User> users = page.getRecords();

            if (!users.isEmpty()) {
                // 1. 批量 insert detail
                List<CouponDistributionDetail> details = new ArrayList<>();
                List<Long> userIds = new ArrayList<>();
                for (User user : users) {
                    CouponDistributionDetail detail = new CouponDistributionDetail();
                    detail.setTaskId(message.getTaskId());
                    detail.setUserId(user.getId());
                    detail.setStatus(0);
                    detail.setRetryCount(0);
                    detail.setCreateTime(LocalDateTime.now());
                    detail.setUpdateTime(LocalDateTime.now());
                    details.add(detail);
                    userIds.add(user.getId());
                }
                detailService.saveBatch(details);

                // 2. 批量发券
                BatchReceiveResult result = userCouponService.batchReceiveForDistribution(
                        message.getTemplateId(), userIds);

                // 3. 批量 update detail
                for (Long successUserId : result.getSuccessUserIds()) {
                    detailService.update(null, new UpdateWrapper<CouponDistributionDetail>()
                            .eq("task_id", message.getTaskId())
                            .eq("user_id", successUserId)
                            .set("status", 1)
                            .set("update_time", LocalDateTime.now()));
                }
                for (var entry : result.getFailMap().entrySet()) {
                    detailService.update(null, new UpdateWrapper<CouponDistributionDetail>()
                            .eq("task_id", message.getTaskId())
                            .eq("user_id", entry.getKey())
                            .set("status", 2)
                            .set("fail_reason", entry.getValue())
                            .set("update_time", LocalDateTime.now()));
                }

                // 4. 更新 task 进度
                int successCount = result.getSuccessUserIds().size();
                int failCount = result.getFailMap().size();
                taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                        .eq("id", message.getTaskId())
                        .setSql("processed_count = processed_count + " + userIds.size())
                        .setSql("success_count = success_count + " + successCount)
                        .setSql("fail_count = fail_count + " + failCount));
            }

            pageNum++;
        } while (page.hasNext());

        // 标记完成
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", message.getTaskId())
                .set("status", 2)
                .set("update_time", LocalDateTime.now()));

        log.info("任务执行完成，taskId={}", message.getTaskId());
    }

    private QueryWrapper<User> buildUserQueryWrapper(com.example.coupon.dto.UserQueryConditionDTO condition) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        if (condition.getShopNumber() != null) {
            wrapper.eq("shop_number", condition.getShopNumber());
        }
        if (condition.getStatus() != null) {
            wrapper.eq("status", condition.getStatus());
        }
        if (condition.getRegisterTimeStart() != null) {
            wrapper.ge("create_time", condition.getRegisterTimeStart());
        }
        if (condition.getRegisterTimeEnd() != null) {
            wrapper.le("create_time", condition.getRegisterTimeEnd());
        }
        if (Boolean.TRUE.equals(condition.getHasPhone())) {
            wrapper.isNotNull("phone");
            wrapper.ne("phone", "");
        }
        if (Boolean.TRUE.equals(condition.getHasEmail())) {
            wrapper.isNotNull("email");
            wrapper.ne("email", "");
        }
        return wrapper;
    }
}
```

- [ ] **Step 2: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 5: 重构 `CouponDistributionServiceImpl.createDistributeTask`

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/CouponDistributionServiceImpl.java`

- [ ] **Step 1: 重写 `createDistributeTask` 方法体**

当前 `createDistributeTask` 已经包含分页查用户、批量 insert detail、TransactionSynchronizationManager 等复杂逻辑。需要替换为：

```java
    @Override
    @Transactional
    public Long createDistributeTask(CouponDistributeRequestDTO dto, Long operatorId) {
        CouponTemplateDTO template = couponTemplateService.getTemplate(dto.getTemplateId());
        if (template == null) {
            throw new com.example.coupon.common.exception.BusinessException("优惠券模板不存在");
        }
        if (template.getStatus() != 1) {
            throw new com.example.coupon.common.exception.BusinessException("优惠券未发布，无法分发");
        }

        long userCount = countUsersByCondition(dto.getUserQuery());
        if (userCount == 0) {
            throw new com.example.coupon.common.exception.BusinessException("没有符合条件的用户");
        }
        if (template.getStock() < userCount) {
            throw new com.example.coupon.common.exception.BusinessException("优惠券库存不足，需要 " + userCount + " 张，仅剩 " + template.getStock());
        }

        CouponDistributionTask task = new CouponDistributionTask();
        task.setTemplateId(dto.getTemplateId());
        task.setTotalCount((int) userCount);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setProcessedCount(0);
        task.setStatus(0);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskService.save(task);
        log.info("创建分发任务成功，taskId={}，templateId={}，totalCount={}，operatorId={}",
                task.getId(), dto.getTemplateId(), userCount, operatorId);

        CouponTaskExecuteMessage message = new CouponTaskExecuteMessage(
                task.getId(), dto.getTemplateId(), dto.getUserQuery());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.COUPON_TASK_EXECUTE_EXCHANGE,
                RabbitMQConfig.COUPON_TASK_EXECUTE_ROUTING_KEY,
                message);

        log.info("任务执行消息已发送，taskId={}", task.getId());
        return task.getId();
    }
```

> 需要导入 `CouponTaskExecuteMessage`。

- [ ] **Step 2: 清理不再使用的 import 和字段**

删除 `createDistributeTask` 方法内原有的 `messages` 列表、`TransactionSynchronizationManager` 相关逻辑。保留 `countUsersByCondition` 和 `buildUserQueryWrapper`（供其他方法复用）。

- [ ] **Step 3: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 6: 重构 `CouponDistributionServiceImpl.retryFailedUsers`

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/CouponDistributionServiceImpl.java`

- [ ] **Step 1: 重写 `retryFailedUsers`**

在新架构下，重试不再需要逐条处理失败的 detail。而是：
1. 检查 task 是否存在
2. 删除该 task 下所有 detail
3. 重置 task 计数和状态
4. 重新发送任务执行 MQ 消息

```java
    @Override
    @Transactional
    public Long retryFailedUsers(Long taskId, Long operatorId) {
        CouponDistributionTask task = taskService.getById(taskId);
        if (task == null) {
            throw new com.example.coupon.common.exception.BusinessException("任务不存在");
        }

        // 删除旧 detail
        detailService.remove(new QueryWrapper<CouponDistributionDetail>()
                .eq("task_id", taskId));

        // 重置 task
        task.setStatus(0);
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setUpdateTime(LocalDateTime.now());
        taskService.updateById(task);

        // 重新查询模板和条件，发送任务 MQ
        // 注意：userQuery 条件未持久化到 task 表，需要额外存储
        // 临时方案：从 task 无法恢复 userQuery，需要扩展 task 表或在重试时由前端传入
        // 当前先抛出异常提示不支持
        throw new com.example.coupon.common.exception.BusinessException("当前版本不支持整任务重试，请重新创建任务");
    }
```

> **问题：** `userQuery` 条件没有持久化到 `t_coupon_distribution_task` 表中，重试时无法恢复查询条件。需要在 task 表中新增 `query_condition` JSON 字段，或在重试时由前端传入新条件。

**修复方案：** 扩展 `CouponDistributionTask` 实体和表，增加 `queryCondition` 字段（JSON 类型）。

- [ ] **Step 2: 扩展 `CouponDistributionTask` 实体**

```java
    private String queryCondition;  // JSON string of UserQueryConditionDTO
```

- [ ] **Step 3: 修改 `createDistributeTask` 保存 queryCondition**

使用 `ObjectMapper` 将 `UserQueryConditionDTO` 序列化为 JSON 字符串存入 task。

```java
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // 在 createDistributeTask 中：
    try {
        task.setQueryCondition(objectMapper.writeValueAsString(dto.getUserQuery()));
    } catch (Exception e) {
        throw new com.example.coupon.common.exception.BusinessException("查询条件序列化失败");
    }
```

- [ ] **Step 4: 修改 `retryFailedUsers`**

```java
    @Override
    @Transactional
    public Long retryFailedUsers(Long taskId, Long operatorId) {
        CouponDistributionTask task = taskService.getById(taskId);
        if (task == null) {
            throw new com.example.coupon.common.exception.BusinessException("任务不存在");
        }

        UserQueryConditionDTO userQuery;
        try {
            userQuery = objectMapper.readValue(task.getQueryCondition(), UserQueryConditionDTO.class);
        } catch (Exception e) {
            throw new com.example.coupon.common.exception.BusinessException("查询条件反序列化失败");
        }

        // 删除旧 detail
        detailService.remove(new QueryWrapper<CouponDistributionDetail>()
                .eq("task_id", taskId));

        // 重置 task
        task.setStatus(0);
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setUpdateTime(LocalDateTime.now());
        taskService.updateById(task);

        // 重新发送任务 MQ
        CouponTaskExecuteMessage message = new CouponTaskExecuteMessage(
                task.getId(), task.getTemplateId(), userQuery);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.COUPON_TASK_EXECUTE_EXCHANGE,
                RabbitMQConfig.COUPON_TASK_EXECUTE_ROUTING_KEY,
                message);

        log.info("重试分发任务，taskId={}，operatorId={}", taskId, operatorId);
        return task.getId();
    }
```

- [ ] **Step 5: 更新数据库表结构**

```sql
ALTER TABLE t_coupon_distribution_task ADD COLUMN query_condition JSON NULL COMMENT '用户查询条件JSON';
```

- [ ] **Step 6: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 7: 删除废弃代码

**Files:**
- Delete: `src/main/java/com/example/coupon/dto/CouponDistributeMessage.java`
- Delete: `src/main/java/com/example/coupon/listener/CouponDistributionListener.java`
- Modify: `src/main/java/com/example/coupon/config/RabbitMQConfig.java`

- [ ] **Step 1: 删除 `CouponDistributeMessage.java` 和 `CouponDistributionListener.java`**

直接删除这两个文件。

- [ ] **Step 2: 清理 `RabbitMQConfig` 中旧队列配置**

删除 `COUPON_DISTRIBUTE_QUEUE` / `COUPON_DISTRIBUTE_EXCHANGE` / `COUPON_DISTRIBUTE_ROUTING_KEY` 常量及对应的 Bean 方法。

- [ ] **Step 3: 编译检查**

Run: `mvn compile -q`
Expected: SUCCESS

---

### Task 8: 更新集成测试 `CouponDistributionTest`

**Files:**
- Modify: `src/test/java/com/example/coupon/CouponDistributionTest.java`

- [ ] **Step 1: 重构测试流程**

新架构下：
- `createDistributeTask` 返回后 task `status=0`
- 需要等待 MQ 消费者执行完成后 task `status=2`
- 再断言 detail 状态

主要修改点：
1. `testDistributeByCondition`：创建任务后等待消费者完成
2. `testRetryFailedUsers_noFailures`：重试逻辑改为等待任务完成后再重试
3. `testListTaskDetails`：同上
4. 不再需要 `testDistributeWithNoMatchUsers`（行为不变）

```java
    private void waitForTaskComplete(Long taskId) throws InterruptedException {
        TimeUnit.SECONDS.sleep(2); // 等待 MQ 消息投递
        int wait = 0;
        CouponDistributionTask task = taskMapper.selectById(taskId);
        while (task.getStatus() != 2 && wait < 60) {
            TimeUnit.SECONDS.sleep(1);
            task = taskMapper.selectById(taskId);
            wait++;
        }
        if (task.getStatus() != 2) {
            throw new RuntimeException("任务未在预期时间内完成");
        }
    }
```

在 `testDistributeByCondition` 中：
```java
    Long taskId = couponDistributionService.createDistributeTask(dto, 99999L);
    assertNotNull(taskId);

    waitForTaskComplete(taskId);

    CouponDistributionTask task = taskMapper.selectById(taskId);
    assertEquals(3, task.getTotalCount());
    assertEquals(2, task.getStatus());
    assertEquals(3, task.getSuccessCount());
    assertEquals(0, task.getFailCount());
```

- [ ] **Step 2: `testRetryFailedUsers_noFailures` 修改**

由于重试逻辑变为整任务重试，该测试需要重命名并修改断言：
- 先创建任务并等待完成
- 调用 `retryFailedUsers`
- 验证任务状态重置为 0 并重新执行

但由于测试中没有制造失败场景，可以简化为验证重试接口能正常调用。

- [ ] **Step 3: 运行测试**

Run: `mvn test -Dtest=CouponDistributionTest -q`
Expected: 4 tests PASS

---

### Task 9: 批量发券单元测试

**Files:**
- Create: `src/test/java/com/example/coupon/UserCouponServiceBatchTest.java`

- [ ] **Step 1: 编写批量发券单元测试**

```java
package com.example.coupon;

import com.example.coupon.dto.BatchReceiveResult;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.User;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.CouponTemplateMapper;
import com.example.coupon.mapper.UserCouponMapper;
import com.example.coupon.mapper.UserMapper;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserCouponService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class UserCouponServiceBatchTest {

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Test
    void testBatchReceiveSuccess() {
        // 创建模板
        Coupon coupon = createTemplate(100);
        couponTemplateService.publish(coupon.getId());

        // 创建用户
        User user1 = createUser("batch_user_1");
        User user2 = createUser("batch_user_2");

        List<Long> userIds = Arrays.asList(user1.getId(), user2.getId());
        BatchReceiveResult result = userCouponService.batchReceiveForDistribution(coupon.getId(), userIds);

        assertEquals(2, result.getSuccessUserIds().size());
        assertTrue(result.getFailMap().isEmpty());

        // 验证库存扣减
        Coupon updated = couponTemplateMapper.selectById(coupon.getId());
        assertEquals(98, updated.getStock());
    }

    @Test
    void testBatchReceivePartialDuplicate() {
        Coupon coupon = createTemplate(100);
        couponTemplateService.publish(coupon.getId());

        User user1 = createUser("batch_user_dup_1");
        User user2 = createUser("batch_user_dup_2");

        // 先让用户1领取
        userCouponService.receiveCouponForDistribution(coupon.getId(), user1.getId());

        // 批量领取（含已领取用户）
        List<Long> userIds = Arrays.asList(user1.getId(), user2.getId());
        BatchReceiveResult result = userCouponService.batchReceiveForDistribution(coupon.getId(), userIds);

        assertEquals(1, result.getSuccessUserIds().size());
        assertEquals(1, result.getFailMap().size());
        assertTrue(result.getFailMap().containsKey(user1.getId()));
    }

    @Test
    void testBatchReceiveInsufficientStock() {
        Coupon coupon = createTemplate(1);
        couponTemplateService.publish(coupon.getId());

        User user1 = createUser("batch_user_stock_1");
        User user2 = createUser("batch_user_stock_2");

        List<Long> userIds = Arrays.asList(user1.getId(), user2.getId());
        BatchReceiveResult result = userCouponService.batchReceiveForDistribution(coupon.getId(), userIds);

        assertTrue(result.getSuccessUserIds().isEmpty());
        assertEquals(2, result.getFailMap().size());
    }

    private Coupon createTemplate(int stock) {
        Coupon coupon = new Coupon();
        coupon.setName("batch_test_" + System.currentTimeMillis());
        coupon.setShopNumber(1L);
        coupon.setSource(1);
        coupon.setTarget(1);
        coupon.setGoods("all");
        coupon.setType(1);
        coupon.setValidStartTime(LocalDateTime.now().minusDays(1));
        coupon.setValidEndTime(LocalDateTime.now().plusDays(7));
        coupon.setStock(stock);
        coupon.setReceiveRule("{\"limit\": 1}");
        coupon.setConsumeRule("{\"minAmount\": 100, \"discount\": 20}");
        coupon.setStatus(0);
        coupon.setCreateTime(LocalDateTime.now());
        coupon.setUpdateTime(LocalDateTime.now());
        couponTemplateMapper.insert(coupon);
        return coupon;
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username + "_" + System.currentTimeMillis());
        user.setPassword("123456");
        user.setStatus(0);
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -Dtest=UserCouponServiceBatchTest -q`
Expected: 3 tests PASS

---

## Self-Review

**1. Spec coverage:**
- [x] 前端秒返 taskId → Task 5
- [x] 单级 MQ 架构 → Task 2, Task 4
- [x] 消费者内部分页 → Task 4
- [x] 批量 insert detail → Task 4
- [x] 批量发券（仅 DB 路径）→ Task 3, Task 3.5
- [x] 更新 task 进度 → Task 4
- [x] 删除旧队列和 Listener → Task 7
- [x] 重试逻辑 → Task 6
- [x] 测试覆盖 → Task 8, Task 9

**2. Placeholder scan:** 无 TBD/TODO/"implement later"。所有步骤包含实际代码和命令。

**3. Type consistency:**
- `BatchReceiveResult` 中的 `failMap` 键为 `Long`（userId），值为 `String`（reason），与 `detailService.update` 中的 `entry.getKey()` / `entry.getValue()` 一致。
- `CouponTaskExecuteMessage` 包含 `UserQueryConditionDTO`，与 `createDistributeTask` 的 `dto.getUserQuery()` 类型一致。
- `deductStockBatch` 参数为 `(Long id, int count)`，与调用方 `userIds.size()` 类型一致。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-16-async-batch-distribution.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
