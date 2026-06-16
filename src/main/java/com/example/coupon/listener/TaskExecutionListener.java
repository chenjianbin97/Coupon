package com.example.coupon.listener;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.enums.CouponDetailStatusEnum;
import com.example.coupon.common.enums.CouponTaskStatusEnum;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.CouponTaskExecuteMessage;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.entity.CouponDistributionTask;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.service.CouponDistributionDetailService;
import com.example.coupon.service.CouponDistributionTaskService;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserCouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TaskExecutionListener {

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponDistributionTaskService taskService;

    @Autowired
    private CouponDistributionDetailService detailService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.COUPON_TASK_EXECUTE_QUEUE)
    public void onMessage(CouponTaskExecuteMessage message) {
        log.info("收到任务执行消息，taskId={}，templateId={}，userCount={}，messageId={}",
                message.getTaskId(), message.getTemplateId(), message.getUserIds().size(), message.getMessageId());

        // 消息级幂等：Redis SETNX 防重
        String msgKey = String.format(RedisConstant.IDEMPOTENT_MSG_KEY, message.getMessageId());
        Boolean first = redisTemplate.opsForValue().setIfAbsent(msgKey, "1", java.time.Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            log.info("消息已处理，跳过，messageId={}", message.getMessageId());
            return;
        }

        CouponDistributionTask task = taskService.getById(message.getTaskId());
        if (task == null || task.getStatus() != CouponTaskStatusEnum.IN_PROGRESS.getStatus()) {
            log.warn("任务状态异常，已终止推送，taskId={}，status={}",
                    message.getTaskId(), task != null ? task.getStatus() : null);
            return;
        }

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setTimeout(300);

        try {
            txTemplate.executeWithoutResult(status -> {
                processChunk(message);
            });
        } catch (Exception e) {
            log.error("任务执行异常，taskId={}，error={}", message.getTaskId(), e.getMessage());
            redisTemplate.delete(msgKey);
            failTask(message.getTaskId());
            return;
        }

        // 仅当所有用户处理完成且未标记才更新，乐观锁防并发
        CouponDistributionTask latest = taskService.getById(message.getTaskId());
        if (latest != null
                && latest.getStatus() == CouponTaskStatusEnum.IN_PROGRESS.getStatus()
                && latest.getProcessedCount() >= latest.getTotalCount()) {
            completeTask(message.getTaskId());
        }
    }

    private void processChunk(CouponTaskExecuteMessage message) {
        List<Long> batch = message.getUserIds();
        // 1. 查找已领取该模板的用户，记录失败明细
        List<UserCoupon> existingCoupons = userCouponService.list(
                new QueryWrapper<UserCoupon>()
                        .eq("template_id", message.getTemplateId())
                        .in("user_id", batch)
                        .eq("del_flag", 0));
        Set<Long> receivedUserIds = existingCoupons.stream()
                .map(UserCoupon::getUserId)
                .collect(Collectors.toSet());

        int failCount = 0;
        if (!receivedUserIds.isEmpty()) {
            List<CouponDistributionDetail> failDetails = receivedUserIds.stream()
                    .map(userId -> {
                        CouponDistributionDetail detail = new CouponDistributionDetail();
                        detail.setTaskId(message.getTaskId());
                        detail.setUserId(userId);
                        detail.setStatus(CouponDetailStatusEnum.FAILED.getStatus());
                        detail.setFailReason("用户已领取该优惠券");
                        detail.setRetryCount(0);
                        detail.setCreateTime(LocalDateTime.now());
                        detail.setUpdateTime(LocalDateTime.now());
                        return detail;
                    }).toList();
            try {
                detailService.saveBatch(failDetails);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.debug("失败明细已存在，跳过，taskId={}", message.getTaskId());
            }
            failCount = failDetails.size();
        }

        // 2. INSERT IGNORE 剩余用户（并发竞态下唯一索引兜底，重复的静默跳过）
        List<Long> newUserIds = batch.stream()
                .filter(uid -> !receivedUserIds.contains(uid))
                .toList();
        int inserted = 0;
        if (!newUserIds.isEmpty()) {
            LocalDateTime expireTime = couponTemplateService.getTemplate(message.getTemplateId())
                    .getValidEndTime();
            inserted = userCouponService.batchReceiveForDistribution(
                    message.getTemplateId(), newUserIds, expireTime);
        }

        // 3. 更新 task 进度
        int successCount = inserted;
        addProgress(message.getTaskId(), batch.size(), successCount, failCount);
    }

    private void addProgress(Long taskId, int processed, int success, int fail) {
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", taskId)
                .setSql("processed_count = processed_count + " + processed)
                .setSql("success_count = success_count + " + success)
                .setSql("fail_count = fail_count + " + fail));
    }

    private void completeTask(Long taskId) {
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", taskId)
                .eq("status", CouponTaskStatusEnum.IN_PROGRESS.getStatus())
                .set("status", CouponTaskStatusEnum.COMPLETED.getStatus())
                .set("update_time", LocalDateTime.now()));
        log.info("任务执行完成，taskId={}", taskId);
    }

    private void failTask(Long taskId) {
        taskService.update(null, new UpdateWrapper<CouponDistributionTask>()
                .eq("id", taskId)
                .eq("status", CouponTaskStatusEnum.IN_PROGRESS.getStatus())
                .set("status", CouponTaskStatusEnum.FAILED.getStatus())
                .set("update_time", LocalDateTime.now()));
    }

}
