-- Schedule owns these tables. Course links are optional snapshots; no physical foreign keys.
CREATE TABLE `t_schedule_term` (
  `term_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `first_monday` DATE NOT NULL,
  `week_count` SMALLINT NOT NULL,
  `timezone` VARCHAR(64) NOT NULL,
  `is_active` TINYINT NOT NULL DEFAULT 0,
  `active_user_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `is_active` = 1 THEN `user_id` ELSE NULL END) STORED,
  `version` BIGINT NOT NULL DEFAULT 1,
  `schedule_version` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`term_id`),
  UNIQUE KEY `uk_schedule_active_user` (`active_user_id`),
  KEY `idx_schedule_term_user` (`user_id`, `term_id`),
  CONSTRAINT `chk_schedule_term` CHECK (`week_count` BETWEEN 1 AND 53 AND `is_active` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_schedule_period` (
  `term_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `period_no` SMALLINT NOT NULL,
  `start_time` TIME NOT NULL,
  `end_time` TIME NOT NULL,
  PRIMARY KEY (`term_id`, `period_no`),
  KEY `idx_schedule_period_user` (`user_id`, `term_id`),
  CONSTRAINT `chk_schedule_period` CHECK (`period_no` BETWEEN 1 AND 30 AND `start_time` >= '00:00:00'
    AND `end_time` < '24:00:00' AND `start_time` < `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_schedule_rule` (
  `rule_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `term_id` BIGINT NOT NULL,
  `course_id` BIGINT NULL,
  `title` VARCHAR(200) NOT NULL,
  `teacher` VARCHAR(100) NULL,
  `room` VARCHAR(200) NULL,
  `weekday` TINYINT NOT NULL,
  `start_time` TIME NOT NULL,
  `end_time` TIME NOT NULL,
  `start_period` SMALLINT NULL,
  `end_period` SMALLINT NULL,
  `weeks_json` JSON NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`rule_id`),
  KEY `idx_schedule_rule_term` (`user_id`, `term_id`, `weekday`),
  KEY `idx_schedule_rule_course` (`user_id`, `course_id`),
  CONSTRAINT `chk_schedule_rule` CHECK (`weekday` BETWEEN 1 AND 7 AND `start_time` >= '00:00:00'
    AND `end_time` < '24:00:00' AND `start_time` < `end_time`
    AND ((`start_period` IS NULL AND `end_period` IS NULL)
      OR (`start_period` IS NOT NULL AND `end_period` IS NOT NULL
        AND `start_period` BETWEEN 1 AND 30 AND `end_period` BETWEEN `start_period` AND 30)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_schedule_exception` (
  `exception_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `term_id` BIGINT NOT NULL,
  `rule_id` BIGINT NOT NULL,
  `original_date` DATE NOT NULL,
  `kind` VARCHAR(8) NOT NULL,
  `target_date` DATE NULL,
  `target_start_time` TIME NULL,
  `target_end_time` TIME NULL,
  `target_room` VARCHAR(200) NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`exception_id`),
  UNIQUE KEY `uk_schedule_exception_occurrence` (`rule_id`, `original_date`),
  KEY `idx_schedule_exception_user_term` (`user_id`, `term_id`, `target_date`),
  CONSTRAINT `chk_schedule_exception` CHECK (
    (`kind` = 'CANCEL' AND `target_date` IS NULL AND `target_start_time` IS NULL AND `target_end_time` IS NULL)
    OR (`kind` = 'MOVE' AND `target_date` IS NOT NULL AND `target_start_time` IS NOT NULL
        AND `target_end_time` IS NOT NULL AND `target_start_time` >= '00:00:00'
        AND `target_end_time` < '24:00:00' AND `target_start_time` < `target_end_time`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
