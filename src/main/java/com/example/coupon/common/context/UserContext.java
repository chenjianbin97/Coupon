package com.example.coupon.common.context;

import com.example.coupon.dto.UserDTO;

public class UserContext {

    private static final ThreadLocal<UserDTO> USER_HOLDER = new ThreadLocal<>();

    public static void setUser(UserDTO user) {
        USER_HOLDER.set(user);
    }

    public static UserDTO getUser() {
        return USER_HOLDER.get();
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
