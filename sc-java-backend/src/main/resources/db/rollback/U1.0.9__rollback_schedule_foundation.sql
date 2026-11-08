-- Destructive: removes all schedule data. Back up and obtain explicit authorization before use.
DROP TABLE IF EXISTS `t_schedule_exception`;
DROP TABLE IF EXISTS `t_schedule_rule`;
DROP TABLE IF EXISTS `t_schedule_period`;
DROP TABLE IF EXISTS `t_schedule_term`;
