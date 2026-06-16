package com.example.coupon.controller;

import com.example.coupon.common.result.Result;
import com.example.coupon.dto.UserDTO;
import com.example.coupon.entity.User;
import com.example.coupon.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/{id}")
    public Result getUser(@PathVariable Long id) {
        UserDTO user = userService.getUser(id);
        return Result.success(user);
    }

    @GetMapping("/list")
    public Result listUser() {
        List<UserDTO> list = userService.listUser();
        return Result.success(list);
    }

    @PostMapping("/save")
    public Result saveUser(@RequestBody User user) {
        boolean saved = userService.save(user);
        return Result.success(saved);
    }

    @PostMapping("/login")
    public Result login(@RequestParam String username, @RequestParam String password) {
        String token = userService.login(username, password);
        return Result.success(token);
    }
}
