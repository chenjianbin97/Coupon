# Coupon — 优惠券平台

Spring Boot 3.2 / Java 17 / MyBatis-Plus 3.5.7 / MySQL 8 / Redis 7 / RabbitMQ 3 (Docker)。

## 命令

- `mvn spring-boot:run -Dspring-boot.run.profiles=dev` - 启动测试环境
- `mvn test` — 运行全部测试
- `mvn test -Dtest=XxxTest` — 运行单个测试类

## 架构

```
src/main/java/com/example/coupon/
├── controller/     # REST 接口 (薄层，委托 Service)
├── service/impl/   # 业务逻辑
├── mapper/         # MyBatis-Plus Mapper
├── entity/         # DB 实体 (不跨层暴露)
├── dto/            # 数据传输对象 (含 MQ 消息体)
├── listener/       # RabbitMQ 消费者
├── common/         # 注解、枚举、异常、拦截器、工具类
└── config/         # Spring 配置
```

## 分层约定

- Controller ↔ Service 只通过 DTO，Entity 不跨边界
- Service 调 Service，不跨模块调 Mapper
- `@Valid` 在 Controller 校验格式；业务校验在 Service
- `BusinessException` → 400；`RuntimeException` → 500
- `@Transactional` 仅放 public 入口方法（private 绕过 AOP 代理）

## 关键规则

### 并发：用原子操作，禁止 check-then-act

- **库存扣减 / 券使用** — `UPDATE ... WHERE`，WHERE 子句即锁。**禁止 SELECT 再 UPDATE。**
- **Redis 复合操作** — Lua 脚本 (`SISMEMBER`+`DECR`+`SADD`) 一步完成，Lua 返回码 (-1/-2/≥0) 驱动分支。**禁止应用层先查后写。**
- **批量发券** — `INSERT IGNORE`，唯一索引冲突静默跳过。**禁止先查重再插入。**
- **热点 key 加载** — `ReentrantLock` per ID + `ConcurrentHashMap` + double-check；多实例用 Redisson `RLock`。

### 异步：同步预分配，异步持久化

- 领券路径：校验 → Lua 原子预分配 → 发 MQ → 立即返回。消费者做 DB 写（扣库存+插记录）。
- 分发任务按固定大小分块，每块一条 MQ，多消费者并行。
- 消费者内分页用 while 循环。**禁止逐页发 MQ 链式调用。**

### 幂等：三层防护

| 层 | 机制 |
|---|---|
| 用户操作 | `SETNX` per request token + per user+scene，短 TTL |
| MQ 消息 | `SETNX` per messageId；失败时删 key 允许重试 |
| DB | 唯一索引 `uk_user_template` / `uk_task_user` 最终兜底 |

消费者异常处理：`DuplicateKeyException` → 视为成功（幂等）；其他异常 → 删幂等 key → 抛出任 MQ 重试。

## 枚举与日志

- 状态用枚举的 `getStatus()` 比较，**禁止魔法数字 `== 0/1/2`**
- 日志级别：INFO=业务里程碑，WARN=预期业务失败，ERROR=非预期系统异常
- 参数化日志 `log.info("done, id={}", id)`；**禁止记录凭据/token**

## 测试

- 采用jmeter进行测试前，必须按照流程：关闭应用程序->重置测试容器->启动应用程序->跑测试文件（jmx） 

## Agent 行为

- 先确认范围再动手，不扩大任务
- 最小改动：只改任务要求的，不做顺手重构
- 多种方案时先讨论设计取舍，再写代码
- 删代码优先于标记废弃
