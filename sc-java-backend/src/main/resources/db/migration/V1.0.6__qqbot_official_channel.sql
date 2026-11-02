-- V1.0.6：QQ 官方机器人（用户自带 Bot）接入。
--
-- 背景：OneBot/NapCat 走的是「一个部署一个机器人 QQ 小号」，实测受 QQ 风控影响严重
-- （频繁掉线、需要反复扫码），不适合作为公开服务的通知通道。
-- QQ 开放平台提供官方机器人与 WebSocket 长连接，不存在风控掉线问题；
-- 但个人主体的机器人不允许公开服务，因此采用「用户自带机器人」模式：
-- 每个用户在 QQ 开放平台创建自己的机器人，把 AppID/Secret 填到本站，
-- 本站为每个用户维持一条 WebSocket 连接，只服务该用户本人。
--
-- 单写归口：这些列由 userconfig 模块写入，channel 模块只读取凭据用于建连。
-- 不建物理外键（03 §3.3.1）。

ALTER TABLE `t_user_config`
  ADD COLUMN `qqbot_app_id`         VARCHAR(32)   NULL COMMENT 'QQ 官方机器人 AppID（用户自己的）',
  ADD COLUMN `qqbot_secret_cipher`  VARCHAR(1024) NULL COMMENT 'AppSecret 密文（AES-GCM，与模型 Key 同一条加密路径）',
  ADD COLUMN `qqbot_openid`         VARCHAR(128)  NULL COMMENT '用户在该机器人下的 openid（首次私聊后自动记录）',
  ADD COLUMN `qqbot_status`         VARCHAR(16)   NOT NULL DEFAULT 'DISCONNECTED'
      COMMENT '连接状态：DISCONNECTED / CONNECTING / ONLINE / ERROR',
  ADD COLUMN `qqbot_last_error`     VARCHAR(255)  NULL COMMENT '最近一次连接失败原因（脱敏，无密钥）',
  ADD COLUMN `qqbot_connected_at`   DATETIME(3)   NULL COMMENT '最近一次连接成功时间';

-- 一个机器人只能归属一个本站账号：避免两个账号绑同一个 AppID 抢连接
ALTER TABLE `t_user_config`
  ADD UNIQUE KEY `uk_user_config_qqbot_app` (`qqbot_app_id`);

-- 启动时按状态恢复连接，需要能快速捞出「已配置」的账号
CREATE INDEX `idx_user_config_qqbot_status` ON `t_user_config` (`qqbot_status`);
