# 查询可用优惠券接口设计

## 目标

前端下单时，根据商家、订单金额、订单商品，查询当前用户可用的优惠券列表。

## API

```
GET /coupon/available?shopId=1&originalAmount=100&goodsIds=1,2,3
```

## 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| shopId | Long | 是 | 商家 ID |
| originalAmount | BigDecimal | 是 | 订单原始金额 |
| goodsIds | String | 是 | 订单商品 ID，逗号分隔 |

## 响应

```json
{
  "code": 200,
  "data": [
    {
      "couponCode": "abc123",
      "templateName": "双11满减券",
      "type": 1,
      "typeDesc": "满减券",
      "discountDesc": "满100减20",
      "expireTime": "2026-06-01T00:00:00"
    }
  ]
}
```

## 筛选逻辑

**第一步：DB 查询（硬条件）**

```sql
SELECT uc.coupon_code, uc.expire_time,
       t.name, t.type, t.consume_rule, t.goods
FROM t_user_coupon uc
JOIN t_coupon_template t ON t.id = uc.template_id
WHERE uc.user_id = ?
  AND uc.status = 0              -- UNUSED
  AND uc.expire_time > NOW()
  AND t.shop_number = ?          -- shopId
  AND t.status = 1               -- PUBLISHED
```

**第二步：内存过滤**

| 条件 | 规则 |
|------|------|
| 满减门槛 | `consume_rule.minAmount <= originalAmount`（不满足则跳过） |
| 商品范围 | `goods == "all"` 或 `goods` 中包含任一 `goodsIds`（无匹配则跳过） |

## `discountDesc` 生成

| type | consume_rule | desc |
|------|-------------|------|
| 1-满减 | `minAmount:100, discount:20` | `满100减20` |
| 2-折扣 | `discountRate:0.8` | `8折` |
| 3-直降 | `directAmount:50` | `立减50` |
