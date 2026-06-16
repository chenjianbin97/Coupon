-- Message template seed data
INSERT IGNORE INTO t_message_template (code, name, push_title, push_body, sms_content, variables, status, create_time, update_time)
VALUES
('COUPON_ONLINE',    '订阅券上线通知', '${template_name} 即将开抢',   '您订阅的${template_name}即将开始领取，快去看看吧', '【优惠券】您订阅的${template_name}即将开抢，限时领取！', '["template_name"]', 1, NOW(), NOW()),
('COUPON_EXPIRING',  '券即将过期提醒', '您的${template_name}即将过期', '您的${template_name}将在${expire_time}过期，记得使用', '【优惠券】您的${template_name}即将过期，快去使用！', '["template_name","expire_time"]', 1, NOW(), NOW()),
('COUPON_RECEIVED',  '券到账通知',     '${template_name}已到账',     '${template_name}已到账，有效期至${expire_time}', '【优惠券】${template_name}已到账，快去使用！', '["template_name","expire_time"]', 1, NOW(), NOW()),
('ORDER_PAID',       '支付成功通知',   '订单${order_no}支付成功',   '您的订单${order_no}已支付成功，金额${amount}元', '【订单】订单${order_no}支付成功，金额${amount}元', '["order_no","amount"]', 1, NOW(), NOW()),
('ORDER_CANCELLED',  '订单取消通知',   '订单${order_no}已取消',     '您的订单${order_no}已取消，如有优惠券会自动退回', '【订单】订单${order_no}已取消，优惠券已退回', '["order_no"]', 1, NOW(), NOW()),
('TASK_COMPLETE',    '分发任务完成',   '${task_name}发放完成',      '${task_name}已完成，共发放${success_count}张优惠券', NULL, '["task_name","success_count"]', 1, NOW(), NOW());
