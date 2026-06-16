# Coupon — 优惠券平台

基于 Spring Boot 3.2 的高并发优惠券系统，支持领券、批量分发、订阅通知、下单用券和超时回滚。

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.2.12 |
| MyBatis-Plus | 3.5.7 |
| MySQL | 8 |
| Redis | 7 |
| RabbitMQ | 3 |
| Redisson | 3.27.2 |

## 功能

- **用户管理** — 用户注册与查询
- **优惠券模板** — 创建、查询、上下架
- **领券** — 高并发领券，Redis Lua 原子预分配 + MQ 异步持久化
- **下单用券** — 下单时锁定优惠券，支持超时自动回滚
- **订阅通知** — 用户订阅后优惠券到账推送（短信 / App Push / 日志）
- **接口限流** — 注解式限流

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Docker

### 启动基础设施

```bash
docker run -d --name mysql-test \
  -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=one_coupon_rebuild \
  mysql:8

docker run -d --name redis-test \
  -p 6380:6379 \
  redis:7

docker run -d --name rabbitmq-test \
  -p 5673:5672 -p 15673:15672 \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  rabbitmq:3-management
```

### 初始化数据库

容器启动后，导入项目自带的表结构和初始数据：

```bash
docker exec -i mysql-test mysql -uroot -proot one_coupon_rebuild < src/main/resources/schema-notify.sql
docker exec -i mysql-test mysql -uroot -proot one_coupon_rebuild < src/main/resources/db/data-notify-templates.sql
```

> 注：MyBatis-Plus 会自动创建业务表，但通知模块的表和模板数据需要手动导入。

### 启动应用

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 项目结构

```
src/main/java/com/example/coupon/
├── controller/     # REST 接口
├── service/impl/   # 业务逻辑
├── service/notify/ # 通知引擎（SMS / Push / Log）
├── mapper/         # MyBatis-Plus Mapper
├── entity/         # 数据库实体
├── dto/            # 数据传输对象
├── listener/       # RabbitMQ 消费者
├── common/         # 异常、枚举、拦截器、注解
└── config/         # Spring 配置
```

