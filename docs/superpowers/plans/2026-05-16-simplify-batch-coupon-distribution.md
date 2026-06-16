# 简化批量发券流程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 `BatchReceiveResult` 和 `failMap` 机制，简化 `batchReceiveForDistribution` 为返回 `void`，消除冗余的预检查查询。

**Architecture:** `batchReceiveForDistribution` 直接执行"扣库存 + 批量插入"，依赖 `TransactionTemplate` 原子性和 `uk_user_template` 唯一索引兜底。`TaskExecutionListener` 不再处理 `failMap`，`successCount = newUserIds.size()`。

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis-Plus 3.5.7, MySQL 8.0

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/example/coupon/dto/BatchReceiveResult.java` | 删除 | 不再使用 |
| `src/main/java/com/example/coupon/service/UserCouponService.java` | 修改 | 方法签名返回 `void` |
| `src/main/java/com/example/coupon/service/impl/UserCouponServiceImpl.java` | 修改 | 简化 `batchReceiveForDistribution` 实现 |
| `src/main/java/com/example/coupon/listener/TaskExecutionListener.java` | 修改 | 去掉 `failMap` 处理 |
| `src/test/java/com/example/coupon/UserCouponServiceBatchTest.java` | 修改 | 删除 `testBatchReceivePartialDuplicate`，调整 `testBatchReceiveSuccess` |
| `src/test/java/com/example/coupon/IdempotencyTest.java` | 修改 | 删除 `testBatchReceiveDuplicateUser` |

---

### Task 1: 修改 Service 接口与实现

**Files:**
- Modify: `src/main/java/com/example/coupon/service/UserCouponService.java`
- Modify: `src/main/java/com/example/coupon/service/impl/UserCouponServiceImpl.java`
- Delete: `src/main/java/com/example/coupon/dto/BatchReceiveResult.java`

- [ ] **Step 1: 修改接口签名**

在 `UserCouponService.java` 中：
- 删除 `import com.example.coupon.dto.BatchReceiveResult;`
- 将 `BatchReceiveResult batchReceiveForDistribution(Long templateId, List<Long> userIds);` 改为 `void batchReceiveForDistribution(Long templateId, List<Long> userIds);`

- [ ] **Step 2: 修改实现**

在 `UserCouponServiceImpl.java` 中：
- 删除 `import com.example.coupon.dto.BatchReceiveResult;`
- 将 `batchReceiveForDistribution` 方法替换为：

```java
@Override
@Transactional
public void batchReceiveForDistribution(Long templateId, List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
        return;
    }

    if (!couponTemplateService.deductStockBatch(templateId, userIds.size())) {
        throw new BusinessException("优惠券库存不足");
    }

    LocalDateTime now = LocalDateTime.now();
    List<UserCoupon> coupons = userIds.stream().map(userId -> {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setTemplateId(templateId);
        uc.setCouponCode(TokenUtil.createToken());
        uc.setStatus(0);
        uc.setReceiveTime(now);
        return uc;
    }).toList();

    saveBatch(coupons);

    log.info("批量发券成功，templateId={}，count={}", templateId, coupons.size());
}
```

- [ ] **Step 3: 删除 `BatchReceiveResult` 类**

删除文件 `src/main/java/com/example/coupon/dto/BatchReceiveResult.java`。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS（此时 `TaskExecutionListener` 仍引用 `BatchReceiveResult`，会编译失败，属于预期，Task 2 修复）

---

### Task 2: 修改 TaskExecutionListener

**Files:**
- Modify: `src/main/java/com/example/coupon/listener/TaskExecutionListener.java`

- [ ] **Step 1: 去掉 `BatchReceiveResult` 引用和 `failMap` 处理**

在 `TaskExecutionListener.java` 中：
- 删除 `import com.example.coupon.dto.BatchReceiveResult;`
- 将 `processPage` 方法中步骤 3-5 替换为：

```java
        // 3. 批量发券
        userCouponService.batchReceiveForDistribution(message.getTemplateId(), newUserIds);

        // 4. 批量更新 detail 状态为成功
        detailService.update(null, new UpdateWrapper<CouponDistributionDetail>()
                .eq("task_id", message.getTaskId())
                .in("user_id", newUserIds)
                .set("status", 1)
                .set("update_time", LocalDateTime.now()));

        // 5. 更新 task 进度
        int successCount = newUserIds.size();
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", message.getTaskId())
                .setSql("processed_count = processed_count + " + newUserIds.size())
                .setSql("success_count = success_count + " + successCount));
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 3: 修改测试

**Files:**
- Modify: `src/test/java/com/example/coupon/UserCouponServiceBatchTest.java`
- Modify: `src/test/java/com/example/coupon/IdempotencyTest.java`

- [ ] **Step 1: 修改 `UserCouponServiceBatchTest`**

- 删除 `import com.example.coupon.dto.BatchReceiveResult;`
- 修改 `testBatchReceiveSuccess`：

```java
    @Test
    void testBatchReceiveSuccess() {
        Coupon coupon = createTemplate(100);
        couponTemplateService.publish(coupon.getId());

        User user1 = createUser("batch_user_1");
        User user2 = createUser("batch_user_2");

        List<Long> userIds = Arrays.asList(user1.getId(), user2.getId());
        userCouponService.batchReceiveForDistribution(coupon.getId(), userIds);

        Coupon updated = couponTemplateMapper.selectById(coupon.getId());
        assertEquals(98, updated.getStock());
    }
```

- 删除整个 `testBatchReceivePartialDuplicate` 方法。

- [ ] **Step 2: 修改 `IdempotencyTest`**

- 删除 `testBatchReceiveDuplicateUser` 整个方法。

- [ ] **Step 3: 运行测试**

Run: `mvn test -Dtest=UserCouponServiceBatchTest,IdempotencyTest -q`
Expected: Tests run: 4, Failures: 0, Errors: 0

---

### Task 4: 端到端验证

**Files:**
- Test: `src/test/java/com/example/coupon/MassDistributionReuseUsersTest.java`

- [ ] **Step 1: 运行大规模分发测试**

Run: `mvn test -Dtest=MassDistributionReuseUsersTest -q`
Expected: BUILD SUCCESS（任务完成，processedCount = successCount = userCount，failCount = 0）

---

## Self-Review

1. **Spec coverage:** 所有设计文档中的变更点都有对应任务。
2. **Placeholder scan:** 无 TBD/TODO。
3. **Type consistency:** `batchReceiveForDistribution` 返回 `void` 在所有文件中使用一致。
