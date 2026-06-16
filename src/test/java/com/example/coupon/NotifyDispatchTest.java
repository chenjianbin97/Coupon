package com.example.coupon;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.coupon.config.RabbitMQConfig;
import com.example.coupon.dto.NotifyMessage;
import com.example.coupon.entity.MessageTemplate;
import com.example.coupon.entity.NotifyLog;
import com.example.coupon.mapper.MessageTemplateMapper;
import com.example.coupon.mapper.NotifyLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class NotifyDispatchTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotifyLogMapper notifyLogMapper;

    @Autowired
    private MessageTemplateMapper messageTemplateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 99999L;
    private String messageId;

    @BeforeEach
    void setUp() {
        messageId = UUID.randomUUID().toString();

        // Ensure user exists (dispatch engine checks user existence)
        jdbcTemplate.update(
                "INSERT IGNORE INTO t_user (id, username, nickname, status, del_flag, create_time, update_time) " +
                "VALUES (?, 'test_notify', '测试用户', 1, 0, NOW(), NOW())",
                USER_ID);

        // Ensure template exists
        MessageTemplate existing = messageTemplateMapper.selectOne(
                new QueryWrapper<MessageTemplate>().eq("code", "COUPON_ONLINE"));
        if (existing == null) {
            MessageTemplate tpl = new MessageTemplate();
            tpl.setCode("COUPON_ONLINE");
            tpl.setName("测试模板");
            tpl.setPushTitle("${template_name} 即将开抢");
            tpl.setPushBody("您订阅的${template_name}即将开始");
            tpl.setSmsContent("【优惠券】${template_name}即将开抢");
            tpl.setVariables("[\"template_name\"]");
            tpl.setStatus(1);
            tpl.setCreateTime(LocalDateTime.now());
            tpl.setUpdateTime(LocalDateTime.now());
            messageTemplateMapper.insert(tpl);
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_notify_log WHERE trace_id = ?", messageId);
    }

    @Test
    void testFullDispatchPipeline() throws Exception {
        NotifyMessage msg = new NotifyMessage();
        msg.setMessageId(messageId);
        msg.setUserId(USER_ID);
        msg.setScene("COUPON_ONLINE");
        msg.setTemplateCode("COUPON_ONLINE");
        msg.setParams(Map.of("template_name", "满100减20"));
        msg.setPriority(1);
        msg.setSendTime(LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                msg);

        Thread.sleep(2000);

        List<NotifyLog> logs = notifyLogMapper.selectList(
                new QueryWrapper<NotifyLog>().eq("trace_id", messageId));
        assertFalse(logs.isEmpty(), "应有至少一条发送记录");
        assertEquals("SENT", logs.get(0).getStatus());
        assertEquals(1, logs.get(0).getChannel(), "默认应走LogChannel");
    }

    @Test
    void testIdempotentMessage() throws Exception {
        NotifyMessage msg = new NotifyMessage();
        msg.setMessageId(messageId);
        msg.setUserId(USER_ID);
        msg.setScene("COUPON_ONLINE");
        msg.setTemplateCode("COUPON_ONLINE");
        msg.setParams(Map.of("template_name", "满100减20"));
        msg.setPriority(1);
        msg.setSendTime(LocalDateTime.now());

        for (int i = 0; i < 2; i++) {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.NOTIFY_DISPATCH_EXCHANGE,
                    RabbitMQConfig.NOTIFY_DISPATCH_KEY,
                    msg);
        }

        Thread.sleep(2000);

        List<NotifyLog> logs = notifyLogMapper.selectList(
                new QueryWrapper<NotifyLog>().eq("trace_id", messageId));
        assertEquals(1, logs.size(), "幂等保证：同一messageId只处理一次");
    }
}
