package com.example.coupon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RabbitMQTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private static final String TEST_QUEUE = "test.coupon.queue";

    @BeforeEach
    void setUp() {
        rabbitAdmin.declareQueue(new Queue(TEST_QUEUE, false, false, true));
    }

    @Test
    void testSendAndReceiveMessage() {
        String message = "Hello RabbitMQ - " + System.currentTimeMillis();

        rabbitTemplate.convertAndSend(TEST_QUEUE, message);

        Object received = rabbitTemplate.receiveAndConvert(TEST_QUEUE, 5000);

        assertNotNull(received, "应成功接收到消息");
        assertEquals(message, received.toString(), "接收到的消息应与发送的一致");
    }

    @Test
    void testRabbitMQConnection() {
        assertNotNull(rabbitTemplate);
        assertNotNull(rabbitAdmin);
    }
}
