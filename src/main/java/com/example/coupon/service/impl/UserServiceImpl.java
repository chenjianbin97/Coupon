package com.example.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.common.constant.RedisConstant;
import com.example.coupon.common.exception.BusinessException;
import com.example.coupon.common.util.TokenUtil;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.User;
import com.example.coupon.mapper.UserMapper;
import com.example.coupon.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void initUserCache() {
        long start = System.currentTimeMillis();
        List<User> users = list();
        for (User user : users) {
            try {
                String cacheKey = String.format(RedisConstant.USER_CACHE_KEY, user.getUsername());
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(user), 30, TimeUnit.MINUTES);
            } catch (JsonProcessingException e) {
                log.warn("用户缓存预加载失败，username={}", user.getUsername());
            }
        }
        log.info("用户缓存预加载完成，共 {} 条，耗时 {}ms", users.size(), System.currentTimeMillis() - start);
    }

    @Override
    public UserDTO getUser(Long id) {
        User user = getById(id);
        if (user == null) {
            return null;
        }
        return toDTO(user);
    }

    @Override
    public List<UserDTO> listUser() {
        List<User> users = list();
        return users.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public String login(String username, String password) {
        User user = getUser(username);
        if (user == null || !password.equals(user.getPassword())) {
            log.warn("登录失败，用户名或密码错误，username={}", username);
            throw new BusinessException("用户名或密码错误");
        }
        String token = TokenUtil.createToken();
        UserDTO dto = toDTO(user);
        try {
            redisTemplate.opsForValue().set(token, objectMapper.writeValueAsString(dto), 1, TimeUnit.MINUTES);
            log.info("用户登录成功，id={}，username={}", user.getId(), username);
        } catch (JsonProcessingException e) {
            log.error("用户信息序列化失败，userId={}", user.getId(), e);
            throw new BusinessException("用户信息序列化失败");
        }
        return token;
    }

    private User getUser(String username) {
        String cacheKey = String.format(RedisConstant.USER_CACHE_KEY, username);
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, User.class);
            }
        } catch (Exception e) {
            log.warn("用户缓存反序列化失败，key={}", cacheKey, e);
            redisTemplate.delete(cacheKey);
        }
        User user = getOne(new QueryWrapper<User>().eq("username", username));
        if (user != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(user), 30, TimeUnit.MINUTES);
            } catch (JsonProcessingException e) {
                log.warn("用户缓存序列化失败，username={}", username);
            }
        }
        return user;
    }

    private UserDTO toDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setPhone(user.getPhone());
        dto.setEmail(user.getEmail());
        dto.setNickname(user.getNickname());
        dto.setAvatar(user.getAvatar());
        dto.setStatus(user.getStatus());
        dto.setShopNumber(user.getShopNumber());
        return dto;
    }
}
