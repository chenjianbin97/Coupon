package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.context.UserContext;
import com.example.coupon.common.enums.CouponTemplateStatusEnum;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.dto.CouponTemplateDTO;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.dto.CouponTemplatePageRequestDTO;
import com.example.coupon.dto.CouponTemplateSaveRequestDTO;
import com.example.coupon.entity.Coupon;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.mapper.CouponTemplateMapper;
import com.example.coupon.service.CouponTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CouponTemplateServiceImpl extends ServiceImpl<CouponTemplateMapper, Coupon> implements CouponTemplateService {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private RBloomFilter<Long> bloomFilter;

    private static final long REQUEST_LOCK_TTL_SECONDS = 5;
    private static final long USER_LOCK_TTL_SECONDS = 3;

    @PostConstruct
    public void initBloomFilter() {
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(RedisConstant.BLOOM_TEMPLATE_KEY);
        filter.delete();
        filter.tryInit(200000L, 0.01);

        List<Coupon> templates = list();
        for (Coupon c : templates) {
            filter.add(c.getId());
            String stockKey = String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, c.getId());
            long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(), c.getValidEndTime());
            if (ttl > 0) {
                redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(c.getStock()), ttl, TimeUnit.SECONDS);
            }
        }
        this.bloomFilter = filter;
        log.info("布隆过滤器初始化完成，已加载 {} 个模板", templates.size());
    }

    private void validateShopAccess(Long templateShopNumber) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BusinessException("用户未登录");
        }
        if (user.getShopNumber() != null && !user.getShopNumber().equals(templateShopNumber)) {
            log.warn("用户 [{}] 无权访问商家 [{}] 的优惠券", user.getId(), templateShopNumber);
            throw new BusinessException("无权访问该优惠券");
        }
    }

    @Override
    public boolean saveTemplate(CouponTemplateSaveRequestDTO dto, Long userId, String idempotentToken) {
        // 第一层：请求级锁（防网络重试）
        if (idempotentToken != null && !idempotentToken.isBlank()) {
            String requestKey = String.format(RedisConstant.IDEMPOTENT_KEY, RedisConstant.SAVE_TEMPLATE_SCENE, idempotentToken);
            Boolean requestLocked = redisTemplate.opsForValue()
                    .setIfAbsent(requestKey, "1", REQUEST_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(requestLocked)) {
                throw new BusinessException("请勿重复提交");
            }
        }

        // 第二层：用户级锁（防连点/短间隔刷接口）
        String userKey = String.format(RedisConstant.USER_LOCK_KEY, userId, RedisConstant.SAVE_TEMPLATE_SCENE);
        Boolean userLocked = redisTemplate.opsForValue()
                .setIfAbsent(userKey, "1", USER_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(userLocked)) {
            throw new BusinessException("操作过于频繁，请稍后再试");
        }

        validateSaveRequest(dto);
        Coupon coupon = new Coupon();
        BeanUtils.copyProperties(dto, coupon);
        coupon.setStatus(CouponTemplateStatusEnum.DRAFT.getStatus());
        coupon.setCreateTime(LocalDateTime.now());
        coupon.setUpdateTime(LocalDateTime.now());
        boolean result = save(coupon);
        bloomAdd(coupon.getId());
        log.info("优惠券模板创建成功，id={}，name={}，shopNumber={}", coupon.getId(), coupon.getName(), coupon.getShopNumber());
        return result;
    }

    private void validateSaveRequest(CouponTemplateSaveRequestDTO dto) {
        if (dto.getValidEndTime().isBefore(dto.getValidStartTime()) || dto.getValidEndTime().isEqual(dto.getValidStartTime())) {
            throw new BusinessException("有效期结束时间必须晚于开始时间");
        }
        if (dto.getValidStartTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("有效期开始时间不能早于当前时间");
        }

        JsonNode receiveNode;
        try {
            receiveNode = objectMapper.readTree(dto.getReceiveRule());
        } catch (Exception e) {
            throw new BusinessException("领取规则不是合法的JSON格式");
        }
        if (!receiveNode.has("limit") || !receiveNode.get("limit").isInt() || receiveNode.get("limit").asInt() <= 0) {
            throw new BusinessException("领取规则必须包含正整数 limit 字段");
        }

        JsonNode consumeNode;
        try {
            consumeNode = objectMapper.readTree(dto.getConsumeRule());
        } catch (Exception e) {
            throw new BusinessException("消费规则不是合法的JSON格式");
        }

        switch (dto.getType()) {
            case 1:
                if (!consumeNode.has("minAmount") || !consumeNode.get("minAmount").isNumber() || consumeNode.get("minAmount").asDouble() <= 0) {
                    throw new BusinessException("满减券消费规则必须包含正数 minAmount 字段");
                }
                if (!consumeNode.has("discount") || !consumeNode.get("discount").isNumber() || consumeNode.get("discount").asDouble() <= 0) {
                    throw new BusinessException("满减券消费规则必须包含正数 discount 字段");
                }
                break;
            case 2:
                if (!consumeNode.has("discountRate") || !consumeNode.get("discountRate").isNumber()) {
                    throw new BusinessException("折扣券消费规则必须包含 discountRate 字段");
                }
                double rate = consumeNode.get("discountRate").asDouble();
                if (rate <= 0 || rate > 1) {
                    throw new BusinessException("折扣率必须在 0 到 1 之间");
                }
                break;
            case 3:
                if (!consumeNode.has("directAmount") || !consumeNode.get("directAmount").isNumber() || consumeNode.get("directAmount").asDouble() <= 0) {
                    throw new BusinessException("直降券消费规则必须包含正数 directAmount 字段");
                }
                break;
            default:
                throw new BusinessException("优惠券类型无效");
        }
    }

    @Override
    public CouponTemplateDTO getTemplate(Long id) {
        Coupon coupon = getById(id);
        if (coupon == null) {
            log.warn("优惠券模板不存在，id={}", id);
            return null;
        }
        validateShopAccess(coupon.getShopNumber());
        log.info("查询优惠券模板成功，id={}，name={}", id, coupon.getName());
        return toDTO(coupon);
    }

    @Override
    public CouponTemplateDTO getTemplateWithCache(Long id) {
        // 1. 布隆过滤器：一定不存在则直接返回
        if (!bloomFilter.contains(id)) {
            return null;
        }

        // 2. 查 Redis
        CouponTemplateDTO cached = getTemplateFromCache(id);
        if (cached != null) {
            return cached;
        }

        // 3. 按 ID 加分布式锁，防缓存击穿
        RLock lock = redissonClient.getLock(String.format(RedisConstant.LOCK_TEMPLATE_KEY, id));
        lock.lock();
        try {
            // 4. Double-check
            cached = getTemplateFromCache(id);
            if (cached != null) {
                return cached;
            }

            // 5. 查 DB
            Coupon coupon = getById(id);
            if (coupon == null) {
                log.warn("优惠券模板不存在，id={}", id);
                return null;
            }
            validateShopAccess(coupon.getShopNumber());
            CouponTemplateDTO dto = toDTO(coupon);
            cacheTemplateDto(dto);
            log.info("查询优惠券模板成功（已缓存），id={}，name={}", id, coupon.getName());
            return dto;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<CouponTemplateDTO> pageTemplate(CouponTemplatePageRequestDTO dto) {
        Page<Coupon> page = new Page<>(dto.getPage(), dto.getSize());
        QueryWrapper<Coupon> wrapper = new QueryWrapper<>();
        if (dto.getName() != null && !dto.getName().isEmpty()) {
            wrapper.like("name", dto.getName());
        }
        if (dto.getStatus() != null) {
            wrapper.eq("status", dto.getStatus());
        }
        UserDTO user = UserContext.getUser();
        if (user != null && user.getShopNumber() != null) {
            wrapper.eq("shop_number", user.getShopNumber());
        } else if (dto.getShopNumber() != null) {
            wrapper.eq("shop_number", dto.getShopNumber());
        }
        page(page, wrapper);
        return page.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public boolean updateTemplate(CouponTemplateDTO dto) {
        Coupon existing = getById(dto.getId());
        if (existing == null) {
            log.warn("更新失败，优惠券模板不存在，id={}", dto.getId());
            throw new BusinessException("优惠券模板不存在");
        }
        validateShopAccess(existing.getShopNumber());
        Coupon coupon = toEntity(dto);
        coupon.setUpdateTime(LocalDateTime.now());
        boolean updated = updateById(coupon);
        if (updated) {
            invalidateTemplateCache(coupon.getId());
            log.info("优惠券模板更新成功，id={}", dto.getId());
        }
        return updated;
    }

    @Override
    public boolean deleteTemplate(Long id) {
        Coupon existing = getById(id);
        if (existing != null) {
            validateShopAccess(existing.getShopNumber());
        }
        boolean deleted = removeById(id);
        if (deleted) {
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, id));
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, id));
            invalidateTemplateCache(id);
            log.info("优惠券模板删除成功，id={}", id);
        }
        return deleted;
    }

    @Override
    public boolean publish(Long id) {
        Coupon coupon = getById(id);
        if (coupon == null) {
            log.warn("发布失败，优惠券模板不存在，id={}", id);
            throw new BusinessException("优惠券模板不存在");
        }
        validateShopAccess(coupon.getShopNumber());
        if (coupon.getStock() <= 0) {
            log.warn("发布失败，优惠券库存不足，id={}，stock={}", id, coupon.getStock());
            throw new BusinessException("优惠券库存不足，无法发布");
        }
        boolean updated = lambdaUpdate()
                .eq(Coupon::getId, id)
                .eq(Coupon::getStatus, CouponTemplateStatusEnum.DRAFT.getStatus())
                .set(Coupon::getStatus, CouponTemplateStatusEnum.PUBLISHED.getStatus())
                .set(Coupon::getUpdateTime, LocalDateTime.now())
                .update();
        if (updated) {
            coupon.setStatus(CouponTemplateStatusEnum.PUBLISHED.getStatus());
            coupon.setUpdateTime(LocalDateTime.now());
            bloomAdd(coupon.getId());
            cacheTemplate(coupon);
            cacheTemplateDto(toDTO(coupon));
            log.info("优惠券模板发布成功，id={}，name={}", id, coupon.getName());
        }
        return updated;
    }

    private void cacheTemplate(Coupon coupon) {
        long expireSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), coupon.getValidEndTime());
        if (expireSeconds <= 0) {
            expireSeconds = 1;
        }
        String stockKey = String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, coupon.getId());
        String usersKey = String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, coupon.getId());
        redisTemplate.delete(usersKey);
        redisTemplate.opsForValue().set(stockKey, String.valueOf(coupon.getStock()), expireSeconds, TimeUnit.SECONDS);
    }

    private CouponTemplateDTO getTemplateFromCache(Long id) {
        String json = redisTemplate.opsForValue().get(String.format(RedisConstant.COUPON_TEMPLATE_KEY, id));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CouponTemplateDTO.class);
        } catch (Exception e) {
            log.warn("模板缓存反序列化失败，id={}", id, e);
            redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_KEY, id));
            return null;
        }
    }

    private void cacheTemplateDto(CouponTemplateDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            long expireSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), dto.getValidEndTime());
            if (expireSeconds <= 0) {
                expireSeconds = 60;
            }
            redisTemplate.opsForValue().set(
                    String.format(RedisConstant.COUPON_TEMPLATE_KEY, dto.getId()),
                    json, expireSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("模板缓存序列化失败，id={}", dto.getId(), e);
        }
    }

    private void bloomAdd(Long id) {
        bloomFilter.add(id);
    }

    private void invalidateTemplateCache(Long id) {
        redisTemplate.delete(String.format(RedisConstant.COUPON_TEMPLATE_KEY, id));
    }

    @Override
    public boolean deductStock(Long id) {
        int affected = baseMapper.update(null, new UpdateWrapper<Coupon>()
                .eq("id", id)
                .eq("status", CouponTemplateStatusEnum.PUBLISHED.getStatus())
                .gt("stock", 0)
                .setSql("stock = stock - 1"));
        if (affected > 0) {
            log.info("优惠券库存扣减成功，id={}", id);
        } else {
            log.warn("优惠券库存扣减失败，id={}", id);
        }
        return affected > 0;
    }

    private CouponTemplateDTO toDTO(Coupon coupon) {
        CouponTemplateDTO dto = new CouponTemplateDTO();
        BeanUtils.copyProperties(coupon, dto);
        return dto;
    }

    private Coupon toEntity(CouponTemplateDTO dto) {
        Coupon coupon = new Coupon();
        BeanUtils.copyProperties(dto, coupon);
        return coupon;
    }
}
