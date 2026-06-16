# 用户信息表 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在数据库中创建 `t_user` 表，并生成对应的 MyBatis-Plus DO 实体类和 Mapper 接口。

**Architecture:** 沿用现有技术栈（Spring Boot + MyBatis-Plus + MySQL），DO 实体类放在 `entity` 包，Mapper 放在 `mapper` 包，风格与现有 `Coupon` 实体保持一致。

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, MySQL 8.0, Lombok

---

### Task 1: 执行 SQL 创建用户表

**Files:**
- 执行 SQL（无文件创建）

- [ ] **Step 1: 执行建表 SQL**

```bash
docker exec -i mysql mysql -uroot -proot -e "
CREATE TABLE IF NOT EXISTS one_coupon_rebuild.t_user (
  id bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  username varchar(64) DEFAULT NULL COMMENT '用户名',
  password varchar(256) DEFAULT NULL COMMENT '密码',
  phone varchar(20) DEFAULT NULL COMMENT '手机号',
  email varchar(64) DEFAULT NULL COMMENT '邮箱',
  nickname varchar(64) DEFAULT NULL COMMENT '昵称',
  avatar varchar(512) DEFAULT NULL COMMENT '头像URL',
  status tinyint(1) DEFAULT '0' COMMENT '用户状态 0：正常 1：禁用',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_time datetime DEFAULT NULL COMMENT '修改时间',
  del_flag tinyint(1) DEFAULT '0' COMMENT '删除标识 0：未删除 1：已删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username) USING BTREE,
  UNIQUE KEY uk_phone (phone) USING BTREE,
  UNIQUE KEY uk_email (email) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';"
```

- [ ] **Step 2: 验证表创建成功**

```bash
docker exec -i mysql mysql -uroot -proot -e "USE one_coupon_rebuild; DESC t_user;"
```

Expected: 输出 11 个字段 + 4 个索引的结构

---

### Task 2: 创建 User DO 实体类

**Files:**
- Create: `src/main/java/com/example/coupon/entity/User.java`

- [ ] **Step 1: 编写 User 实体类**

```java
package com.example.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String phone;

    private String email;

    private String nickname;

    private String avatar;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
```

- [ ] **Step 2: 验证文件创建**

确认文件路径正确，包声明为 `com.example.coupon.entity`。

---

### Task 3: 创建 UserMapper 接口

**Files:**
- Create: `src/main/java/com/example/coupon/mapper/UserMapper.java`

- [ ] **Step 1: 编写 UserMapper**

```java
package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
```

- [ ] **Step 2: 验证文件创建**

确认文件路径正确，包声明为 `com.example.coupon.mapper`。

---

### Task 4: 编译验证

**Files:**
- 无新文件

- [ ] **Step 1: 编译项目**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 确认无报错**

检查编译输出，确认 `User.java` 和 `UserMapper.java` 无编译错误。
