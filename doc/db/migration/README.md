# 数据库迁移

- `../tables_xxl_job.sql`：**全量建表脚本**，新库用它一次初始化，已包含全部迁移内容。
- `NNN_描述.sql`：**增量迁移**，只给已经初始化过的库用；每个脚本必须幂等（`IF NOT EXISTS` / `IF EXISTS` / `ON CONFLICT DO NOTHING`），单事务执行，出错整体回滚。
- 执行记录写在 `xxl_job_schema_migration` 表，按文件名去重，重复执行无副作用。

执行方式（先在一台调度中心节点或跳板机上，对同一个库只需执行一次）：

```bash
psql -h <pg-host> -U xxl_job -d xxl_job -1 -v ON_ERROR_STOP=1 -f doc/db/migration/001_schedule_timezone_and_drop_glue.sql
psql -h <pg-host> -U xxl_job -d xxl_job -Atc "select * from xxl_job_schema_migration order by applied_at"
```

| 脚本 | 内容 | 何时需要 |
|---|---|---|
| 001_schedule_timezone_and_drop_glue.sql | 加 `schedule_timezone` 列；删 `glue_*` 四列与 `xxl_job_logglue` 表 | 库由本分支早期版本的建表脚本初始化 |

**从 MySQL 版迁来**：没有 ALTER 路径，方言不同。做法是用 `tables_xxl_job.sql` 在 PostgreSQL 上建新库，再在控制台或通过 OpenAPI 重新录入执行器组与任务（数量通常很少），历史调度日志不迁移。
