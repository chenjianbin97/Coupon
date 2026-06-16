package com.example.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.coupon.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    @Insert("<script>" +
            "INSERT IGNORE INTO t_user_coupon (user_id, template_id, coupon_code, status, receive_time, expire_time) VALUES " +
            "<foreach collection='list' item='c' separator=','>" +
            "(#{c.userId}, #{c.templateId}, #{c.couponCode}, 0, NOW(), #{c.expireTime})" +
            "</foreach>" +
            "</script>")
    int insertIgnoreBatch(@Param("list") List<UserCoupon> coupons);

    @Select("SELECT uc.coupon_code, uc.expire_time, t.name as templateName, t.type, t.consume_rule, t.goods " +
            "FROM t_user_coupon uc JOIN t_coupon_template t ON t.id = uc.template_id " +
            "WHERE uc.user_id = #{userId} AND uc.status = 0 AND uc.expire_time > NOW() " +
            "AND t.shop_number = #{shopId} AND t.status = 1")
    List<UserCoupon> selectAvailable(@Param("userId") Long userId, @Param("shopId") Long shopId);
}
