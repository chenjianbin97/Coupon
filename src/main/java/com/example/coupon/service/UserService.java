package com.example.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.User;

import java.util.List;

public interface UserService extends IService<User> {

    UserDTO getUser(Long id);

    List<UserDTO> listUser();

    String login(String username, String password);
}
