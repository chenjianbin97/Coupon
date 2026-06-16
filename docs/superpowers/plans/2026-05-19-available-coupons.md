# 查询可用优惠券 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 新增 `GET /coupon/available` 接口，根据商家、金额、商品查询当前用户可用的优惠券。

**Architecture:** Service 层 JOIN 查询 user_coupon + template，内存过滤满减门槛和商品范围，Controller 接收 query 参数返回 DTO 列表。

**Tech Stack:** Spring Boot 3.2, MyBatis-Plus 3.5.7, Java 17

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `dto/AvailableCouponDTO.java` | 新建 | 响应 DTO |
| `service/UserCouponService.java` | 修改 | 加接口方法 |
| `service/impl/UserCouponServiceImpl.java` | 修改 | 加实现 |
| `controller/UserCouponController.java` | 修改 | 加端点 |

---

### Task 1: DTO + Service 接口

**Files:**
- Create: `src/main/java/com/example/coupon/dto/AvailableCouponDTO.java`
- Modify: `src/main/java/com/example/coupon/service/UserCouponService.java`

- [ ] **Step 1: 创建 AvailableCouponDTO**

```java
package com.example.coupon.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AvailableCouponDTO {
    private String couponCode;
    private String templateName;
    private Integer type;
    private String typeDesc;
    private String discountDesc;
    private LocalDateTime expireTime;
}
```

- [ ] **Step 2: UserCouponService 加方法**

```java
import com.example.coupon.dto.AvailableCouponDTO;
import java.math.BigDecimal;

List<AvailableCouponDTO> listAvailableCoupons(Long userId, Long shopId,
        BigDecimal originalAmount, List<Long> goodsIds);
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`

---

### Task 2: Service 实现

**Files:**
- Modify: `src/main/java/com/example/coupon/service/impl/UserCouponServiceImpl.java`

- [ ] **Step 1: 实现 listAvailableCoupons**

```java
@Override
public List<AvailableCouponDTO> listAvailableCoupons(Long userId, Long shopId,
        BigDecimal originalAmount, List<Long> goodsIds) {
    List<UserCoupon> coupons = baseMapper.selectAvailable(userId, shopId);
    return coupons.stream()
            .map(uc -> toAvailableDTO(uc, originalAmount, goodsIds))
            .filter(dto -> dto != null)
            .toList();
}
```

- [ ] **Step 2: Mapper XML 查询**

在 `src/main/resources/mapper/UserCouponMapper.xml` 中：

```xml
<select id="selectAvailable" resultType="com.example.coupon.entity.UserCoupon">
    SELECT uc.coupon_code, uc.expire_time,
           t.name as template_name, t.type, t.consume_rule, t.goods
    FROM t_user_coupon uc
    JOIN t_coupon_template t ON t.id = uc.template_id
    WHERE uc.user_id = #{userId}
      AND uc.status = 0
      AND uc.expire_time > NOW()
      AND t.shop_number = #{shopId}
      AND t.status = 1
</select>
```

UserCouponMapper.java 加方法：

```java
@Select("SELECT uc.coupon_code, uc.expire_time, t.name as templateName, t.type, t.consume_rule, t.goods " +
        "FROM t_user_coupon uc JOIN t_coupon_template t ON t.id = uc.template_id " +
        "WHERE uc.user_id = #{userId} AND uc.status = 0 AND uc.expire_time > NOW() " +
        "AND t.shop_number = #{shopId} AND t.status = 1")
List<UserCoupon> selectAvailable(@Param("userId") Long userId, @Param("shopId") Long shopId);
```

- [ ] **Step 3: toAvailableDTO 方法**

```java
private AvailableCouponDTO toAvailableDTO(UserCoupon uc, BigDecimal originalAmount,
        List<Long> goodsIds) {
    // 门槛
    JsonNode rule = objectMapper.readTree(uc.getConsumeRule());
    if (uc.getType() == 1) {
        BigDecimal min = new BigDecimal(rule.get("minAmount").asText());
        if (originalAmount.compareTo(min) < 0) return null;
    }
    // 商品范围
    if (!"all".equals(uc.getGoods())) {
        List<Long> allowIds = Arrays.stream(uc.getGoods().split(","))
                .map(Long::valueOf).toList();
        if (goodsIds.stream().noneMatch(allowIds::contains)) return null;
    }
    // 构建 DTO
    AvailableCouponDTO dto = new AvailableCouponDTO();
    dto.setCouponCode(uc.getCouponCode());
    dto.setTemplateName(uc.getTemplateName());
    dto.setType(uc.getType());
    dto.setTypeDesc(getTypeDesc(uc.getType()));
    dto.setDiscountDesc(getDiscountDesc(uc.getType(), rule));
    dto.setExpireTime(uc.getExpireTime());
    return dto;
}

private String getTypeDesc(Integer type) {
    return switch (type) {
        case 1 -> "满减券";
        case 2 -> "折扣券";
        case 3 -> "直降券";
        default -> "未知";
    };
}

private String getDiscountDesc(Integer type, JsonNode rule) {
    return switch (type) {
        case 1 -> "满" + rule.get("minAmount").asText() + "减" + rule.get("discount").asText();
        case 2 -> new BigDecimal(rule.get("discountRate").asText())
                .multiply(BigDecimal.TEN).intValue() + "折";
        case 3 -> "立减" + rule.get("directAmount").asText();
        default -> "";
    };
}
```

UserCoupon entity 需要加 4 个非 DB 字段（通过 `@TableField(exist = false)`）：

```java
@TableField(exist = false)
private String templateName;
@TableField(exist = false)
private String consumeRule;
@TableField(exist = false)
private String goods;
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`

---

### Task 3: Controller

**Files:**
- Modify: `src/main/java/com/example/coupon/controller/UserCouponController.java`

- [ ] **Step 1: 加端点**

```java
@GetMapping("/available")
public Result listAvailableCoupons(@RequestParam Long shopId,
        @RequestParam BigDecimal originalAmount,
        @RequestParam String goodsIds) {
    List<Long> ids = Arrays.stream(goodsIds.split(","))
            .map(Long::valueOf).toList();
    List<AvailableCouponDTO> list = userCouponService.listAvailableCoupons(
            UserContext.getUser().getId(), shopId, originalAmount, ids);
    return Result.success(list);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
