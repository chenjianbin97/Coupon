# Coupon 模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建优惠券模板管理和用户优惠券业务操作的完整分层架构。

**Architecture:** 沿用现有 User 模块风格，模板层负责 CRUD 和库存管理，用户券层负责领取/核销业务，两张表通过 template_id 关联。

**Tech Stack:** Java 17, Spring Boot 3.2.12, MyBatis-Plus 3.5.7, MySQL 8.0, Redis

---

### Task 1: 创建 t_user_coupon 表

**Files:**
- 执行 SQL

- [ ] **Step 1: 执行建表 SQL**

```bash
docker exec -i mysql mysql -uroot -proot -e "
CREATE TABLE IF NOT EXISTS one_coupon_rebuild.t_user_coupon (
  id bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id bigint(20) NOT NULL COMMENT '用户ID',
  template_id bigint(20) NOT NULL COMMENT '优惠券模板ID',
  coupon_code varchar(64) NOT NULL COMMENT '券码',
  status tinyint(1) DEFAULT '0' COMMENT '状态 0：未使用 1：已使用 2：已过期',
  receive_time datetime DEFAULT NULL COMMENT '领取时间',
  use_time datetime DEFAULT NULL COMMENT '使用时间',
  order_id bigint(20) DEFAULT NULL COMMENT '订单ID',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_time datetime DEFAULT NULL COMMENT '修改时间',
  del_flag tinyint(1) DEFAULT '0' COMMENT '删除标识',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_code (coupon_code) USING BTREE,
  KEY idx_user_id (user_id) USING BTREE,
  KEY idx_template_id (template_id) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券表';"
```

- [ ] **Step 2: 验证表创建**

```bash
docker exec -i mysql mysql -uroot -proot -e "USE one_coupon_rebuild; DESC t_user_coupon;"
```

---

### Task 2: 创建 DTO 和实体类

**Files:**
- Create: `src/main/java/com/example/coupon/dto/CouponTemplateDTO.java`
- Create: `src/main/java/com/example/coupon/dto/CouponTemplatePageDTO.java`
- Create: `src/main/java/com/example/coupon/dto/UserCouponDTO.java`
- Create: `src/main/java/com/example/coupon/dto/ReceiveCouponDTO.java`
- Create: `src/main/java/com/example/coupon/dto/UseCouponDTO.java`
- Create: `src/main/java/com/example/coupon/entity/UserCoupon.java`

---

### Task 3: 创建 Mapper

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/CouponTemplateMapper.java`
- Create: `src/main/java/com/example/coupon/mapper/UserCouponMapper.java`

---

### Task 4: 创建 Service 接口和实现

**Files:**
- Create: `src/main/java/com/example/coupon/service/CouponTemplateService.java`
- Create: `src/main/java/com/example/coupon/service/impl/CouponTemplateServiceImpl.java`
- Create: `src/main/java/com/example/coupon/service/UserCouponService.java`
- Create: `src/main/java/com/example/coupon/service/impl/UserCouponServiceImpl.java`

---

### Task 5: 创建 Controller

**Files:**
- Create: `src/main/java/com/example/coupon/controller/CouponTemplateController.java`
- Create: `src/main/java/com/example/coupon/controller/UserCouponController.java`

---

### Task 6: 编译验证

**Files：**
- 无新文件

- [ ] **Step 1: 编译**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS
