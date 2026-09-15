-- 001: 任务级时区字段 + 移除 GLUE 相关列与表（PostgreSQL，幂等，可重复执行）
-- 适用对象：用本分支更早版本的 tables_xxl_job.sql 初始化过、但还没有 schedule_timezone 列或仍有 glue_* 列的库。
-- 用 tables_xxl_job.sql 全新初始化的库已包含这些变更，执行本脚本无副作用。
-- 执行：psql -h <pg-host> -U xxl_job -d xxl_job -1 -v ON_ERROR_STOP=1 -f doc/db/migration/001_schedule_timezone_and_drop_glue.sql

ALTER TABLE xxl_job_info ADD COLUMN IF NOT EXISTS schedule_timezone VARCHAR(64) DEFAULT NULL;
COMMENT ON COLUMN xxl_job_info.schedule_timezone IS '调度时区，CRON类型生效，为空则使用调度中心默认时区';

ALTER TABLE xxl_job_info DROP COLUMN IF EXISTS glue_type;
ALTER TABLE xxl_job_info DROP COLUMN IF EXISTS glue_source;
ALTER TABLE xxl_job_info DROP COLUMN IF EXISTS glue_remark;
ALTER TABLE xxl_job_info DROP COLUMN IF EXISTS glue_updatetime;

DROP TABLE IF EXISTS xxl_job_logglue;

-- 记录已执行的迁移（首次执行时建表）
CREATE TABLE IF NOT EXISTS xxl_job_schema_migration
(
    filename   VARCHAR(255) NOT NULL,
    applied_at TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (filename)
);
INSERT INTO xxl_job_schema_migration(filename) VALUES ('001_schedule_timezone_and_drop_glue.sql')
ON CONFLICT (filename) DO NOTHING;
