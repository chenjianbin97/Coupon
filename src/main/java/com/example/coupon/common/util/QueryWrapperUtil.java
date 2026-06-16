package com.example.coupon.common.util;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.coupon.dto.UserQueryConditionDTO;
import com.example.coupon.entity.User;

public class QueryWrapperUtil {

    private QueryWrapperUtil() {
    }

    public static QueryWrapper<User> buildUserQueryWrapper(UserQueryConditionDTO condition) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        if (condition.getShopNumber() != null) {
            wrapper.eq("shop_number", condition.getShopNumber());
        }
        if (condition.getStatus() != null) {
            wrapper.eq("status", condition.getStatus());
        }
        if (condition.getRegisterTimeStart() != null) {
            wrapper.ge("create_time", condition.getRegisterTimeStart());
        }
        if (condition.getRegisterTimeEnd() != null) {
            wrapper.le("create_time", condition.getRegisterTimeEnd());
        }
        if (Boolean.TRUE.equals(condition.getHasPhone())) {
            wrapper.isNotNull("phone");
            wrapper.ne("phone", "");
        }
        if (Boolean.TRUE.equals(condition.getHasEmail())) {
            wrapper.isNotNull("email");
            wrapper.ne("email", "");
        }
        return wrapper;
    }
}
