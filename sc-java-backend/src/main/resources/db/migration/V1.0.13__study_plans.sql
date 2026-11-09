-- Planning exclusively owns plans and blocks. Absolute block times are explicitly UTC.
CREATE TABLE `t_study_plan` (
  `plan_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `client_request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `from_date` DATE NOT NULL,
  `to_date` DATE NOT NULL,
  `timezone` VARCHAR(64) NOT NULL,
  `source_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `occupancy_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `revision` BIGINT NOT NULL DEFAULT 1,
  `warnings_json` JSON NOT NULL,
  `shortages_json` JSON NOT NULL,
  `unavailable_json` JSON NOT NULL,
  `accept_request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `accept_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`plan_id`),
  UNIQUE KEY `uk_study_plan_user_request` (`user_id`, `client_request_id`),
  UNIQUE KEY `uk_study_plan_user_accept` (`user_id`, `accept_request_id`),
  KEY `idx_study_plan_user_state` (`user_id`, `state`, `plan_id`),
  CONSTRAINT `chk_study_plan` CHECK (`state` IN ('DRAFT','ACCEPTED','CANCELED')
    AND `to_date` >= `from_date` AND `revision` >= 1
    AND JSON_TYPE(`warnings_json`) = 'ARRAY' AND JSON_TYPE(`shortages_json`) = 'ARRAY'
    AND JSON_TYPE(`unavailable_json`) = 'ARRAY'
    AND ((`accept_request_id` IS NULL AND `accept_fingerprint` IS NULL)
      OR (`accept_request_id` IS NOT NULL AND `accept_fingerprint` IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_study_plan_block` (
  `block_id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `task_id` BIGINT NOT NULL,
  `task_title` VARCHAR(255) NOT NULL,
  `start_utc` DATETIME(3) NOT NULL COMMENT '绝对时刻，明确以 UTC 存储',
  `end_utc` DATETIME(3) NOT NULL COMMENT '绝对时刻，明确以 UTC 存储',
  `local_date` DATE NOT NULL,
  `state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `locked` TINYINT NOT NULL DEFAULT 0,
  `overdue` TINYINT NOT NULL DEFAULT 0,
  `version` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`block_id`),
  KEY `idx_study_block_plan` (`user_id`, `plan_id`, `start_utc`, `block_id`),
  KEY `idx_study_block_state` (`user_id`, `state`, `start_utc`, `end_utc`),
  KEY `idx_study_block_task` (`user_id`, `task_id`, `state`),
  CONSTRAINT `chk_study_block` CHECK (`state` IN ('PROPOSED','PLANNED','DONE','CANCELED')
    AND `start_utc` < `end_utc` AND `locked` IN (0,1) AND `overdue` IN (0,1) AND `version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
