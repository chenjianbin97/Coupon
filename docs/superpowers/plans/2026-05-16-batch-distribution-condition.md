# Batch Coupon Distribution with Condition Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace explicit userId list with condition-based user targeting, add per-user detail tracking, and support retry for failed users.

**Architecture:** Backend paginates `t_user` by query conditions, pre-inserts detail rows into `t_coupon_distribution_detail`, sends MQ messages per user, listener updates detail status per user. Controller exposes task progress query and retry endpoints.

**Tech Stack:** Spring Boot 3.2.12, Java 17, MyBatis-Plus 3.5.7, RabbitMQ, Lombok

---

## File Structure

### New Files
- `entity/CouponDistributionDetail.java` — per-user detail entity
- `dto/CouponDistributionDetailDTO.java` — detail response DTO
- `dto/CouponDistributionDetailPageRequestDTO.java` — detail page query DTO
- `dto/UserQueryConditionDTO.java` — user query condition input
- `mapper/CouponDistributionDetailMapper.java` — detail mapper

### Modified Files
- `dto/CouponDistributeRequestDTO.java` — replace `List<Long> userIds` with `UserQueryConditionDTO userQuery`
- `service/CouponDistributionService.java` — add `listTaskDetails`, `retryFailedUsers`
- `service/impl/CouponDistributionServiceImpl.java` — paginate users by condition, pre-insert details, retry logic
- `listener/CouponDistributionListener.java` — update detail status on success/fail
- `controller/CouponDistributionController.java` — add detail query and retry endpoints

---

### Task 1: Create Entity and DTOs

**Files:**
- Create: `src/main/java/com/example/coupon/entity/CouponDistributionDetail.java`
- Create: `src/main/java/com/example/coupon/dto/CouponDistributionDetailDTO.java`
- Create: `src/main/java/com/example/coupon/dto/CouponDistributionDetailPageRequestDTO.java`
- Create: `src/main/java/com/example/coupon/dto/UserQueryConditionDTO.java`
- Modify: `src/main/java/com/example/coupon/dto/CouponDistributeRequestDTO.java`

- [ ] **Step 1: Create `CouponDistributionDetail` entity**

```java
package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_coupon_distribution_detail")
public class CouponDistributionDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long userId;

    private Integer status;

    private String failReason;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
```

- [ ] **Step 2: Create `CouponDistributionDetailDTO`**

```java
package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponDistributionDetailDTO {

    private Long id;

    private Long taskId;

    private Long userId;

    private Integer status;

    private String failReason;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: Create `CouponDistributionDetailPageRequestDTO`**

```java
package com.example.coupon.dto;

import lombok.Data;

@Data
public class CouponDistributionDetailPageRequestDTO {

    private Long taskId;

    private Integer status;

    private Long page = 1L;

    private Long size = 20L;
}
```

- [ ] **Step 4: Create `UserQueryConditionDTO`**

```java
package com.example.coupon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserQueryConditionDTO {

    private Long shopNumber;

    private Integer status;

    private LocalDateTime registerTimeStart;

    private LocalDateTime registerTimeEnd;

    private Boolean hasPhone;

    private Boolean hasEmail;
}
```

- [ ] **Step 5: Modify `CouponDistributeRequestDTO`**

Replace the entire file content with:

```java
package com.example.coupon.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CouponDistributeRequestDTO {

    @NotNull(message = "优惠券模板ID不能为空")
    private Long templateId;

    @NotNull(message = "用户查询条件不能为空")
    private UserQueryConditionDTO userQuery;
}
```

---

### Task 2: Create Mapper

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/CouponDistributionDetailMapper.java`

- [ ] **Step 1: Create mapper**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.CouponDistributionDetail;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponDistributionDetailMapper extends BaseMapper<CouponDistributionDetail> {
}
```

---

### Task 3: Update Service Interface

**Files:**
- Modify: `src/main/java/com/example/coupon/service/CouponDistributionService.java`

- [ ] **Step 1: Add new methods to interface**

Replace the entire file with:

```java
package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.dto.CouponDistributeRequestDTO;
import com.example.coupon.dto.CouponDistributionDetailDTO;
import com.example.coupon.dto.CouponDistributionTaskDTO;

public interface CouponDistributionService {

    Long createDistributeTask(CouponDistributeRequestDTO dto, Long operatorId);

    CouponDistributionTaskDTO getTask(Long id);

    Page<CouponDistributionDetailDTO> listTaskDetails(Long taskId, Integer status, Long page, Long size);

    Long retryFailedUsers(Long taskId, Long operatorId);
}
```

---

### Task 4: Rewrite Service Implementation

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/CouponDistributionServiceImpl.java`

- [ ] **Step 1: Replace the entire service implementation**

```java
package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.*;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.entity.CouponDistributionTask;
import com.example.coupon.entity.User;
import com.example.coupon.mapper.CouponDistributionDetailMapper;
import com.example.coupon.mapper.CouponDistributionTaskMapper;
import com.example.coupon.mapper.UserMapper;
import com.example.coupon.service.CouponDistributionService;
import com.example.coupon.service.CouponTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CouponDistributionServiceImpl implements CouponDistributionService {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponDistributionTaskMapper taskMapper;

    @Autowired
    private CouponDistributionDetailMapper detailMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

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
        task.setStatus(1);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        log.info("创建分发任务成功，taskId={}，templateId={}，totalCount={}，operatorId={}",
                task.getId(), dto.getTemplateId(), userCount, operatorId);

        // 分页查询用户，预写入 detail 并发送 MQ
        int pageNum = 1;
        int pageSize = 500;
        Page<User> page;
        do {
            page = new Page<>(pageNum, pageSize);
            page = userMapper.selectPage(page, buildUserQueryWrapper(dto.getUserQuery()));
            List<User> users = page.getRecords();
            if (!users.isEmpty()) {
                List<CouponDistributionDetail> details = new ArrayList<>();
                for (User user : users) {
                    CouponDistributionDetail detail = new CouponDistributionDetail();
                    detail.setTaskId(task.getId());
                    detail.setUserId(user.getId());
                    detail.setStatus(0);
                    detail.setRetryCount(0);
                    detail.setCreateTime(LocalDateTime.now());
                    detail.setUpdateTime(LocalDateTime.now());
                    details.add(detail);

                    CouponDistributeMessage message = new CouponDistributeMessage(task.getId(), dto.getTemplateId(), user.getId());
                    rabbitTemplate.convertAndSend(RabbitMQConfig.COUPON_DISTRIBUTE_EXCHANGE, RabbitMQConfig.COUPON_DISTRIBUTE_ROUTING_KEY, message);
                }
                for (CouponDistributionDetail detail : details) {
                    detailMapper.insert(detail);
                }
            }
            pageNum++;
        } while (page.hasNext());

        log.info("分发任务消息发送完成，taskId={}，messageCount={}", task.getId(), userCount);
        return task.getId();
    }

    @Override
    public CouponDistributionTaskDTO getTask(Long id) {
        CouponDistributionTask task = taskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        return toTaskDTO(task);
    }

    @Override
    public Page<CouponDistributionDetailDTO> listTaskDetails(Long taskId, Integer status, Long page, Long size) {
        QueryWrapper<CouponDistributionDetail> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        Page<CouponDistributionDetail> resultPage = detailMapper.selectPage(new Page<>(page, size), wrapper);
        List<CouponDistributionDetailDTO> list = resultPage.getRecords().stream().map(this::toDetailDTO).toList();
        Page<CouponDistributionDetailDTO> dtoPage = new Page<>(resultPage.getCurrent(), resultPage.getSize(), resultPage.getTotal());
        dtoPage.setRecords(list);
        return dtoPage;
    }

    @Override
    @Transactional
    public Long retryFailedUsers(Long taskId, Long operatorId) {
        CouponDistributionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new com.example.coupon.common.exception.BusinessException("任务不存在");
        }

        QueryWrapper<CouponDistributionDetail> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        wrapper.eq("status", 2);
        List<CouponDistributionDetail> failedList = detailMapper.selectList(wrapper);
        if (failedList.isEmpty()) {
            throw new com.example.coupon.common.exception.BusinessException("没有失败的用户需要重试");
        }

        for (CouponDistributionDetail detail : failedList) {
            detail.setStatus(0);
            detail.setRetryCount(detail.getRetryCount() + 1);
            detail.setUpdateTime(LocalDateTime.now());
            detailMapper.updateById(detail);

            CouponDistributeMessage message = new CouponDistributeMessage(taskId, task.getTemplateId(), detail.getUserId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.COUPON_DISTRIBUTE_EXCHANGE, RabbitMQConfig.COUPON_DISTRIBUTE_ROUTING_KEY, message);
        }

        // 将任务状态改回处理中
        if (task.getStatus() == 2) {
            task.setStatus(1);
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
        }

        log.info("重试分发任务，taskId={}，retryCount={}，operatorId={}", taskId, failedList.size(), operatorId);
        return (long) failedList.size();
    }

    private long countUsersByCondition(UserQueryConditionDTO condition) {
        return userMapper.selectCount(buildUserQueryWrapper(condition));
    }

    private QueryWrapper<User> buildUserQueryWrapper(UserQueryConditionDTO condition) {
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

    private CouponDistributionTaskDTO toTaskDTO(CouponDistributionTask task) {
        CouponDistributionTaskDTO dto = new CouponDistributionTaskDTO();
        BeanUtils.copyProperties(task, dto);
        return dto;
    }

    private CouponDistributionDetailDTO toDetailDTO(CouponDistributionDetail detail) {
        CouponDistributionDetailDTO dto = new CouponDistributionDetailDTO();
        BeanUtils.copyProperties(detail, dto);
        return dto;
    }
}
```

---

### Task 5: Update Listener to Track Per-User Results

**Files:**
- Modify: `src/main/java/com/example/coupon/listener/CouponDistributionListener.java`

- [ ] **Step 1: Inject detail mapper and update detail status**

Replace the entire file with:

```java
package com.example.coupon.listener;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.CouponDistributeMessage;
import com.example.coupon.dto.ReceiveCouponRequestDTO;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.entity.CouponDistributionTask;
import com.example.coupon.mapper.CouponDistributionDetailMapper;
import com.example.coupon.mapper.CouponDistributionTaskMapper;
import com.example.coupon.service.UserCouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class CouponDistributionListener {

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private CouponDistributionTaskMapper taskMapper;

    @Autowired
    private CouponDistributionDetailMapper detailMapper;

    @RabbitListener(queues = RabbitMQConfig.COUPON_DISTRIBUTE_QUEUE)
    public void onMessage(CouponDistributeMessage message) {
        log.info("收到分发消息，taskId={}，templateId={}，userId={}",
                message.getTaskId(), message.getTemplateId(), message.getUserId());

        String failReason = null;
        boolean success = false;
        try {
            ReceiveCouponRequestDTO dto = new ReceiveCouponRequestDTO();
            dto.setTemplateId(message.getTemplateId());
            userCouponService.receiveCoupon(dto, message.getUserId());
            success = true;
            log.info("分发领券成功，taskId={}，userId={}", message.getTaskId(), message.getUserId());
        } catch (Exception e) {
            failReason = e.getMessage();
            log.warn("分发领券失败，taskId={}，userId={}，error={}",
                    message.getTaskId(), message.getUserId(), failReason);
        }

        updateDetail(message.getTaskId(), message.getUserId(), success, failReason);
        updateTaskProgress(message.getTaskId(), success);
    }

    private void updateDetail(Long taskId, Long userId, boolean success, String failReason) {
        try {
            detailMapper.update(null, new UpdateWrapper<CouponDistributionDetail>()
                    .eq("task_id", taskId)
                    .eq("user_id", userId)
                    .set("status", success ? 1 : 2)
                    .set("fail_reason", success ? null : failReason)
                    .set("update_time", LocalDateTime.now()));
        } catch (Exception e) {
            log.error("更新分发详情失败，taskId={}，userId={}，error={}", taskId, userId, e.getMessage());
        }
    }

    private void updateTaskProgress(Long taskId, boolean success) {
        try {
            taskMapper.update(null, new UpdateWrapper<CouponDistributionTask>()
                    .eq("id", taskId)
                    .setSql("processed_count = processed_count + 1")
                    .setSql(success ? "success_count = success_count + 1" : "fail_count = fail_count + 1"));

            CouponDistributionTask task = taskMapper.selectById(taskId);
            if (task != null && task.getProcessedCount() >= task.getTotalCount()) {
                int affected = taskMapper.update(null, new UpdateWrapper<CouponDistributionTask>()
                        .eq("id", taskId)
                        .eq("status", 1)
                        .set("status", 2)
                        .set("update_time", LocalDateTime.now()));
                if (affected > 0) {
                    log.info("分发任务全部处理完成，taskId={}，success={}，fail={}",
                            taskId, task.getSuccessCount(), task.getFailCount());
                }
            }
        } catch (Exception e) {
            log.error("更新分发任务进度失败，taskId={}，error={}", taskId, e.getMessage());
        }
    }
}
```

---

### Task 6: Update Controller with New Endpoints

**Files:**
- Modify: `src/main/java/com/example/coupon/controller/CouponDistributionController.java`

- [ ] **Step 1: Add detail query and retry endpoints**

Replace the entire file with:

```java
package com.example.coupon.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.CouponDistributeRequestDTO;
import com.example.coupon.dto.CouponDistributionDetailDTO;
import com.example.coupon.dto.CouponDistributionTaskDTO;
import com.example.coupon.service.CouponDistributionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupon-distribution")
public class CouponDistributionController {

    @Autowired
    private CouponDistributionService couponDistributionService;

    @PostMapping("/distribute")
    public Result<Long> distribute(@Valid @RequestBody CouponDistributeRequestDTO dto) {
        Long taskId = couponDistributionService.createDistributeTask(dto, UserContext.getUser().getId());
        return Result.success(taskId);
    }

    @GetMapping("/task/{id}")
    public Result<CouponDistributionTaskDTO> getTask(@PathVariable Long id) {
        CouponDistributionTaskDTO dto = couponDistributionService.getTask(id);
        return Result.success(dto);
    }

    @GetMapping("/task/{id}/details")
    public Result<Page<CouponDistributionDetailDTO>> listDetails(
            @PathVariable Long id,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size) {
        Page<CouponDistributionDetailDTO> result = couponDistributionService.listTaskDetails(id, status, page, size);
        return Result.success(result);
    }

    @PostMapping("/task/{id}/retry")
    public Result<Long> retryFailed(@PathVariable Long id) {
        Long count = couponDistributionService.retryFailedUsers(id, UserContext.getUser().getId());
        return Result.success(count);
    }
}
```

---

### Task 7: Database Migration

**Files:**
- Create or run SQL directly

- [ ] **Step 1: Execute SQL to create detail table**

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

---

### Task 8: Compile Verification

- [ ] **Step 1: Run Maven compile**

Run: `mvn compile -q`

Expected: BUILD SUCCESS with no errors.

---

### Task 9: Integration Test (Manual)

- [ ] **Step 1: Start Docker containers**

```bash
docker start mysql redis rabbitmq
```

- [ ] **Step 2: Verify by creating a distribution task**

1. Login to get token
2. Create and publish a coupon template
3. POST `/coupon-distribution/distribute` with a condition that matches some test users
4. GET `/coupon-distribution/task/{id}` to watch progress
5. GET `/coupon-distribution/task/{id}/details?status=2` to inspect failures
6. POST `/coupon-distribution/task/{id}/retry` to retry failures

---

## Self-Review

**Spec coverage:**
- ✅ 条件圈选替代 list<userId> — Task 4 `createDistributeTask` 分页查询用户
- ✅ 用户级明细追踪 — Task 1 entity/dto + Task 5 listener 更新 detail
- ✅ 重试失败用户 — Task 4 `retryFailedUsers` + Task 6 controller endpoint
- ✅ 聚合进度统计 — Task 5 listener 保留 task progress 更新

**Placeholder scan:**
- ✅ 无 TBD/TODO
- ✅ 所有代码块完整可直接复制

**Type consistency:**
- ✅ `CouponDistributeMessage` 复用已有结构
- ✅ `ReceiveCouponRequestDTO` 复用已有结构
- ✅ 所有新增 DTO/Entity 字段名一致
