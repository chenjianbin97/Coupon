package com.example.coupon.config;

import com.example.coupon.common.interceptor.RateLimitInterceptor;
import com.example.coupon.common.interceptor.TokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private TokenInterceptor tokenInterceptor;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/user/login", "/user/save",
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/user/login", "/user/save",
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
    }
}
