package com.example.coupon.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter messageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.addAllowedListPatterns("com.example.coupon.dto.*", "com.example.coupon.entity.*", "java.*");
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setChannelTransacted(true);
        return template;
    }

    public static final String COUPON_TASK_EXECUTE_QUEUE = "coupon.task.execute.queue";
    public static final String COUPON_TASK_EXECUTE_EXCHANGE = "coupon.task.execute.exchange";
    public static final String COUPON_TASK_EXECUTE_ROUTING_KEY = "coupon.task.execute";

    public static final String COUPON_RECEIVE_QUEUE = "coupon.receive.queue";
    public static final String COUPON_RECEIVE_EXCHANGE = "coupon.receive.exchange";
    public static final String COUPON_RECEIVE_ROUTING_KEY = "coupon.receive";

    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.cancel.exchange";
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.cancel.queue";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout.cancel";

    public static final String NOTIFY_DISPATCH_QUEUE = "notify.dispatch.queue";
    public static final String NOTIFY_DISPATCH_EXCHANGE = "notify.dispatch.exchange";
    public static final String NOTIFY_DISPATCH_KEY = "notify.dispatch";

    @Bean
    public Queue couponTaskExecuteQueue() {
        return new Queue(COUPON_TASK_EXECUTE_QUEUE, true);
    }

    @Bean
    public DirectExchange couponTaskExecuteExchange() {
        return new DirectExchange(COUPON_TASK_EXECUTE_EXCHANGE, true, false);
    }

    @Bean
    public Binding couponTaskExecuteBinding() {
        return BindingBuilder.bind(couponTaskExecuteQueue())
                .to(couponTaskExecuteExchange())
                .with(COUPON_TASK_EXECUTE_ROUTING_KEY);
    }

    @Bean
    public Queue couponReceiveQueue() {
        return new Queue(COUPON_RECEIVE_QUEUE, true);
    }

    @Bean
    public DirectExchange couponReceiveExchange() {
        return new DirectExchange(COUPON_RECEIVE_EXCHANGE, true, false);
    }

    @Bean
    public Binding couponReceiveBinding() {
        return BindingBuilder.bind(couponReceiveQueue())
                .to(couponReceiveExchange())
                .with(COUPON_RECEIVE_ROUTING_KEY);
    }

    @Bean
    public CustomExchange orderTimeoutExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(ORDER_TIMEOUT_EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue orderTimeoutQueue() {
        return new Queue(ORDER_TIMEOUT_QUEUE, true);
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue())
                .to(orderTimeoutExchange())
                .with(ORDER_TIMEOUT_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public Queue notifyDispatchQueue() {
        return new Queue(NOTIFY_DISPATCH_QUEUE, true);
    }

    @Bean
    public DirectExchange notifyDispatchExchange() {
        return new DirectExchange(NOTIFY_DISPATCH_EXCHANGE, true, false);
    }

    @Bean
    public Binding notifyDispatchBinding() {
        return BindingBuilder.bind(notifyDispatchQueue())
                .to(notifyDispatchExchange())
                .with(NOTIFY_DISPATCH_KEY);
    }
}
