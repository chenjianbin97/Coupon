# Log Troubleshoot Skill — Design Spec

## Overview

A Claude Code skill that helps developers troubleshoot issues in the Coupon project
by reverse-tracing log output across the async chain (Redis → MQ → DB).

## Scope

### Included

- 3 business links:
  1. `receive-coupon` — user receives a coupon (async: Redis Lua → MQ → DB)
  2. `distribute-coupon` — platform bulk distribution (batch async: MQ → INSERT IGNORE)
  3. `use-coupon` — order submit / pay / cancel (sync + delayed MQ)

### Excluded (future iteration)

- Performance profiling (timing analysis)
- Live state inspection (reading Redis keys, DB rows directly)
- Dead-letter queue management
- Links 4-6 (order-timeout, subscribe-template, create-template)

## Structure

### Part 1: Link Stage Definitions

Each link is a YAML-like ordered list of stages. Each stage defines:
- `stage`: short key (e.g., `redis-lua`)
- `logMatcher`: regex or keyword to locate this stage in logs
- `success`: log patterns indicating the stage succeeded
- `failure`: log patterns indicating the stage failed, with error classification
- `correlationKeys`: fields that connect this stage to adjacent stages (e.g., userId, templateId, msgId)

### Part 2: Common Cross-Link Patterns

Shared patterns that appear across multiple links:
- Redis Lua return codes (-1, -2, null, >=0)
- MQ idempotency (SETNX pass/skip, delete-on-failure)
- DB exceptions (DuplicateKeyException = idempotent success, others = real failure)
- Cache invalidation on mutation

### Part 3: Troubleshoot Workflow

The skill's interaction model:

1. **Parse the problem** — extract userId, templateId, couponCode, orderNo, taskId, etc. from user description
2. **Identify the link** — match keywords ("领券"/"receive", "分发"/"distribute", "核销"/"下单"/"use") to a link
3. **Reverse-trace** — start from the last stage and walk backward:

   ```
   Stage N (final state) → found? → YES: problem is later (check DB/cache directly)
                                 → NO: go to Stage N-1
   Stage N-1 → found? → YES: broken between N-1 and N. Root cause located.
                      → NO: continue backward...
   ```

4. **Report** — pinpoint which stage broke, show the log snippet, classify the error, suggest root cause
5. **Optionally** — suggest next actions (check specific Redis key, query DB, inspect MQ queue)

### Part 4: Log Search Commands

Pre-built grep templates organized by stage, with placeholder substitution.

## Link Definitions

### Link 1: `receive-coupon` (用户领券)

```
stages:
  - stage: template-validate
    logMatcher: "用户 [{userId}] 开始异步领取优惠券，templateId={templateId}"
    success: log exists
    failure: log missing → request never reached service (check interceptor/auth)
    correlationKeys: [userId, templateId]

  - stage: redis-lua
    logMatcher: "Redis 领券预占成功，templateId={templateId}，userId={userId}，剩余库存={remain}"
    success: log exists (Lua returned >= 0)
    failure:
      - pattern: "已经领取过该优惠券" → Lua returned -1 (already in SISMEMBER set)
      - pattern: "优惠券库存不足" + no "预占成功" → Lua returned -2 (stock exhausted)
      - pattern: "Redis Lua 脚本执行返回 null" → Redis connectivity or script error
    correlationKeys: [userId, templateId]

  - stage: mq-send
    logMatcher: "领券异步消息已发送，userId={userId}，templateId={templateId}，couponCode={couponCode}"
    success: log exists
    failure: log missing after redis-lua success → RabbitMQ connection issue or exception
    correlationKeys: [userId, templateId, couponCode]

  - stage: mq-consume
    logMatcher: "处理领券消息，userId={userId}，templateId={templateId}，messageId={messageId}"
    success: log exists
    failure:
      - pattern: "消息已处理，跳过" → SETNX returned false (duplicate delivery, idempotent)
      - pattern: log missing → message not delivered (check queue backlog, consumer down)
    correlationKeys: [userId, templateId, messageId]

  - stage: db-persist
    logMatcher: "领券异步处理完成，userId={userId}，templateId={templateId}"
    success: log exists
    failure:
      - pattern: "优惠券库存不足" → DB stock <= 0 (deductStock returned false)
      - pattern: DuplicateKeyException → user+template unique index conflict (idempotent, treated as success)
      - pattern: other exception → log.error + delete msgKey + rethrow for MQ retry
    correlationKeys: [userId, templateId]
```

### Link 2: `distribute-coupon` (平台分发)

```
stages:
  - stage: task-create
    logMatcher: "创建分发任务成功，taskId={taskId}，templateId={templateId}，totalCount={totalCount}，chunkCount={chunkCount}，operatorId={operatorId}"
    success: log exists
    failure:
      - pattern: "优惠券模板不存在" / "优惠券未发布" → template validation failed
      - pattern: "用户列表序列化失败" → input data issue
    correlationKeys: [taskId, templateId, operatorId]

  - stage: chunk-dispatch
    logMatcher: (MQ send per chunk, no explicit log per chunk currently)
    success: inferred from task-create success + mq-consume receiving messages
    failure: if some chunks never consumed → check RabbitMQ queue depth

  - stage: mq-consume
    logMatcher: "收到任务执行消息，taskId={taskId}，templateId={templateId}，userCount={userCount}，messageId={messageId}"
    success: log exists
    failure:
      - pattern: "消息已处理，跳过" → SETNX duplicate (re-delivery, idempotent)
      - pattern: "任务状态异常，已终止推送" → task already completed/failed/removed
      - pattern: log missing → queue backlog or consumer down

  - stage: chunk-process
    logMatcher: (processChunk — success/fail per user tracked via detail records, no aggregate log)
    success: processed_count tracked in DB (CouponDistributionTask)
    failure:
      - pattern: "任务执行异常，taskId={taskId}，error={error}" → transaction failed, msgKey deleted, task marked FAILED
      - pattern: "失败明细已存在，跳过" → DuplicateKeyException on fail details (idempotent)
    correlationKeys: [taskId, messageId]

  - stage: task-complete
    logMatcher: "任务执行完成，taskId={taskId}"
    success: log exists → all chunks processed, processedCount >= totalCount
    failure: log missing → some chunks still pending or task failed
    correlationKeys: [taskId]
```

### Link 3: `use-coupon` (下单核销)

```
stages:
  - stage: order-submit
    logMatcher: "订单创建成功，orderNo={orderNo}，userId={userId}，amount={amount}"
    success: log exists
    failure:
      - pattern: "订单金额校验失败" → amount mismatch
      - pattern: "优惠券不存在或已使用" → coupon invalid
      - pattern: "优惠券已过期" → expired
      - pattern: "优惠券模板不存在" → template deleted
      - pattern: "优惠券不属于该商家" → shop mismatch
      - pattern: "未达到最低消费金额" → min amount not met
      - pattern: "优惠券已被使用或已过期" → atomic lock failed (race condition or already locked)
      - pattern: "优惠券规则解析失败" → malformed consumeRule JSON
    correlationKeys: [orderNo, userId, couponCode]

  - stage: timeout-msg-send
    logMatcher: "已发送超时取消延迟消息，orderNo={orderNo}"
    success: log exists (15min delayed message sent)
    failure: log missing → transaction afterCommit hook failed or RabbitMQ delay plugin issue
    correlationKeys: [orderNo]

  - stage: payment
    logMatcher: "支付成功，orderNo={orderNo}，userId={userId}"
    success: log exists
    failure:
      - pattern: "订单不存在" → wrong orderNo/userId
      - pattern: "订单状态不正确" → already paid/cancelled (concurrent operation)
    correlationKeys: [orderNo, userId]

  - stage: coupon-used
    success: inferred from payment success — LOCKED coupon updated to USED
    failure: if payment succeeded but coupon status not updated → check DB directly

  - stage: cancel (alternative path)
    logMatcher: "取消订单成功，orderNo={orderNo}，userId={userId}"
    success: log exists — coupon restored to UNUSED
    failure: "订单状态不正确" → already paid (cannot cancel after payment)
    correlationKeys: [orderNo, userId]
```

## Troubleshoot Workflow (Detailed)

### Step 1: Parse

Extract identifiers from user's natural-language description:

| User says | Extract as |
|-----------|-----------|
| "用户123" / "userId=123" | userId = 123 |
| "模板5" / "templateId=5" | templateId = 5 |
| "券码ABC" | couponCode = "ABC" |
| "订单号XYZ" | orderNo = "XYZ" |
| "任务42" | taskId = 42 |

### Step 2: Classify

Match the user's description to a link:

| Keywords | Link |
|----------|------|
| 领券/领取/receive/没看到券/没收到券 | `receive-coupon` |
| 分发/派发/distribute/批量/任务 | `distribute-coupon` |
| 核销/下单/支付/取消/用券/use/order/submit/pay/cancel | `use-coupon` |

If ambiguous, ask: "这是用户自己领券、平台批量分发、还是下单核销？"

### Step 3: Reverse Trace

Start from the LAST stage, query backward:

```
1. Search log for last stage success pattern
   → Found → the link completed successfully; problem may be in cache visibility or client-side
   → Not found → go to previous stage

2. Search log for stage N-1 success pattern
   → Found → breakage is between N-1 and N. Report root cause.
   → Not found → continue backward...

3. Continue until reaching the earliest stage or finding the break.
```

### Step 4: Report

```
## 排查结果

**链路**: receive-coupon (用户领券)
**断点**: mq-consume 阶段未找到日志
**结论**: Redis 预分配成功，MQ 消息已发送，但消费者未处理

**建议排查**:
1. 检查 RabbitMQ 队列 `coupon.receive.queue` 是否有堆积
2. 检查消费者实例是否存活
3. 检查是否有消息被路由到死信队列
```

<group_annotation>
The report section is illustrative — actual output format will be defined during implementation.
</group_annotation>

## Common Cross-Link Patterns

### Redis Lua Return Codes

| Lua Result | Meaning | Expected Behavior |
|-----------|---------|-------------------|
| >= 0 | Success, remaining stock | Pre-allocate complete |
| -1 | SISMEMBER hit — user already in set | `BusinessException("已经领取过该优惠券")` |
| -2 | DECR < 0 — stock exhausted | `BusinessException("优惠券库存不足")` |
| null | Redis error or script error | `BusinessException("领取优惠券失败")` |

### MQ Idempotency (SETNX)

| Pattern | Meaning |
|---------|---------|
| SETNX returns true → process | First delivery, proceed |
| SETNX returns false → `log.info("消息已处理，跳过")` | Duplicate delivery, skip safely |
| Exception caught, `redisTemplate.delete(msgKey)` | Delete key so retry can re-process |
| `DuplicateKeyException` caught | Idempotent success (DB unique index wins) |

### DB Error Classification

| Exception | Meaning | Action |
|-----------|---------|--------|
| `DuplicateKeyException` | Unique index conflict (uk_user_template, uk_task_user) | Treat as success (idempotent) |
| Other `RuntimeException` / checked | Real failure | Log error, clean up idempotency key, rethrow for MQ retry |

## File: Skill Location

`skills/coupon-log-troubleshoot/skill.md` — the skill definition file.

## Key Design Decisions

1. **Reverse-trace, not forward-trace.** When a user says "I don't see the coupon," the final state is what's wrong — start there and walk backward. Forward-tracing would needlessly inspect every stage.

2. **Log-based, not live-state-based.** The skill uses log patterns to diagnose, not live Redis/DB queries. This keeps it safe (read-only) and works with historical issues. Live state inspection is a future enhancement.

3. **Declarative stage definitions.** New links can be added by declaring their stages. Each stage is self-contained and only couples via correlation keys.

4. **One skill file, not a directory of scripts.** The skill is a conversation guide — it tells the agent *how* to reason about logs, not just what commands to run.
