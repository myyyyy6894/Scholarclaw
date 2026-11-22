CREATE TABLE t_schedule_exam (
  rule_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  exam_date DATE NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  room VARCHAR(100) NULL,
  version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (rule_id),
  KEY idx_schedule_exam_term (user_id, term_id, exam_date),
  CONSTRAINT chk_schedule_exam_time CHECK (start_time >= '00:00:00' AND end_time < '24:00:00' AND start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE t_knowledge_doc
  DROP CHECK chk_doc_status,
  ADD CONSTRAINT chk_doc_status CHECK (status IN ('UPLOADED','PARSING','READY','FAILED','DELETING','DELETED','STORED'));
