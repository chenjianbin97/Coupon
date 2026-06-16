-- Message template table
CREATE TABLE IF NOT EXISTS t_message_template (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(128),
    push_title      VARCHAR(256),
    push_body       VARCHAR(1024),
    sms_content     VARCHAR(512),
    variables       VARCHAR(512),
    status          INT DEFAULT 1,
    create_time     DATETIME,
    update_time     DATETIME,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Notify log table
CREATE TABLE IF NOT EXISTS t_notify_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id        VARCHAR(64) NOT NULL,
    user_id         BIGINT,
    scene           VARCHAR(64),
    channel         INT,
    status          VARCHAR(32) DEFAULT 'SENT',
    title           VARCHAR(256),
    content         VARCHAR(2048),
    fail_reason     VARCHAR(512),
    send_time       DATETIME,
    deliver_time    DATETIME,
    create_time     DATETIME,
    INDEX idx_user_id (user_id),
    INDEX idx_trace_id (trace_id),
    INDEX idx_send_time (send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Add device_token to t_user
ALTER TABLE t_user ADD COLUMN device_token VARCHAR(256) NULL;
