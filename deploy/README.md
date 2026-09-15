# 部署与升级指南

调度中心（xxl-job-admin）与通用 HTTP 执行器（xxl-job-executor-http）以 `java -jar` 加 systemd 方式部署，配置与密钥全部在节点上的 `.env` 文件里，由单元文件 `EnvironmentFile=` 加载。做法与 coucou-server 的 `/data/coucou-server/.env` 一致：仓库里只有 `.env.example` 模板，真实文件不入库。

| 项目 | 值 |
|---|---|
| 调度中心服务端口 | **9280**（`SERVER_PORT`，避开 8080/8081 等已占用端口） |
| 调度中心内网域名 | **http://xxl-job.internal.sentino.jp**，由 Internal Gateway（api-gateway/infra）反代到三台调度中心 |
| 调度中心节点 | 与 coucou-server 同机：10.0.0.100 / 10.0.0.194 / 10.0.0.228（私网），系统用户 ubuntu，目录 /data/xxl-job-admin |
| 执行器内嵌端口 | 9999（`XXL_JOB_EXECUTOR_PORT`，可改） |
| 数据库 | PostgreSQL，独立库 `xxl_job`、独立账号 `xxl_job` |

仓库内相关文件：

```
deploy/systemd/xxl-job-admin.service            调度中心 systemd 单元
deploy/systemd/xxl-job-executor-http.service    通用 HTTP 执行器 systemd 单元
deploy/gateway/xxl-job.conf                      Internal Gateway 的 nginx 反代配置（复制到 api-gateway/infra）
xxl-job-admin/.env.example                       调度中心 .env 模板
xxl-job-executor-http/.env.example               通用 HTTP 执行器 .env 模板
doc/db/tables_xxl_job.sql                        全量建表脚本（新库）
doc/db/migration/                                增量迁移脚本（已有库）
```

application.properties 里的环境相关项全部是 `${ENV:默认值}` 占位（如 `spring.datasource.url=${DB_URL:...}`），变量名与 coucou-server 一致（DB_URL / DB_USER / DB_PASSWORD），所以 jar 不随环境重新打包。本地运行前 `set -a && . ./.env && set +a`。

---

## 一、完整上线步骤

### 第 1 步：Internal Gateway 加内网域名

在 api-gateway/infra 仓库里做，网关机上生效。

1. `dnsmasq/dnsmasq.conf` 增加一行：

   ```
   address=/xxl-job.internal.sentino.jp/127.0.0.1
   ```

2. 把本仓库 `deploy/gateway/xxl-job.conf` 复制为 `nginx/conf.d/xxl-job.conf`。upstream 已写好三台节点 10.0.0.100 / 10.0.0.194 / 10.0.0.228 的 9280，与网关里 agent.conf 反代的是同一批机器。
3. 网关机上部署并验证：

   ```bash
   sudo systemctl restart dnsmasq
   sudo cp nginx/conf.d/xxl-job.conf /etc/nginx/conf.d/
   sudo nginx -t && sudo systemctl reload nginx
   dig xxl-job.internal.sentino.jp          # 应解析到网关机
   ```

   此时调度中心还没起来，curl 会返回 502，等第 4 步之后再验。

4. 安全组：放通网关机到三台节点的 9280（网关到这三台的 8081 已放通，同一批机器再加一个端口）；放通执行器节点到网关机 80（已有）；放通三台调度中心到各执行器节点的 9999。

### 第 2 步：数据库

在托管 PostgreSQL（10.0.1.34）上建独立库与账号。密码用 `xxl-job-admin/.env` 里 `DB_PASSWORD` 的值，两边必须一致。

```bash
psql -h 10.0.1.34 -U postgres -c "CREATE ROLE xxl_job LOGIN PASSWORD '<.env 里的 DB_PASSWORD>';"
psql -h 10.0.1.34 -U postgres -c "CREATE DATABASE xxl_job OWNER xxl_job ENCODING 'UTF8';"
psql -h 10.0.1.34 -U xxl_job -d xxl_job -v ON_ERROR_STOP=1 -f doc/db/tables_xxl_job.sql
```

建表脚本自带默认数据：管理员 `admin / 123456`，两个示例执行器组，一条示例任务。上线后第一件事是改密码，示例任务与示例执行器组可以删。

**已有库怎么升级**：见第二章。

### 第 3 步：构建

```bash
mvn -pl xxl-job-admin,xxl-job-executor-http -am package -Dmaven.test.skip=true
# xxl-job-admin/target/xxl-job-admin-3.5.0-SNAPSHOT.jar
# xxl-job-executor-http/target/xxl-job-executor-http-3.5.0-SNAPSHOT.jar
```

业务服务作为执行器需要 `xxl-job-core`：发到团队私有 Maven 仓库，或在各构建机 `mvn -pl xxl-job-core -am install`。建议把版本号从 SNAPSHOT 改为正式号再发布。

### 第 4 步：调度中心节点（三台，逐台）

准备 `.env`：以 `xxl-job-admin/.env.example` 为模板，按环境填好。生产至少这些项：

```
SERVER_PORT=9280
LOG_HOME=/data/xxl-job-admin/logs
DB_URL=jdbc:postgresql://10.0.1.34:5432/xxl_job?sslmode=require
DB_USER=xxl_job
DB_PASSWORD=<与第 2 步一致>
XXL_JOB_LARK_WEBHOOK_URL=<群机器人 webhook>
XXL_JOB_LARK_SECRET=<签名密钥，未开签名留空>
XXL_JOB_LARK_ENV=prod
XXL_JOB_LARK_ADMIN_URL=http://xxl-job.internal.sentino.jp
```

三台节点的 `.env` 内容完全相同。然后：

```bash
sudo mkdir -p /data/xxl-job-admin/logs && sudo chown -R ubuntu:ubuntu /data/xxl-job-admin
scp xxl-job-admin/target/xxl-job-admin-3.5.0-SNAPSHOT.jar ubuntu@<node>:/data/xxl-job-admin/xxl-job-admin.jar
scp xxl-job-admin/.env                                     ubuntu@<node>:/data/xxl-job-admin/.env
scp deploy/systemd/xxl-job-admin.service                   ubuntu@<node>:/tmp/

ssh ubuntu@<node>
chmod 600 /data/xxl-job-admin/.env
sudo mv /tmp/xxl-job-admin.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now xxl-job-admin
journalctl -u xxl-job-admin -f      # 看到 "Started XxlJobAdminApplication" 与 "lark alarm enabled" 即可
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9280/auth/login   # 200
```

其余两台重复以上步骤。三台靠数据库锁互斥，同一时刻只有一台在调度，其余热备；节点时钟必须 NTP 同步（coucou-server 已在这三台跑 systemd，时钟与目录规范可直接沿用）。

回到网关机验证域名：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://xxl-job.internal.sentino.jp/auth/login   # 200
```

### 第 5 步：控制台初始化

浏览器打开 http://xxl-job.internal.sentino.jp（需在内网或经跳板）：

1. 用 `admin / 123456` 登录，右上角改密码。
2. "执行器管理"里为每个接入方新建执行器组：AppName 用服务名（如 `http-executor`、`workflow-api`），注册方式"自动注册"，生成 AccessToken。
3. 删除示例执行器组与示例任务。
4. 把各 AccessToken 填到对应服务的 `.env`。

### 第 6 步：通用 HTTP 执行器（两台）

`.env` 以 `xxl-job-executor-http/.env.example` 为模板：

```
XXL_JOB_ADMIN_ADDRESSES=http://xxl-job.internal.sentino.jp
XXL_JOB_EXECUTOR_APPNAME=http-executor
XXL_JOB_EXECUTOR_ACCESS_TOKEN=<第 5 步生成>
XXL_JOB_EXECUTOR_PORT=9999
XXL_JOB_EXECUTOR_ADDRESS=http://<本机私网IP>:9999/
XXL_JOB_HTTPJOB_ALLOWDOMAINS=.sentino.jp,api.coucou.fun,10.0.1.34:9082
```

`XXL_JOB_HTTPJOB_ALLOWDOMAINS` 生产必填，否则该执行器可以请求任意地址。若担心网关机单点，可写成 `http://xxl-job.internal.sentino.jp,http://10.0.0.100:9280,http://10.0.0.194:9280,http://10.0.0.228:9280`，执行器按顺序尝试。

```bash
sudo mkdir -p /data/xxl-job-executor-http/logs && sudo chown -R ubuntu:ubuntu /data/xxl-job-executor-http
scp xxl-job-executor-http/target/xxl-job-executor-http-3.5.0-SNAPSHOT.jar ubuntu@<node>:/data/xxl-job-executor-http/xxl-job-executor-http.jar
scp xxl-job-executor-http/.env                       ubuntu@<node>:/data/xxl-job-executor-http/.env
scp deploy/systemd/xxl-job-executor-http.service     ubuntu@<node>:/tmp/
ssh ubuntu@<node> 'chmod 600 /data/xxl-job-executor-http/.env && sudo mv /tmp/xxl-job-executor-http.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now xxl-job-executor-http'
```

启动后 30 秒内，控制台"执行器管理"里 http-executor 组应出现该节点地址。

### 第 7 步：业务服务接入（workflow-api 等）

业务服务引入 xxl-job-core，`.env` 里调度中心地址同样填 `http://xxl-job.internal.sentino.jp`，AppName 与 AccessToken 用第 5 步生成的值，注册地址填本机私网 ip:port。接入细节见《中央调度中心技术方案》。

### 第 8 步：验证清单

1. 执行器管理里各组的实例地址在线。
2. 新建一个任务手动执行一次：调度日志里调度结果与执行结果均成功，能看到执行器侧滚动日志。
3. 让一个任务故意失败一次：Lark 群收到告警；同一任务 5 分钟内再失败不重复发。
4. 停掉一台调度中心：任务仍按时触发，控制台经域名仍可访问。停掉一台执行器：故障转移路由切到另一台。
5. 网关机上 `tail -f /var/log/nginx/xxl-job-access.log` 能看到执行器每 30 秒一次的注册心跳。

---

## 二、数据库升级（已有库）

| 情况 | 做法 |
|---|---|
| 全新环境 | 只执行 `doc/db/tables_xxl_job.sql`，不需要迁移脚本 |
| 已用本分支早期 PostgreSQL 建表脚本初始化，缺 `schedule_timezone` 或仍有 `glue_*` 列 | 执行 `doc/db/migration/001_schedule_timezone_and_drop_glue.sql`，幂等，可重复执行 |
| 老的 MySQL 版 xxl-job | 没有 ALTER 路径。用 `tables_xxl_job.sql` 建 PostgreSQL 新库，在控制台重新录入执行器组与任务，历史日志不迁移 |

迁移脚本执行方式与规则见 `doc/db/migration/README.md`。执行顺序：先跑迁移，再滚动重启调度中心到新版本（本分支的结构变更都是加列或删无用列，旧版本读新结构不报错，因此先迁移后发版是安全的）。

## 三、版本升级

调度中心与执行器里的 xxl-job-core 协议已变，两者必须同版本升级，顺序：数据库迁移 → 调度中心（三台滚动）→ 执行器。

```bash
scp xxl-job-admin-<新版本>.jar ubuntu@<node>:/data/xxl-job-admin/xxl-job-admin.jar.new
ssh ubuntu@<node> 'cd /data/xxl-job-admin && cp xxl-job-admin.jar xxl-job-admin.jar.bak && mv xxl-job-admin.jar.new xxl-job-admin.jar && sudo systemctl restart xxl-job-admin'
journalctl -u xxl-job-admin -n 50 --no-pager
```

**不要在进程运行时原地覆盖 jar。** JVM 按需加载类，被覆盖后会出现 NoClassDefFoundError 与页面找不到。先写 `.jar.new` 再重命名，紧接着重启。一台确认正常后再做下一台，期间由其余节点接管调度。

`.env` 变更同样是改文件后 `sudo systemctl restart xxl-job-admin`，三台滚动。

## 四、回滚

```bash
ssh ubuntu@<node> 'cd /data/xxl-job-admin && mv xxl-job-admin.jar.bak xxl-job-admin.jar && sudo systemctl restart xxl-job-admin'
```

数据库结构若已迁移，回滚代码前确认旧版本能读新结构。

## 五、日常运维

- **告警**：Lark 通道全局配置，所有任务失败发到 `.env` 里的群，同一任务 5 分钟内合并；邮件通道按任务"报警邮箱"字段发送。
- **日志**：调度日志按 `XXL_JOB_LOG_RETENTION_DAYS` 自动清理；调度中心文件日志在 `LOG_HOME/xxl-job/`，执行器任务日志在其 `XXL_JOB_EXECUTOR_LOGPATH`。
- **备份**：xxl_job 库纳入托管 PG 备份；各节点 `.env` 单独备份，注意权限。
- **监控**：`http://xxl-job.internal.sentino.jp/actuator/health`；失败任务数从 xxl_job_log 中 handle_code 非 200 的记录统计。
- **网络路径**：执行器 → 网关 80 → 调度中心 9280；调度中心 → 执行器 9999；调度中心 → PostgreSQL 5432；调度中心 → open.larksuite.com 443。
