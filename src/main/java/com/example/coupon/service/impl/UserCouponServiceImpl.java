package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.enums.CouponTemplateStatusEnum;
import com.example.coupon.common.enums.UserCouponStatusEnum;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.common.util.TokenUtil;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.*;
import com.example.coupon.entity.UserCoupon;
import com.example.coupon.mapper.UserCouponMapper;
import com.example.coupon.service.CouponTemplateService;
import com.example.coupon.service.UserCouponService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.coupon.common.enums.CouponTypeEnum;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements UserCouponService {

    @Autowired
    private CouponTemplateService couponTemplateService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String RECEIVE_LUA_SCRIPT =
            "local usersKey = KEYS[1] " +
            "local stockKey = KEYS[2] " +
            "local userId = ARGV[1] " +
            "if redis.call('SISMEMBER', usersKey, userId) == 1 then " +
            "    return -1 " +
            "end " +
            "local remain = redis.call('DECR', stockKey) " +
            "if remain < 0 then " +
            "    redis.call('INCR', stockKey) " +
            "    return -2 " +
            "end " +
            "redis.call('SADD', usersKey, userId) " +
            "return remain";

    private final DefaultRedisScript<Long> receiveScript = new DefaultRedisScript<>();

    public UserCouponServiceImpl() {
        receiveScript.setScriptText(RECEIVE_LUA_SCRIPT);
        receiveScript.setResultType(Long.class);
    }

    @Override
    public String asyncReceiveCoupon(ReceiveCouponRequestDTO dto, Long userId) {
        log.info("用户 [{}] 开始异步领取优惠券，templateId={}", userId, dto.getTemplateId());

        CouponTemplateDTO template = validateAndGetTemplate(dto.getTemplateId());

        String couponCode = executeLuaAndGetCode(dto.getTemplateId(), userId);

        String messageId = dto.getTemplateId() + ":" + userId + ":" + UUID.randomUUID();
        CouponReceiveMessage message = new CouponReceiveMessage(userId, dto.getTemplateId(), couponCode,
                template.getValidEndTime(), messageId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.COUPON_RECEIVE_EXCHANGE,
                RabbitMQConfig.COUPON_RECEIVE_ROUTING_KEY,
                message);

        log.info("领券异步消息已发送，userId={}，templateId={}，couponCode={}", userId, dto.getTemplateId(), couponCode);
        return couponCode;
    }

    @Override
    @Transactional
    public String syncReceiveCoupon(ReceiveCouponRequestDTO dto, Long userId) {
        log.info("用户 [{}] 开始同步领取优惠券（Redis），templateId={}", userId, dto.getTemplateId());

        CouponTemplateDTO template = validateAndGetTemplate(dto.getTemplateId());

        String couponCode = executeLuaAndGetCode(dto.getTemplateId(), userId);

        try {
            if (!couponTemplateService.deductStock(dto.getTemplateId())) {
                throw new BusinessException("优惠券库存不足");
            }
            UserCoupon uc = new UserCoupon();
            uc.setUserId(userId);
            uc.setTemplateId(dto.getTemplateId());
            uc.setCouponCode(couponCode);
            uc.setStatus(UserCouponStatusEnum.UNUSED.getStatus());
            uc.setReceiveTime(LocalDateTime.now());
            uc.setExpireTime(template.getValidEndTime());
            save(uc);
        } catch (Exception e) {
            String usersKey = String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, dto.getTemplateId());
            String stockKey = String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, dto.getTemplateId());
            redisTemplate.opsForValue().increment(stockKey);
            redisTemplate.opsForSet().remove(usersKey, String.valueOf(userId));
            throw e;
        }

        log.info("同步领取成功（Redis），userId={}，templateId={}，couponCode={}", userId, dto.getTemplateId(), couponCode);
        return couponCode;
    }

    @Override
    @Transactional
    public String receiveCoupon(ReceiveCouponRequestDTO dto, Long userId) {
        log.info("用户 [{}] 开始领取优惠券（纯DB），templateId={}", userId, dto.getTemplateId());

        validateAndGetTemplate(dto.getTemplateId());

        if (!couponTemplateService.deductStock(dto.getTemplateId())) {
            throw new BusinessException("优惠券库存不足");
        }

        String couponCode = TokenUtil.createToken();
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setTemplateId(dto.getTemplateId());
        uc.setCouponCode(couponCode);
        uc.setStatus(UserCouponStatusEnum.UNUSED.getStatus());
        uc.setReceiveTime(LocalDateTime.now());
        try {
            save(uc);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已经领取过该优惠券");
        }

        log.info("领取成功（纯DB），userId={}，templateId={}，couponCode={}", userId, dto.getTemplateId(), couponCode);
        return couponCode;
    }

    private CouponTemplateDTO validateAndGetTemplate(Long templateId) {
        CouponTemplateDTO template = couponTemplateService.getTemplateWithCache(templateId);
        if (template == null) {
            throw new BusinessException("优惠券模板不存在");
        }
        if (template.getStatus() != CouponTemplateStatusEnum.PUBLISHED.getStatus()) {
            throw new BusinessException("优惠券未发布");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getValidStartTime())) {
            throw new BusinessException("优惠券未开始");
        }
        if (now.isAfter(template.getValidEndTime())) {
            throw new BusinessException("优惠券已过期");
        }
        return template;
    }

    private String executeLuaAndGetCode(Long templateId, Long userId) {
        String usersKey = String.format(RedisConstant.COUPON_TEMPLATE_USERS_KEY, templateId);
        String stockKey = String.format(RedisConstant.COUPON_TEMPLATE_STOCK_KEY, templateId);
        Long result = redisTemplate.execute(receiveScript,
                Arrays.asList(usersKey, stockKey),
                String.valueOf(userId));

        if (result == null) {
            log.error("Redis Lua 脚本执行返回 null，templateId={}，userId={}", templateId, userId);
            throw new BusinessException("领取优惠券失败");
        }
        if (result == -1) {
            throw new BusinessException("已经领取过该优惠券");
        }
        if (result == -2) {
            throw new BusinessException("优惠券库存不足");
        }
        log.info("Redis 领券预占成功，templateId={}，userId={}，剩余库存={}", templateId, userId, result);
        return TokenUtil.createToken();
    }

    @Override
    @Transactional
    public int batchReceiveForDistribution(Long templateId, List<Long> userIds, LocalDateTime expireTime) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }

        List<Long> sortedUserIds = userIds.stream().sorted().toList();

        List<UserCoupon> coupons = sortedUserIds.stream().map(userId -> {
            UserCoupon uc = new UserCoupon();
            uc.setUserId(userId);
            uc.setTemplateId(templateId);
            uc.setCouponCode(TokenUtil.createToken());
            uc.setStatus(UserCouponStatusEnum.UNUSED.getStatus());
            uc.setReceiveTime(LocalDateTime.now());
            uc.setExpireTime(expireTime);
            return uc;
        }).toList();

        int inserted = baseMapper.insertIgnoreBatch(coupons);
        log.info("批量发券完成，templateId={}，尝试={}，成功={}", templateId, coupons.size(), inserted);
        return inserted;
    }

    @Override
    @Transactional
    public boolean useCoupon(UseCouponRequestDTO dto, Long userId) {
        log.info("用户 [{}] 尝试使用优惠券，couponCode={}，orderId={}", userId, dto.getCouponCode(), dto.getOrderId());
        int affected = baseMapper.update(null, new UpdateWrapper<UserCoupon>()
                .eq("coupon_code", dto.getCouponCode())
                .eq("user_id", userId)
                .eq("del_flag", 0)
                .eq("status", UserCouponStatusEnum.UNUSED.getStatus())
                .set("status", UserCouponStatusEnum.USED.getStatus())
                .set("use_time", LocalDateTime.now())
                .set("order_id", dto.getOrderId()));
        if (affected == 0) {
            log.warn("用户使用优惠券失败，couponCode={}，userId={}", dto.getCouponCode(), userId);
            throw new BusinessException("优惠券不存在或已使用或已过期");
        }
        log.info("用户 [{}] 使用优惠券成功，couponCode={}，orderId={}", userId, dto.getCouponCode(), dto.getOrderId());
        return true;
    }

    @Override
    public List<UserCouponDTO> listMyCoupons(Long userId) {
        List<UserCoupon> list = list(
                new QueryWrapper<UserCoupon>()
                        .eq("user_id", userId)
                        .eq("del_flag", 0)
                        .orderByDesc("create_time")
        );
        log.info("查询用户 [{}] 优惠券列表，记录数={}", userId, list.size());
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public List<AvailableCouponDTO> listAvailableCoupons(Long userId, Long shopId,
            BigDecimal originalAmount, List<Long> goodsIds) {
        List<UserCoupon> coupons = baseMapper.selectAvailable(userId, shopId);
        return coupons.stream()
                .map(uc -> toAvailableDTO(uc, originalAmount, goodsIds))
                .filter(dto -> dto != null)
                .toList();
    }

    private AvailableCouponDTO toAvailableDTO(UserCoupon uc, BigDecimal originalAmount,
            List<Long> goodsIds) {
        try {
            JsonNode rule = objectMapper.readTree(uc.getConsumeRule());
            // 门槛校验
            if (uc.getType() == CouponTypeEnum.FULL_REDUCTION.getType()) {
                BigDecimal min = new BigDecimal(rule.get("minAmount").asText());
                if (originalAmount.compareTo(min) < 0) return null;
            }
            // 商品范围校验
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
        } catch (Exception e) {
            log.warn("解析优惠券 consumeRule 失败, couponCode={}", uc.getCouponCode(), e);
            return null;
        }
    }

    private String getTypeDesc(Integer type) {
        return Arrays.stream(CouponTypeEnum.values())
                .filter(e -> e.getType() == type)
                .findFirst()
                .map(CouponTypeEnum::getDesc)
                .orElse("未知");
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

    private UserCouponDTO toDTO(UserCoupon userCoupon) {
        UserCouponDTO dto = new UserCouponDTO();
        dto.setId(userCoupon.getId());
        dto.setUserId(userCoupon.getUserId());
        dto.setTemplateId(userCoupon.getTemplateId());
        dto.setCouponCode(userCoupon.getCouponCode());
        dto.setStatus(userCoupon.getStatus());
        dto.setReceiveTime(userCoupon.getReceiveTime());
        dto.setUseTime(userCoupon.getUseTime());
        dto.setExpireTime(userCoupon.getExpireTime());
        return dto;
    }
}
