-- Rolling back the application re-enables the old insecure first-message binding.
-- Do not use this rollback while the official QQ channel is enabled.
ALTER TABLE `t_user_config`
  DROP COLUMN `qqbot_challenge_expires_at`,
  DROP COLUMN `qqbot_challenge_hash`,
  DROP COLUMN `qqbot_binding_version`,
  DROP COLUMN `qqbot_binding_verified`;
