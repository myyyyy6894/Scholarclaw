-- Existing openids were acquired without a Web challenge. Preserve them as data,
-- but require explicit re-verification before accepting messages or sending reminders.
ALTER TABLE `t_user_config`
  ADD COLUMN `qqbot_binding_verified` TINYINT NOT NULL DEFAULT 0 COMMENT 'Binding verified via Web challenge',
  ADD COLUMN `qqbot_binding_version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Credential and connection generation',
  ADD COLUMN `qqbot_challenge_hash` CHAR(64) NULL COMMENT 'SHA-256 of one-time binding challenge',
  ADD COLUMN `qqbot_challenge_expires_at` DATETIME(3) NULL COMMENT 'One-time binding challenge expiry';
