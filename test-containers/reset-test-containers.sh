#!/bin/bash
# 重置所有测试容器到初始状态
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_PASSWORD="root"
MYSQL_DATABASE="one_coupon_rebuild"

echo "=== 重置测试容器 ==="

# 1. 重置 MySQL: 删库 -> 重建 -> 导入初始数据（完整 mysqldump，含 DDL + DML）
echo "[MySQL] 重置中..."
docker exec -i mysql-test mysql -uroot -p"$MYSQL_PASSWORD" -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\`;"
docker exec -i mysql-test mysql -uroot -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < "$SCRIPT_DIR/mysql_test_data.sql"
docker exec -i mysql-test mysql -uroot -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < "$SCRIPT_DIR/mysql_test_schema.sql"
echo "[MySQL] 完成"

# 2. 重置 RabbitMQ: 清空所有队列、交换机、绑定（重置不会影响已安装的插件）
echo "[RabbitMQ] 重置中..."
docker exec rabbitmq-test rabbitmqctl stop_app > /dev/null 2>&1
docker exec rabbitmq-test rabbitmqctl reset > /dev/null 2>&1
docker exec rabbitmq-test rabbitmqctl start_app > /dev/null 2>&1
echo "[RabbitMQ] 完成"

# 3. 重置 Redis: 清空所有数据
echo "[Redis] 重置中..."
docker exec redis-test redis-cli FLUSHALL > /dev/null 2>&1
echo "[Redis] 完成"

echo ""
echo "=== 重置完毕 ==="
echo ""
echo "验证连接:"
echo "  MySQL:    mysql -h 127.0.0.1 -P 3307 -uroot -proot one_coupon_rebuild"
echo "  RabbitMQ: http://localhost:15673"
echo "  Redis:    redis-cli -p 6380"
