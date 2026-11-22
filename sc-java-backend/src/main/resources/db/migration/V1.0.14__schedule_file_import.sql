ALTER TABLE t_schedule_import
  DROP CHECK chk_schedule_import,
  ADD COLUMN source_format VARCHAR(8) NULL,
  ADD COLUMN file_assist_used TINYINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_schedule_import CHECK (
    ((source_type IN ('TEXT','IMAGE') AND source_format IS NULL)
      OR (source_type='FILE' AND source_format IS NOT NULL AND source_format IN ('XLSX','CSV','DOCX')))
    AND file_assist_used IN (0,1)
    AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','FAILED','CONFIRMED','EXPIRED','CANCELED')
    AND attempt BETWEEN 0 AND 3 AND version > 0 AND schedule_version > 0
  );
