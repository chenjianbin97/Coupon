package com.example.coupon.service.notify;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.coupon.common.enums.MessageTemplateStatusEnum;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.mapper.MessageTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class MessageTemplateService extends ServiceImpl<MessageTemplateMapper, MessageTemplate> {

    public MessageTemplate getByCode(String code) {
        return getOne(new QueryWrapper<MessageTemplate>()
                .eq("code", code)
                .eq("status", MessageTemplateStatusEnum.ENABLED.getStatus()));
    }

    public String render(String template, Map<String, String> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        return StringSubstitutor.replace(template, params, "${", "}");
    }
}
