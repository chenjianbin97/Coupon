# 提交订单功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现下单锁券、支付核销、取消释放的完整订单流程。

**Architecture:** 下单一个事务完成"校验→计算→锁券→建订单"。支付/取消用原子 UPDATE 操作。订单状态枚举管理流转。

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis-Plus 3.5.7, MySQL 8.0

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `common/enums/UserCouponStatusEnum.java` | 修改 | 加 `LOCKED(2)` |
| `common/enums/OrderStatusEnum.java` | 新建 | 订单状态枚举 |
| `entity/Order.java` | 新建 | 订单实体 |
| `entity/OrderItem.java` | 新建 | 订单明细实体 |
| `dto/OrderSubmitRequestDTO.java` | 新建 | 下单请求 DTO |
| `dto/OrderDTO.java` | 新建 | 订单响应 DTO |
| `mapper/OrderMapper.java` | 新建 | 订单 Mapper |
| `mapper/OrderItemMapper.java` | 新建 | 订单明细 Mapper |
| `service/OrderService.java` | 新建 | 接口 |
| `service/impl/OrderServiceImpl.java` | 新建 | 实现 |
| `controller/OrderController.java` | 新建 | 控制器 |

---

### Task 1: 枚举 + 数据库

**Files:**
- Modify: `src/main/java/com/example/coupon/common/enums/UserCouponStatusEnum.java`
- Create: `src/main/java/com/example/coupon/common/enums/OrderStatusEnum.java`

- [ ] **Step 1: UserCouponStatusEnum 加 LOCKED**

```java
@Getter
public enum UserCouponStatusEnum {
    UNUSED(0, "未使用"),
    USED(1, "已使用"),
    LOCKED(2, "已锁定");
    // ... constructor unchanged
}
```

- [ ] **Step 2: 创建 OrderStatusEnum**

```java
package com.example.coupon.common.enums;

import lombok.Getter;

@Getter
public enum OrderStatusEnum {
    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    CANCELLED(2, "已取消");

    private final int status;
    private final String desc;

    OrderStatusEnum(int status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
```

- [ ] **Step 3: 建表**

```sql
CREATE TABLE t_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) UNIQUE NOT NULL,
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    coupon_code VARCHAR(64),
    original_amount DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status TINYINT DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消',
    create_time DATETIME,
    update_time DATETIME,
    del_flag TINYINT DEFAULT 0,
    INDEX idx_user_id (user_id)
);

CREATE TABLE t_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    goods_id VARCHAR(100),
    goods_name VARCHAR(200) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    INDEX idx_order_id (order_id)
);
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 2: 实体 + DTO

**Files:**
- Create: `src/main/java/com/example/coupon/entity/Order.java`
- Create: `src/main/java/com/example/coupon/entity/OrderItem.java`
- Create: `src/main/java/com/example/coupon/dto/OrderSubmitRequestDTO.java`
- Create: `src/main/java/com/example/coupon/dto/OrderDTO.java`

- [ ] **Step 1: Order.java**

```java
package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long shopId;
    private String couponCode;
    private BigDecimal originalAmount;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer delFlag;
}
```

- [ ] **Step 2: OrderItem.java**

```java
package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_order_item")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String goodsId;
    private String goodsName;
    private Integer quantity;
    private BigDecimal price;
}
```

- [ ] **Step 3: OrderSubmitRequestDTO.java**

```java
package com.example.coupon.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderSubmitRequestDTO {
    @NotNull private Long shopId;
    @NotEmpty private String couponCode;
    @NotNull private BigDecimal originalAmount;
    @NotEmpty private List<Item> items;

    @Data
    public static class Item {
        private String goodsId;
        private String goodsName;
        private Integer quantity;
        private BigDecimal price;
    }
}
```

- [ ] **Step 4: OrderDTO.java**

```java
package com.example.coupon.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDTO {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long shopId;
    private String couponCode;
    private BigDecimal originalAmount;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime createTime;
    private List<OrderItem> items;
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 3: Mapper

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/OrderMapper.java`
- Create: `src/main/java/com/example/coupon/mapper/OrderItemMapper.java`

- [ ] **Step 1: OrderMapper.java**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

- [ ] **Step 2: OrderItemMapper.java**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 4: Service + Controller

**Files:**
- Create: `src/main/java/com/example/coupon/service/OrderService.java`
- Create: `src/main/java/com/example/coupon/service/impl/OrderServiceImpl.java`
- Create: `src/main/java/com/example/coupon/controller/OrderController.java`

- [ ] **Step 1: OrderService.java**

```java
package com.example.coupon.service;

import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;

public interface OrderService {
    OrderDTO submit(OrderSubmitRequestDTO dto, Long userId);
    void pay(String orderNo, Long userId);
    void cancel(String orderNo, Long userId);
}
```

- [ ] **Step 2: OrderServiceImpl.java — submit**

```java
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
    CouponTemplate template = couponTemplateService.getById(coupon.getTemplateId());
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

    log.info("订单创建成功，orderNo={}，userId={}，amount={}", order.getOrderNo(), userId, amount);
    return toDTO(order);
}

private BigDecimal calcAmount(BigDecimal original, CouponTemplate template) {
    JsonNode rule = objectMapper.readTree(template.getConsumeRule());
    return switch (template.getType()) {
        case 1 -> {
            BigDecimal minAmount = new BigDecimal(rule.get("minAmount").asText());
            BigDecimal discount = new BigDecimal(rule.get("discount").asText());
            if (original.compareTo(minAmount) < 0)
                throw new BusinessException("未达到最低消费金额");
            yield original.subtract(discount);
        }
        case 2 -> original.multiply(new BigDecimal(rule.get("discountRate").asText()));
        case 3 -> {
            BigDecimal directAmount = new BigDecimal(rule.get("directAmount").asText());
            yield original.subtract(directAmount).max(BigDecimal.ZERO);
        }
        default -> throw new BusinessException("未知的优惠券类型");
    };
}
```

- [ ] **Step 3: OrderServiceImpl.java — pay**

```java
@Override
@Transactional
public void pay(String orderNo, Long userId) {
    Order order = orderMapper.selectOne(new QueryWrapper<Order>()
            .eq("order_no", orderNo).eq("user_id", userId));
    if (order == null) {
        throw new BusinessException("订单不存在");
    }
    if (order.getStatus() != OrderStatusEnum.PENDING_PAY.getStatus()) {
        throw new BusinessException("订单状态不正确");
    }

    order.setStatus(OrderStatusEnum.PAID.getStatus());
    order.setUpdateTime(LocalDateTime.now());
    orderMapper.updateById(order);

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
```

- [ ] **Step 4: OrderServiceImpl.java — cancel**

```java
@Override
@Transactional
public void cancel(String orderNo, Long userId) {
    Order order = orderMapper.selectOne(new QueryWrapper<Order>()
            .eq("order_no", orderNo).eq("user_id", userId));
    if (order == null) {
        throw new BusinessException("订单不存在");
    }
    if (order.getStatus() != OrderStatusEnum.PENDING_PAY.getStatus()) {
        throw new BusinessException("订单状态不正确");
    }

    order.setStatus(OrderStatusEnum.CANCELLED.getStatus());
    order.setUpdateTime(LocalDateTime.now());
    orderMapper.updateById(order);

    if (order.getCouponCode() != null) {
        userCouponService.lambdaUpdate()
                .eq(UserCoupon::getCouponCode, order.getCouponCode())
                .eq(UserCoupon::getStatus, UserCouponStatusEnum.LOCKED.getStatus())
                .set(UserCoupon::getStatus, UserCouponStatusEnum.UNUSED.getStatus())
                .update();
    }
    log.info("取消订单成功，orderNo={}，userId={}", orderNo, userId);
}
```

- [ ] **Step 5: OrderController.java**

```java
package com.example.coupon.controller;

import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.result.Result;
import com.example.coupon.dto.OrderDTO;
import com.example.coupon.dto.OrderSubmitRequestDTO;
import com.example.coupon.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/submit")
    public Result submit(@Valid @RequestBody OrderSubmitRequestDTO dto) {
        OrderDTO order = orderService.submit(dto, UserContext.getUser().getId());
        return Result.success(order);
    }

    @PostMapping("/{orderNo}/pay")
    public Result pay(@PathVariable String orderNo) {
        orderService.pay(orderNo, UserContext.getUser().getId());
        return Result.success(true);
    }

    @PostMapping("/{orderNo}/cancel")
    public Result cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo, UserContext.getUser().getId());
        return Result.success(true);
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 5: 集成测试

**Files:**
- Create: `src/test/java/com/example/coupon/OrderIntegrationTest.java`

- [ ] **Step 1: 写测试**

```java
@SpringBootTest
public class OrderIntegrationTest {
    // 1. 创建模板 + 发布
    // 2. 领券（调用 receiveCoupon）
    // 3. 下单（submit）→ 校验 order.amount 正确
    // 4. 支付（pay）→ 校验券状态 USED
    // 5. 再下单同一张券 → 应失败
    // 6. 取消场景：领券→下单→取消→校验券回 UNUSED
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -Dtest=OrderIntegrationTest -q`
Expected: BUILD SUCCESS
