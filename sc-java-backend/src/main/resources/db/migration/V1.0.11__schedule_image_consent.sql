-- Bind each image call to the BYOK configuration explicitly confirmed by its owner.
ALTER TABLE `t_schedule_import`
  ADD COLUMN `model_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `source_media_type`,
  ADD COLUMN `cleanup_attempt_at` DATETIME(3) NULL AFTER `model_fingerprint`,
  ADD KEY `idx_schedule_import_image_cleanup` (`source_type`, `status`, `cleanup_attempt_at`, `import_id`);
