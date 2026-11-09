-- Planning owns these inputs; DDL remains the sole writer of task data.
CREATE TABLE `t_planning_preference` (
  `user_id` BIGINT NOT NULL,
  `timezone` VARCHAR(64) NOT NULL,
  `windows_json` JSON NOT NULL,
  `focus_minutes` SMALLINT NOT NULL,
  `daily_limit_minutes` SMALLINT NOT NULL,
  `buffer_minutes` SMALLINT NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `chk_planning_preference` CHECK (JSON_TYPE(`windows_json`) = 'ARRAY'
    AND `focus_minutes` BETWEEN 5 AND 480 AND `daily_limit_minutes` BETWEEN 5 AND 1440
    AND `buffer_minutes` BETWEEN 0 AND 120)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_personal_event` (
  `event_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `timezone` VARCHAR(64) NOT NULL,
  `start_date` DATE NOT NULL,
  `end_date` DATE NULL,
  `weekday` TINYINT NULL,
  `start_minute` SMALLINT NOT NULL,
  `end_minute` SMALLINT NOT NULL,
  `hard` TINYINT NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_personal_event_user` (`user_id`, `event_id`),
  CONSTRAINT `chk_personal_event` CHECK (`hard` IN (0, 1)
    AND `start_minute` BETWEEN 0 AND 1439 AND `end_minute` BETWEEN 1 AND 1440
    AND `start_minute` < `end_minute` AND (`weekday` IS NULL OR `weekday` BETWEEN 1 AND 7)
    AND (`end_date` IS NULL OR `end_date` >= `start_date`)
    AND (`weekday` IS NOT NULL OR (`end_date` IS NOT NULL AND `end_date` = `start_date`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_task_planning_profile` (
  `user_id` BIGINT NOT NULL,
  `task_id` BIGINT NOT NULL,
  `estimated_minutes` INT NOT NULL,
  `remaining_minutes` INT NOT NULL,
  `priority` TINYINT NOT NULL,
  `earliest_start_utc` DATETIME(3) NULL COMMENT '明确按 UTC 保存绝对时间，避免 DST 重复时刻歧义',
  `splittable` TINYINT NOT NULL,
  `min_block_minutes` SMALLINT NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`user_id`, `task_id`),
  CONSTRAINT `chk_task_planning_profile` CHECK (`estimated_minutes` BETWEEN 1 AND 100000
    AND `remaining_minutes` BETWEEN 0 AND 100000 AND `priority` BETWEEN 1 AND 5
    AND `splittable` IN (0, 1) AND `min_block_minutes` BETWEEN 5 AND 480)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
