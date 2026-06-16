package com.example.coupon.common.constant;

public class RedisConstant {

    private RedisConstant() {
    }

    public static final String COUPON_TEMPLATE_KEY = "coupon:template:%s";
    public static final String COUPON_TEMPLATE_STOCK_KEY = "coupon:template:%s:stock";
    public static final String COUPON_TEMPLATE_USERS_KEY = "coupon:template:%s:users";

    public static final String BLOOM_TEMPLATE_KEY = "bloom:template";
    public static final String LOCK_TEMPLATE_KEY = "lock:template:%s";

    public static final String IDEMPOTENT_MSG_KEY = "idempotent:msg:%s";

    public static final String IDEMPOTENT_KEY = "idempotent:%s:%s";

    public static final String USER_LOCK_KEY = "lock:user:%s:scene:%s";

    public static final String SAVE_TEMPLATE_SCENE = "saveCouponTemplate";

    public static final String RATELIMIT_KEY = "rateLimit:%s:%s";

    public static final String FREQUENCY_KEY = "freq:%d:userId:%d";

    public static final String CHANNEL_HEALTH_KEY = "channel:health:%d";

    public static final String IDEMPOTENT_NOTIFY_KEY = "idempotent:notify:%s";

    public static final String USER_CACHE_KEY = "user:cache:%s";
}
