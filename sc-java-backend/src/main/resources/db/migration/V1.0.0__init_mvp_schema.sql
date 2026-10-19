-- ============================================================================
-- ScholarClaw (智学爪) MVP 数据库初始化迁移脚本
--
-- 来源：`docs/03_数据库设计文档.md` §9.2 的 `V1.0.0__init_mvp_schema.sql`（v1.0.3）Part 1
--       ——**逐字提取 7 张 MVP 表**（字段、类型、默认值、生成列、索引、CHECK 约束一字未改）。
--       Part 2 的 2 张 v1.1 扩展表（t_ddl_subtask / t_exam_wrong_book）按 03 §1.4 与
--       DB-04 默认方案留待 V1.1.0 增量脚本，**本阶段不建**（不得提前实现 v1.1 业务功能）。
--
-- 执行方式（两种都支持，版本记录表 sc_schema_migration 保证不会重复执行）：
--   1) Java 启动时由 com.scholarclaw.common.db.DatabaseMigrationRunner 自动执行
--      （开发/单机默认，受 sc.db.migration.enabled 控制）；
--   2) 运维手工执行（生产建议）：
--      docker compose exec -T sc-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < V1.0.0__init_mvp_schema.sql
--
-- 说明：脚本内的 CREATE DATABASE / USE / SET 是给手工执行准备的会话级语句；
--       Java 迁移器连接的是已存在的库，执行时会跳过它们（见 MigrationScript）。
--       本脚本**不插入任何业务数据**（03 §9.3：严禁预置管理员账号或伪造默认课程）。
-- ============================================================================

-- ============================================================================
-- ScholarClaw (智学爪) 数据库初始化 DDL 脚本
-- 脚本版本: V1.0.0 (对应 03_数据库设计文档.md v1.0.3)
-- 适用引擎: MySQL 8.0.x (InnoDB, utf8mb4, utf8mb4_0900_ai_ci)
-- 主键策略: 唯一采用 MySQL AUTO_INCREMENT (对应 MyBatis-Plus IdType.AUTO)
-- 安全声明: 本脚本不包含任何数据库账号密码、SC_MASTER_KEY、API Key 或虚构业务数据。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `scholarclaw`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `scholarclaw`;

SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- ============================================================================
-- [Part 1: MVP (v1.0) 必做核心业务表 (共 7 张表)]
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 用户账号、BYOK 模型加密配置与个人偏好表 (t_user_config)
-- 唯一写入模块: com.scholarclaw.modules.userconfig (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user_config` (
  `user_id`                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '用户全局唯一自增主键(MyBatis-Plus IdType.AUTO)',
  -- [组 A: 账号与认证生命周期字段 - 仅限认证/销户专用方法更新]
  `username`                   VARCHAR(64)   NOT NULL COMMENT '登录用户名(全局唯一)',
  `password_hash`              VARCHAR(100)  NOT NULL COMMENT 'BCrypt密码哈希值(严禁存明文)',
  `account_status`             VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态: ACTIVE-正常, DEACTIVATING-注销清理中',
  -- [组 B: BYOK 模型配置与个人偏好字段 - 仅限配置保存专用方法更新]
  `chat_provider`              VARCHAR(32)   DEFAULT NULL COMMENT '对话模型供应商: OPENAI_COMPATIBLE, DEEPSEEK, ZHIPU, OLLAMA',
  `chat_base_url`              VARCHAR(512)  DEFAULT NULL COMMENT '对话模型自定义Base URL(需经SSRF安全校验)',
  `chat_api_key_cipher`        VARCHAR(1024) DEFAULT NULL COMMENT '对话模型API Key密文(AES-256-GCM, 格式 v1:iv:cipher)',
  `chat_model`                 VARCHAR(128)  DEFAULT NULL COMMENT '主对话模型名称',
  `fast_chat_model`            VARCHAR(128)  DEFAULT NULL COMMENT '[v1.1预留] 轻量快模型名称(MVP默认复用chat_model)',
  `slow_reasoning_model`       VARCHAR(128)  DEFAULT NULL COMMENT '[v1.1预留] 深度推理慢模型名称',
  `default_embedding_provider` VARCHAR(32)   DEFAULT NULL COMMENT '全局默认Embedding供应商(初始NULL, 严禁填虚假默认值)',
  `embedding_base_url`         VARCHAR(512)  DEFAULT NULL COMMENT 'Embedding模型自定义Base URL(需经SSRF安全校验)',
  `embedding_api_key_cipher`   VARCHAR(1024) DEFAULT NULL COMMENT 'Embedding模型API Key密文(AES-256-GCM, 格式 v1:iv:cipher)',
  `default_embedding_model`    VARCHAR(128)  DEFAULT NULL COMMENT '全局默认Embedding模型名称(初始NULL, 严禁填虚假默认值)',
  `default_embedding_dim`      INT           DEFAULT NULL COMMENT '全局默认Embedding向量维度(初始NULL, 连通性探测成功后回填>0)',
  `key_version`                VARCHAR(16)   NOT NULL DEFAULT 'v1' COMMENT '密文使用的主密钥版本号(默认v1)',
  `timezone`                   VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '用户IANA时区标识(默认Asia/Shanghai)',
  `dnd_start_time`             TIME          NOT NULL DEFAULT '23:30:00' COMMENT '外部IM夜间免打扰开始时刻(默认23:30:00)',
  `dnd_end_time`               TIME          NOT NULL DEFAULT '07:30:00' COMMENT '外部IM夜间免打扰结束时刻(默认07:30:00)',
  `bound_im_platform`          VARCHAR(32)   DEFAULT NULL COMMENT '已绑定外部IM平台: QQ_ONEBOT, WECOM',
  `bound_im_uid`               VARCHAR(128)  DEFAULT NULL COMMENT '已绑定外部IM账号唯一标识(如QQ号)',
  -- [审计字段]
  `created_at`                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_bound_im` (`bound_im_platform`, `bound_im_uid`),
  KEY `idx_user_account_status` (`account_status`, `updated_at`),
  CONSTRAINT `chk_user_account_status` CHECK (`account_status` IN ('ACTIVE', 'DEACTIVATING')),
  CONSTRAINT `chk_user_emb_dim` CHECK (`default_embedding_dim` IS NULL OR `default_embedding_dim` > 0),
  CONSTRAINT `chk_user_im_pair` CHECK ((`bound_im_platform` IS NULL AND `bound_im_uid` IS NULL) OR (`bound_im_platform` IS NOT NULL AND `bound_im_uid` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号、BYOK加密配置与个人偏好表';

-- ----------------------------------------------------------------------------
-- 2. 课程知识库命名空间表 (t_course_namespace)
-- 唯一写入模块: com.scholarclaw.modules.course (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_course_namespace` (
  `course_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '课程空间全局唯一自增主键(MyBatis-Plus IdType.AUTO)',
  `user_id`            BIGINT       NOT NULL COMMENT '所属用户ID(逻辑关联t_user_config.user_id)',
  `course_name`        VARCHAR(128) NOT NULL COMMENT '课程空间名称(如: 数据库系统概念)',
  `description`        VARCHAR(500) DEFAULT NULL COMMENT '课程备注说明',
  `is_default`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为默认未分类课程空间: 0-否, 1-是',
  `embedding_provider` VARCHAR(32)  NOT NULL COMMENT '本课程强绑定Embedding供应商(必填NOT NULL, 严禁伪造默认值)',
  `embedding_model`    VARCHAR(128) NOT NULL COMMENT '本课程强绑定Embedding模型名称(必填NOT NULL, 严禁伪造默认值)',
  `embedding_dim`      INT          NOT NULL COMMENT '本课程强绑定Embedding向量维度(>0, 必填NOT NULL, 严禁伪造默认值)',
  `index_version`      INT          NOT NULL DEFAULT 1 COMMENT '课程向量索引版本号(单调递增, 对应Collection后缀_v{ver})',
  `index_status`       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '课程索引状态: ACTIVE, REBUILDING, REBUILD_FAILED',
  `is_deleting`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除清理标记: 0-正常, 1-删除清理中',
  `active_course_name_key` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `is_deleting` = 0 THEN `course_name` ELSE NULL END) STORED COMMENT '活动课程名唯一键生成列(删除清理中为NULL)',
  `default_course_uk`  BIGINT       GENERATED ALWAYS AS (CASE WHEN (`is_default` = 1 AND `is_deleting` = 0) THEN `user_id` ELSE NULL END) STORED COMMENT '默认课程唯一约束生成列: 活动默认课程为user_id, 普通课程或删除清理中课程为NULL',
  `created_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`course_id`),
  UNIQUE KEY `uk_course_user_default` (`default_course_uk`),
  UNIQUE KEY `uk_course_user_active_name` (`user_id`, `active_course_name_key`),
  KEY `idx_course_user_deleting` (`user_id`, `is_deleting`, `created_at`),
  KEY `idx_course_user_name` (`user_id`, `course_name`),
  CONSTRAINT `chk_course_index_status` CHECK (`index_status` IN ('ACTIVE', 'REBUILDING', 'REBUILD_FAILED')),
  CONSTRAINT `chk_course_reserved_name` CHECK (`is_default` = 1 OR `course_name` <> '未分类课程'),
  CONSTRAINT `chk_course_dim_ver` CHECK (`embedding_dim` > 0 AND `index_version` >= 1 AND `is_default` IN (0, 1) AND `is_deleting` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程知识库命名空间与Embedding强绑定表';

-- ----------------------------------------------------------------------------
-- 3. 知识库课件文档表 (t_knowledge_doc)
-- 唯一写入模块: com.scholarclaw.modules.knowledge (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_doc` (
  `doc_id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '课件文档全局唯一自增主键(MyBatis-Plus IdType.AUTO)',
  `user_id`                    BIGINT       NOT NULL COMMENT '所属用户ID',
  `course_id`                  BIGINT       NOT NULL COMMENT '所属课程空间ID(逻辑关联t_course_namespace.course_id)',
  `doc_name`                   VARCHAR(255) NOT NULL COMMENT '净化后的安全展示文件名(用于引用标注)',
  `file_ext`                   VARCHAR(16)  NOT NULL COMMENT '文件扩展名(小写带点: .pdf, .md, .txt)',
  `file_size_bytes`            BIGINT       NOT NULL COMMENT '文件字节大小(1 ~ 52428800, 即<=50MB)',
  `file_sha256`                CHAR(64)     NOT NULL COMMENT '文件内容SHA-256哈希值',
  `storage_rel_path`           VARCHAR(512) NOT NULL COMMENT '共享存储卷相对路径: raw/{user_id}/{course_id}/{doc_id}.{ext}',
  `status`                     VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED' COMMENT 'DocumentStatus六态: UPLOADED, PARSING, READY, FAILED, DELETING, DELETED',
  `active_file_sha256`         CHAR(64) GENERATED ALWAYS AS (CASE WHEN `status` NOT IN ('DELETING', 'DELETED') THEN `file_sha256` ELSE NULL END) STORED COMMENT '活动文档SHA唯一键生成列(删除态为NULL)',
  `parse_version`              INT          NOT NULL DEFAULT 1 COMMENT '解析任务乐观锁版本号(单调递增, 初始1)',
  `course_index_version`       INT          NOT NULL DEFAULT 1 COMMENT '归属的课程索引版本号(对应t_course_namespace.index_version)',
  `indexed_embedding_provider` VARCHAR(32)  DEFAULT NULL COMMENT 'READY时实际使用的Embedding供应商快照',
  `indexed_embedding_model`    VARCHAR(128) DEFAULT NULL COMMENT 'READY时实际使用的Embedding模型名快照',
  `indexed_embedding_dim`      INT          DEFAULT NULL COMMENT 'READY时实际生成的向量维度快照',
  `page_count`                 INT          NOT NULL DEFAULT 0 COMMENT '解析总页数(PDF<=300)',
  `chunk_count`                INT          NOT NULL DEFAULT 0 COMMENT '生成的子文档块(向量)总数量',
  `ocr_used`                   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '[v1.1预留] 是否使用OCR解析: 0-否(MVP固定0), 1-是',
  `last_ingest_job_id`         VARCHAR(64)  DEFAULT NULL COMMENT '最近一次MQ解析任务流水号(ingest_job_id)',
  `retry_count`                INT          NOT NULL DEFAULT 0 COMMENT '当前parse_version下的已重试次数(0~3)',
  `error_code`                 VARCHAR(64)  DEFAULT NULL COMMENT 'FAILED时的标准错误码',
  `error_msg`                  VARCHAR(512) DEFAULT NULL COMMENT 'FAILED时的可读错误说明(脱敏)',
  `enqueued_at`                DATETIME(3)  DEFAULT NULL COMMENT '最近一次发布RabbitMQ的时间戳',
  `ready_at`                   DATETIME(3)  DEFAULT NULL COMMENT '最近一次解析完成置为READY的时间戳',
  `created_at`                 DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传创建时间',
  `updated_at`                 DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态最后更新时间',
  PRIMARY KEY (`doc_id`),
  KEY `idx_doc_user_course_status` (`user_id`, `course_id`, `status`, `course_index_version`),
  UNIQUE KEY `uk_doc_user_course_active_sha` (`user_id`, `course_id`, `active_file_sha256`),
  KEY `idx_doc_user_course_sha256` (`user_id`, `course_id`, `file_sha256`),
  KEY `idx_doc_status_updated` (`status`, `updated_at`),
  CONSTRAINT `chk_doc_status` CHECK (`status` IN ('UPLOADED', 'PARSING', 'READY', 'FAILED', 'DELETING', 'DELETED')),
  CONSTRAINT `chk_doc_metrics` CHECK (`file_size_bytes` > 0 AND `file_size_bytes` <= 52428800 AND `parse_version` >= 1 AND `course_index_version` >= 1 AND `retry_count` >= 0 AND `retry_count` <= 3 AND `page_count` >= 0 AND `chunk_count` >= 0 AND `ocr_used` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库课件文档元数据与解析状态机表';

-- ----------------------------------------------------------------------------
-- 4. 知识库父文档块原文表 (t_knowledge_parent_chunk)
-- 唯一写入模块: com.scholarclaw.modules.knowledge (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_parent_chunk` (
  `parent_chunk_id` VARCHAR(64)  NOT NULL COMMENT '父文档块确定性主键: pc_{doc_id}_v{parse_version}_{chunk_seq}',
  `doc_id`          BIGINT       NOT NULL COMMENT '所属文档ID(逻辑关联t_knowledge_doc.doc_id)',
  `user_id`         BIGINT       NOT NULL COMMENT '所属用户ID(用于租户归属校验)',
  `course_id`       BIGINT       NOT NULL COMMENT '所属课程空间ID(用于课程归属校验与整课清理)',
  `parse_version`   INT          NOT NULL COMMENT '生成该父块时的文档parse_version',
  `chunk_seq`       INT          NOT NULL COMMENT '文档内父块顺序号(从1开始)',
  `page_start`      INT          NOT NULL COMMENT '真实起始页码(>=1)',
  `page_end`        INT          NOT NULL COMMENT '真实结束页码(>=page_start)',
  `section_title`   VARCHAR(255) DEFAULT NULL COMMENT '章节或小节标题',
  `content`         MEDIUMTEXT   NOT NULL COMMENT '父文档块完整原文(建议800~1200字符)',
  `char_length`     INT          NOT NULL DEFAULT 0 COMMENT '父块字符数',
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写入时间',
  PRIMARY KEY (`parent_chunk_id`),
  KEY `idx_pchunk_doc_id` (`doc_id`, `chunk_seq`),
  KEY `idx_pchunk_user_course` (`user_id`, `course_id`),
  CONSTRAINT `chk_pchunk_pages` CHECK (`parse_version` >= 1 AND `chunk_seq` >= 1 AND `page_start` >= 1 AND `page_end` >= `page_start` AND `char_length` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库父文档块原文与真实页码区间表';

-- ----------------------------------------------------------------------------
-- 5. DDL 待办任务主表 (t_ddl_task)
-- 唯一写入模块: com.scholarclaw.modules.ddl (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ddl_task` (
  `task_id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'DDL待办主任务唯一自增主键(MyBatis-Plus IdType.AUTO)',
  `user_id`            BIGINT        NOT NULL COMMENT '所属用户ID',
  `course_id`          BIGINT        DEFAULT NULL COMMENT '关联课程空间ID(可空; 删除课程时置NULL解绑保留任务)',
  `course_name`        VARCHAR(128)  NOT NULL COMMENT '课程或分类名称文本(必填且永久保留)',
  `task_title`         VARCHAR(255)  NOT NULL COMMENT '待办任务事项标题',
  `deliverable_format` VARCHAR(255)  DEFAULT NULL COMMENT '交付物格式或命名要求',
  `submission_channel` VARCHAR(255)  DEFAULT NULL COMMENT '提交渠道或方式说明',
  `raw_source_text`    TEXT          DEFAULT NULL COMMENT '触发提取的原始通知文本(Web手动新建时为NULL)',
  `raw_time_text`      VARCHAR(128)  DEFAULT NULL COMMENT '原文时间表述片段(如: 本周五下午5点前 / 尽快)',
  `time_precision`     VARCHAR(32)   NOT NULL DEFAULT 'EXACT_DATETIME' COMMENT '四级时间精度: EXACT_DATETIME, DATE_ONLY, VAGUE_OR_MISSING, INVALID_OR_PAST',
  `is_relative_time`   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否为相对时间表述: 0-否, 1-是',
  `due_date`           DATE          DEFAULT NULL COMMENT '用户时区下的截止公历日期(VAGUE_OR_MISSING时为NULL)',
  `due_time`           TIME          DEFAULT NULL COMMENT '用户时区下的截止时刻(DATE_ONLY与VAGUE_OR_MISSING时强制为NULL)',
  `due_at`             DATETIME(3)   DEFAULT NULL COMMENT '统一截止时间戳(DATE_ONLY默认当日23:59:59; PENDING_TIME强制为NULL)',
  `time_inferred`      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '时刻是否为DATE_ONLY默认23:59:59推断: 0-否, 1-是',
  `user_timezone`      VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '解析采用的用户时区标识快照',
  `confidence`         DECIMAL(4,3)  DEFAULT NULL COMMENT '模型提取置信度(0.000~1.000)',
  `status`             VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'TaskStatus六态: PENDING_TIME, PENDING_CONFIRM, ACTIVE, OVERDUE, COMPLETED, CANCELED',
  `follow_up_at`       DATETIME(3)   DEFAULT NULL COMMENT '待确认任务下次温和跟进提醒时间(ACTIVE/COMPLETED/CANCELED时为NULL)',
  `follow_up_count`    INT           NOT NULL DEFAULT 0 COMMENT '已发送跟进提醒次数(0~2)',
  `reminded_flags`     INT           NOT NULL DEFAULT 0 COMMENT '正式梯度提醒位掩码(1:T-24h, 2:T-6h, 4:OVERDUE)',
  `has_subtasks`       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '[v1.1预留] 是否含拆解子任务: 0-否(MVP固定0), 1-是',
  `source_platform`    VARCHAR(32)   NOT NULL DEFAULT 'WEB' COMMENT '来源渠道: WEB, QQ_ONEBOT, WECOM',
  `source_msg_id`      VARCHAR(128)  NOT NULL COMMENT '来源平台消息ID(platform_msg_id)或Web端操作请求UUID(client_request_id), 同操作重试复用原ID, 确认仍然新建使用新ID, 严禁为空',
  `idempotency_key`    CHAR(64)      NOT NULL COMMENT '第2层请求级唯一防重键: 固定为 sha256(user_id:source_platform:source_msg_id:DDL), force_create不改变本键算法',
  `content_hash`       CHAR(64)      DEFAULT NULL COMMENT '第3层规范化通知文本SHA-256哈希: sha256(normalize(course_name):normalize(raw_source_text)), 用于14天短期语义防重(force_create=true时跳过检测, 手动建单为NULL)',
  `completed_at`       DATETIME(3)   DEFAULT NULL COMMENT '实际标记完成时间戳',
  `created_at`         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间',
  `updated_at`         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '任务更新时间',
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_ddl_idempotency_key` (`idempotency_key`),
  KEY `idx_ddl_user_status_due` (`user_id`, `status`, `due_at`),
  KEY `idx_ddl_user_hash_created` (`user_id`, `content_hash`, `created_at`),
  KEY `idx_ddl_status_due` (`status`, `due_at`),
  KEY `idx_ddl_status_followup` (`status`, `follow_up_at`),
  KEY `idx_ddl_course_id` (`course_id`),
  CONSTRAINT `chk_ddl_enums` CHECK (`time_precision` IN ('EXACT_DATETIME', 'DATE_ONLY', 'VAGUE_OR_MISSING', 'INVALID_OR_PAST') AND `status` IN ('PENDING_TIME', 'PENDING_CONFIRM', 'ACTIVE', 'OVERDUE', 'COMPLETED', 'CANCELED') AND `source_platform` IN ('WEB', 'QQ_ONEBOT', 'WECOM')),
  CONSTRAINT `chk_ddl_ranges` CHECK ((`confidence` IS NULL OR (`confidence` >= 0.000 AND `confidence` <= 1.000)) AND `follow_up_count` >= 0 AND `follow_up_count` <= 2 AND `reminded_flags` >= 0 AND `reminded_flags` <= 7 AND `is_relative_time` IN (0, 1) AND `time_inferred` IN (0, 1) AND `has_subtasks` IN (0, 1)),
  CONSTRAINT `chk_ddl_time_invariants` CHECK (
    (`status` != 'PENDING_TIME' OR (`due_at` IS NULL AND `due_date` IS NULL AND `due_time` IS NULL))
    AND (`status` NOT IN ('ACTIVE', 'OVERDUE') OR (`due_at` IS NOT NULL AND `due_date` IS NOT NULL))
    AND (`time_precision` != 'DATE_ONLY' OR (`due_time` IS NULL AND `time_inferred` = 1))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DDL待办任务主表';

-- ----------------------------------------------------------------------------
-- 6. 会话历史消息表 (t_chat_message)
-- 唯一写入模块: com.scholarclaw.modules.agent (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_chat_message` (
  `message_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话消息全局唯一自增主键(MyBatis-Plus IdType.AUTO)',
  `user_id`             BIGINT       NOT NULL COMMENT '所属用户ID',
  `course_id`           BIGINT       DEFAULT NULL COMMENT '关联课程空间ID(全局指令或非课程问答时为NULL)',
  `platform`            VARCHAR(32)  NOT NULL DEFAULT 'WEB' COMMENT '交互渠道: WEB, QQ_ONEBOT, WECOM',
  `platform_msg_id`     VARCHAR(128) DEFAULT NULL COMMENT '客户端或IM平台原始消息ID',
  `role`                VARCHAR(16)  NOT NULL COMMENT '消息角色: USER, ASSISTANT, SYSTEM',
  `intent_type`         VARCHAR(32)  DEFAULT NULL COMMENT '路由意图枚举: DDL_EXTRACT, TASK_MANAGE, KB_INGEST, KB_QA, CHITCHAT_HELP等',
  `content`             MEDIUMTEXT   NOT NULL COMMENT '消息文本或经CitationVerifier校验后的回答正文',
  `citations_json`      JSON         DEFAULT NULL COMMENT '校验通过的父块引用列表JSON数组',
  `is_rejected`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否触发无资料拒答门控: 0-否, 1-是',
  `trace_id`            VARCHAR(64)  DEFAULT NULL COMMENT '全链路追踪ID(tr_...)',
  `retrieval_cost_ms`   INT          DEFAULT NULL COMMENT '系统内部检索与父块回查耗时(ms)',
  `llm_first_token_ms`  INT          DEFAULT NULL COMMENT '大模型流式首字耗时(ms)',
  `created_at`          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '消息创建时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_chat_user_course_id` (`user_id`, `course_id`, `message_id`),
  KEY `idx_chat_trace_id` (`trace_id`),
  CONSTRAINT `chk_chat_role_platform` CHECK (`role` IN ('USER', 'ASSISTANT', 'SYSTEM') AND `platform` IN ('WEB', 'QQ_ONEBOT', 'WECOM') AND `is_rejected` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多端交互与课程RAG问答会话历史表';

-- ----------------------------------------------------------------------------
-- 7. 站内通知与外部 IM 推送状态流水表 (t_notification_log)
-- 唯一写入模块: com.scholarclaw.modules.notification (Single-Writer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_notification_log` (
  `notification_id`    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知全局唯一自增主键(MyBatis-Plus IdType.AUTO)',
  `user_id`            BIGINT       NOT NULL COMMENT '接收用户ID',
  `biz_type`           VARCHAR(32)  NOT NULL COMMENT '业务类型: DDL_T24H, DDL_T6H, DDL_OVERDUE, DDL_FOLLOW_UP, DOC_INGEST_READY, DOC_INGEST_FAILED, COURSE_REBUILD_DONE',
  `biz_id`             BIGINT       DEFAULT NULL COMMENT '关联业务主键(task_id / doc_id / course_id)',
  `title`              VARCHAR(255) NOT NULL COMMENT '通知标题',
  `content`            TEXT         NOT NULL COMMENT '通知详细正文及IM推送卡片文本',
  `read_status`        VARCHAR(16)  NOT NULL DEFAULT 'UNREAD' COMMENT 'Web站内信阅读状态: UNREAD, READ',
  `outbound_status`    VARCHAR(32)  NOT NULL COMMENT 'OutboundStatus四态(无数据库DEFAULT值,由NotificationService显式赋值): PENDING_IM, RATE_LIMITED_DELAYED, SENT_IM, FALLBACK_WEB_ONLY',
  `target_im_platform` VARCHAR(32)  DEFAULT NULL COMMENT '目标外部IM平台快照: QQ_ONEBOT, WECOM',
  `target_im_uid`      VARCHAR(128) DEFAULT NULL COMMENT '目标外部IM账号标识快照',
  `im_retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '外部IM推送已重试次数(0~2)',
  `im_error_msg`       VARCHAR(512) DEFAULT NULL COMMENT 'IM推送失败或降级FALLBACK_WEB_ONLY原因简述',
  `scheduled_push_at`  DATETIME(3)  DEFAULT NULL COMMENT '计划或顺延执行IM推送的时间戳',
  `sent_im_at`         DATETIME(3)  DEFAULT NULL COMMENT '外部IM实际推送成功时间戳',
  `read_at`            DATETIME(3)  DEFAULT NULL COMMENT 'Web站内信标记已读时间戳',
  `trace_id`           VARCHAR(64)  DEFAULT NULL COMMENT '关联全链路追踪ID',
  `created_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通知创建时间',
  `updated_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态最后更新时间',
  PRIMARY KEY (`notification_id`),
  KEY `idx_notif_user_read_created` (`user_id`, `read_status`, `created_at`),
  KEY `idx_notif_outbound_sched` (`outbound_status`, `scheduled_push_at`),
  CONSTRAINT `chk_notif_enums` CHECK (`read_status` IN ('UNREAD', 'READ') AND `outbound_status` IN ('PENDING_IM', 'RATE_LIMITED_DELAYED', 'SENT_IM', 'FALLBACK_WEB_ONLY') AND `im_retry_count` >= 0 AND `im_retry_count` <= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Web站内通知与外部IM限流推送流水表';
