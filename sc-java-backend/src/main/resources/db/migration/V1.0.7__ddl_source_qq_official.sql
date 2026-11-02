-- V1.0.7：DDL 来源渠道增加 QQ_OFFICIAL。
--
-- 背景：QQ 官方机器人（用户自带 Bot）走的是长连接 + openid，与 OneBot 的 QQ 号
-- 语义不同，不能复用 'QQ_ONEBOT'（幂等键 sha256(user:platform:msg_id:DDL) 会把两条
-- 不同通道的同号消息混为一体）。建表时的 chk_ddl_enums 只允许 WEB / QQ_ONEBOT / WECOM，
-- 实测插入 QQ_OFFICIAL 会报 "Check constraint 'chk_ddl_enums' is violated"。
--
-- 只放宽枚举，不改任何既有数据。

ALTER TABLE `t_ddl_task` DROP CHECK `chk_ddl_enums`;

ALTER TABLE `t_ddl_task`
  ADD CONSTRAINT `chk_ddl_enums` CHECK (
    `time_precision` IN ('EXACT_DATETIME', 'DATE_ONLY', 'VAGUE_OR_MISSING', 'INVALID_OR_PAST')
    AND `status` IN ('PENDING_TIME', 'PENDING_CONFIRM', 'ACTIVE', 'OVERDUE', 'COMPLETED', 'CANCELED')
    AND `source_platform` IN ('WEB', 'QQ_ONEBOT', 'WECOM', 'QQ_OFFICIAL')
  );

ALTER TABLE `t_ddl_task`
  MODIFY COLUMN `source_platform` VARCHAR(32) NOT NULL DEFAULT 'WEB'
  COMMENT '来源渠道: WEB, QQ_ONEBOT, WECOM, QQ_OFFICIAL';
