-- Destructive rollback: first stop image import/cleanup and back up or complete all image tasks.
ALTER TABLE `t_schedule_import`
  DROP KEY `idx_schedule_import_image_cleanup`,
  DROP COLUMN `cleanup_attempt_at`,
  DROP COLUMN `model_fingerprint`;
