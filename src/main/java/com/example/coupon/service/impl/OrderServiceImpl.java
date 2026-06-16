package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.coupon.common.enums.CouponTypeEnum;
import com.example.coupon.common.enums.OrderStatusEnum;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.Order;
import com.example.coupon.entity.OrderItem;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.OrderItemMapper;
import com.example.coupon.mapper.OrderMapper;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.OrderTimeoutMessage;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.OrderService;
import com.example.coupon.service.UserCouponService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public OrderDTO submit(OrderSubmitRequestDTO dto, Long userId) {
        // 1. 校验金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (var item : dto.getItems()) {
            totalAmount = totalAmount.add(
                    item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (dto.getOriginalAmount().compareTo(totalAmount) != 0) {
            throw new BusinessException("订单金额校验失败");
        }

        // 2. 查券 + 校验
        UserCoupon coupon = userCouponService.getOne(new QueryWrapper<UserCoupon>()
                .eq("coupon_code", dto.getCouponCode())
                .eq("user_id", userId)
                .eq("status", UserCouponStatusEnum.UNUSED.getStatus()));
        if (coupon == null) {
            throw new BusinessException("优惠券不存在或已使用");
        }
        if (coupon.getExpireTime() != null && coupon.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("优惠券已过期");
        }

        // 3. 查模板 + 校验商家
        Coupon template = couponTemplateService.getById(coupon.getTemplateId());
        if (template == null) {
            throw new BusinessException("优惠券模板不存在");
        }
        if (!template.getShopNumber().equals(dto.getShopId())) {
            throw new BusinessException("优惠券不属于该商家");
        }

        // 4. 计算实付
        BigDecimal amount = calcAmount(dto.getOriginalAmount(), template);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            amount = BigDecimal.ZERO;
        }

        // 5. 原子锁券
        boolean locked = userCouponService.lambdaUpdate()
                .eq(UserCoupon::getCouponCode, dto.getCouponCode())
                .eq(UserCoupon::getStatus, UserCouponStatusEnum.UNUSED.getStatus())
                .eq(UserCoupon::getUserId, userId)
                .set(UserCoupon::getStatus, UserCouponStatusEnum.LOCKED.getStatus())
                .update();
        if (!locked) {
            throw new BusinessException("优惠券已被使用或已过期");
        }

        // 6. 建订单
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setShopId(dto.getShopId());
        order.setCouponCode(dto.getCouponCode());
        order.setOriginalAmount(dto.getOriginalAmount());
        order.setAmount(amount);
        order.setStatus(OrderStatusEnum.PENDING_PAY.getStatus());
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        for (var item : dto.getItems()) {
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setGoodsId(item.getGoodsId());
            oi.setGoodsName(item.getGoodsName());
            oi.setQuantity(item.getQuantity());
            oi.setPrice(item.getPrice());
            orderItemMapper.insert(oi);
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        OrderTimeoutMessage msg = new OrderTimeoutMessage();
                        msg.setMessageId(java.util.UUID.randomUUID().toString());
                        msg.setOrderNo(order.getOrderNo());
                        msg.setUserId(userId);
                        msg.setCouponCode(dto.getCouponCode());
                        msg.setSentAt(LocalDateTime.now());

                        rabbitTemplate.convertAndSend(
                                RabbitMQConfig.ORDER_TIMEOUT_EXCHANGE,
                                RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                                msg,
                                m -> {
                                    m.getMessageProperties().setHeader("x-delay", 900000);
                                    return m;
                                });
                        log.info("已发送超时取消延迟消息，orderNo={}", order.getOrderNo());
                    }
                });

        log.info("订单创建成功，orderNo={}，userId={}，amount={}", order.getOrderNo(), userId, amount);
        return toDTO(order);
    }

    @Override
    @Transactional
    public void pay(String orderNo, Long userId) {
        Order order = orderMapper.selectOne(new QueryWrapper<Order>()
                .eq("order_no", orderNo).eq("user_id", userId));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 原子更新订单状态，防并发
        int paid = orderMapper.update(null, new UpdateWrapper<Order>()
                .eq("order_no", orderNo)
                .eq("status", OrderStatusEnum.PENDING_PAY.getStatus())
                .set("status", OrderStatusEnum.PAID.getStatus())
                .set("update_time", LocalDateTime.now()));
        if (paid == 0) {
            throw new BusinessException("订单状态不正确");
        }

        if (order.getCouponCode() != null) {
            userCouponService.lambdaUpdate()
                    .eq(UserCoupon::getCouponCode, order.getCouponCode())
                    .eq(UserCoupon::getStatus, UserCouponStatusEnum.LOCKED.getStatus())
                    .set(UserCoupon::getStatus, UserCouponStatusEnum.USED.getStatus())
                    .set(UserCoupon::getUseTime, LocalDateTime.now())
                    .update();
        }
        log.info("支付成功，orderNo={}，userId={}", orderNo, userId);
    }

    @Override
    @Transactional
    public void cancel(String orderNo, Long userId) {
        Order order = orderMapper.selectOne(new QueryWrapper<Order>()
                .eq("order_no", orderNo).eq("user_id", userId));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 原子更新订单状态，防并发
        int cancelled = orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Order>()
                .eq("order_no", orderNo)
                .eq("status", OrderStatusEnum.PENDING_PAY.getStatus())
                .set("status", OrderStatusEnum.CANCELLED.getStatus())
                .set("update_time", LocalDateTime.now()));
        if (cancelled == 0) {
            throw new BusinessException("订单状态不正确");
        }

        if (order.getCouponCode() != null) {
            userCouponService.lambdaUpdate()
                    .eq(UserCoupon::getCouponCode, order.getCouponCode())
                    .eq(UserCoupon::getStatus, UserCouponStatusEnum.LOCKED.getStatus())
                    .set(UserCoupon::getStatus, UserCouponStatusEnum.UNUSED.getStatus())
                    .update();
        }
        log.info("取消订单成功，orderNo={}，userId={}", orderNo, userId);
    }

    private BigDecimal calcAmount(BigDecimal original, Coupon template) {
        JsonNode rule;
        try {
            rule = objectMapper.readTree(template.getConsumeRule());
        } catch (Exception e) {
            log.error("解析优惠券规则失败，consumeRule={}", template.getConsumeRule(), e);
            throw new BusinessException("优惠券规则解析失败");
        }

        int type = template.getType();
        if (type == CouponTypeEnum.FULL_REDUCTION.getType()) {
            BigDecimal minAmount = new BigDecimal(rule.get("minAmount").asText());
            BigDecimal discount = new BigDecimal(rule.get("discount").asText());
            if (original.compareTo(minAmount) < 0) {
                throw new BusinessException("未达到最低消费金额");
            }
            return original.subtract(discount);
        }
        if (type == CouponTypeEnum.DISCOUNT.getType()) {
            return original.multiply(new BigDecimal(rule.get("discountRate").asText()));
        }
        if (type == CouponTypeEnum.DIRECT_REDUCTION.getType()) {
            BigDecimal directAmount = new BigDecimal(rule.get("directAmount").asText());
            return original.subtract(directAmount).max(BigDecimal.ZERO);
        }
        throw new BusinessException("未知的优惠券类型");
    }

    private String generateOrderNo() {
        String uuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return uuid.substring(0, 16);
    }

    private OrderDTO toDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setOrderNo(order.getOrderNo());
        dto.setUserId(order.getUserId());
        dto.setShopId(order.getShopId());
        dto.setCouponCode(order.getCouponCode());
        dto.setOriginalAmount(order.getOriginalAmount());
        dto.setAmount(order.getAmount());
        dto.setStatus(order.getStatus());
        dto.setCreateTime(order.getCreateTime());
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().eq("order_id", order.getId()));
        dto.setItems(items);
        return dto;
    }
}
