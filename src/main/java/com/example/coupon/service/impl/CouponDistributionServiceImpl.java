package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.common.enums.CouponTaskStatusEnum;
import com.example.coupon.common.enums.CouponTemplateStatusEnum;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.*;
import com.example.coupon.entity.CouponDistributionDetail;
import com.example.coupon.entity.CouponDistributionTask;
import com.example.coupon.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CouponDistributionServiceImpl implements CouponDistributionService {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private CouponDistributionTaskService taskService;

    @Autowired
    private CouponDistributionDetailService detailService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final int CHUNK_SIZE = 2000;

    @Override
    @Transactional
    public Long createDistributeTask(CouponDistributeRequestDTO dto, Long operatorId) {
        CouponTemplateDTO template = couponTemplateService.getTemplate(dto.getTemplateId());
        if (template == null) {
            throw new com.example.coupon.common.exception.BusinessException("优惠券模板不存在");
        }
        if (template.getStatus() != CouponTemplateStatusEnum.PUBLISHED.getStatus()) {
            throw new com.example.coupon.common.exception.BusinessException("优惠券未发布，无法分发");
        }

        List<Long> userIds = dto.getUserIds();

        CouponDistributionTask task = new CouponDistributionTask();
        task.setTemplateId(dto.getTemplateId());
        task.setShopId(template.getShopNumber());
        task.setTotalCount(userIds.size());
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setProcessedCount(0);
        task.setStatus(CouponTaskStatusEnum.IN_PROGRESS.getStatus());
        try {
            task.setQueryCondition(objectMapper.writeValueAsString(userIds));
        } catch (Exception e) {
            throw new com.example.coupon.common.exception.BusinessException("用户列表序列化失败");
        }
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskService.save(task);

        // 按块大小拆分为多条 MQ 消息
        for (int i = 0; i < userIds.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, userIds.size());
            List<Long> chunk = new ArrayList<>(userIds.subList(i, end));
            String messageId = task.getId() + ":chunk:" + (i / CHUNK_SIZE);
            CouponTaskExecuteMessage message = new CouponTaskExecuteMessage(
                    task.getId(), dto.getTemplateId(), chunk, messageId);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.COUPON_TASK_EXECUTE_EXCHANGE,
                    RabbitMQConfig.COUPON_TASK_EXECUTE_ROUTING_KEY,
                    message);
        }

        log.info("创建分发任务成功，taskId={}，templateId={}，totalCount={}，chunkCount={}，operatorId={}",
                task.getId(), dto.getTemplateId(), userIds.size(),
                (int) Math.ceil((double) userIds.size() / CHUNK_SIZE), operatorId);
        return task.getId();
    }

    @Override
    public CouponDistributionTaskDTO getTask(Long id) {
        CouponDistributionTask task = taskService.getById(id);
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
        Page<CouponDistributionDetail> resultPage = detailService.page(new Page<>(page, size), wrapper);
        List<CouponDistributionDetailDTO> list = resultPage.getRecords().stream().map(this::toDetailDTO).toList();
        Page<CouponDistributionDetailDTO> dtoPage = new Page<>(resultPage.getCurrent(), resultPage.getSize(), resultPage.getTotal());
        dtoPage.setRecords(list);
        return dtoPage;
    }

    @Override
    @Transactional
    public Long retryFailedUsers(Long taskId, Long operatorId) {
        CouponDistributionTask task = taskService.getById(taskId);
        if (task == null) {
            throw new com.example.coupon.common.exception.BusinessException("任务不存在");
        }

        List<Long> userIds;
        try {
            userIds = objectMapper.readValue(task.getQueryCondition(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            throw new com.example.coupon.common.exception.BusinessException("用户列表反序列化失败");
        }

        detailService.removePhysicalByTaskId(taskId);

        task.setStatus(CouponTaskStatusEnum.IN_PROGRESS.getStatus());
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setUpdateTime(LocalDateTime.now());
        taskService.updateById(task);

        for (int i = 0; i < userIds.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, userIds.size());
            List<Long> chunk = new ArrayList<>(userIds.subList(i, end));
            String messageId = task.getId() + ":retry:" + (i / CHUNK_SIZE);
            CouponTaskExecuteMessage message = new CouponTaskExecuteMessage(
                    task.getId(), task.getTemplateId(), chunk, messageId);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.COUPON_TASK_EXECUTE_EXCHANGE,
                    RabbitMQConfig.COUPON_TASK_EXECUTE_ROUTING_KEY,
                    message);
        }

        log.info("重试分发任务，taskId={}，operatorId={}", taskId, operatorId);
        return task.getId();
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
