package com.example.coupon.common.util;

import java.util.UUID;

public class TokenUtil {

    public static String createToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
