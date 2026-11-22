CREATE TABLE t_schedule_reminder_pref (
  user_id BIGINT NOT NULL,
  enabled TINYINT NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (user_id),
  KEY idx_schedule_reminder_pref_enabled (enabled,user_id),
  CONSTRAINT chk_schedule_reminder_pref CHECK (enabled IN (0,1) AND version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_schedule_reminder (
  reminder_id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  start_epoch BIGINT NOT NULL,
  schedule_version BIGINT NOT NULL,
  member_keys JSON NOT NULL,
  group_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  notification_id BIGINT NULL,
  PRIMARY KEY (reminder_id),
  UNIQUE KEY uk_schedule_reminder_start (user_id,start_epoch),
  KEY idx_schedule_reminder_expiry (start_epoch),
  CONSTRAINT chk_schedule_reminder CHECK (active IN (0,1) AND schedule_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE t_notification_log
  ADD COLUMN external_deadline_epoch BIGINT NULL,
  ADD COLUMN qq_binding_version BIGINT NULL,
  ADD COLUMN schedule_send_started TINYINT NOT NULL DEFAULT 0;
