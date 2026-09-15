# 部署指南（systemd）

调度中心与通用 HTTP 执行器都以 `java -jar` 加 systemd 方式部署，配置与密钥全部放在节点上的 `.env` 文件里，由单元文件的 `EnvironmentFile=` 加载。做法与 coucou-server 的 `/data/coucou-server/.env` 一致：仓库里只有 `.example` 模板，真实文件不入库。

目录：

```
deploy/systemd/xxl-job-admin.service            调度中心单元文件
deploy/systemd/xxl-job-admin.env.example        调度中心环境文件模板
deploy/systemd/xxl-job-executor-http.service    通用 HTTP 执行器单元文件
deploy/systemd/xxl-job-executor-http.env.example
```

Spring Boot 会把环境变量按松散绑定映射到配置项，例如 `SPRING_DATASOURCE_URL` 对应 `spring.datasource.url`，`XXL_JOB_LOGRETENTIONDAYS` 对应 `xxl.job.logretentiondays`，所以 jar 内的 application.properties 不需要改，所有环境差异都在 `.env` 里。

---

## 一、首次上线

### 1. 数据库

在目标环境的 PostgreSQL 上建库、建专用账号、初始化表：

```bash
psql -h <pg-host> -U postgres -c "CREATE ROLE xxl_job LOGIN PASSWORD '<密码>';"
psql -h <pg-host> -U postgres -c "CREATE DATABASE xxl_job OWNER xxl_job ENCODING 'UTF8';"
psql -h <pg-host> -U xxl_job -d xxl_job -v ON_ERROR_STOP=1 -f doc/db/tables_xxl_job.sql
```

建表脚本自带默认数据：管理员 `admin / 123456`，两个示例执行器组，一条示例任务。上线后第一件事是改密码，示例任务可以删。

### 2. 构建

在构建机上：

```bash
mvn -pl xxl-job-admin,xxl-job-executor-http -am package -Dmaven.test.skip=true
# 产物：
#   xxl-job-admin/target/xxl-job-admin-3.5.0-SNAPSHOT.jar
#   xxl-job-executor-http/target/xxl-job-executor-http-3.5.0-SNAPSHOT.jar
```

业务服务作为执行器还需要 `xxl-job-core`，把它发到团队私有 Maven 仓库（或在各构建机 `mvn -pl xxl-job-core -am install`），并把版本号从 SNAPSHOT 改为正式号再发布。

### 3. 节点目录与文件（每台调度中心节点）

```bash
sudo mkdir -p /data/xxl-job-admin/logs
sudo chown -R ubuntu:ubuntu /data/xxl-job-admin
scp xxl-job-admin/target/xxl-job-admin-3.5.0-SNAPSHOT.jar ubuntu@<node>:/data/xxl-job-admin/xxl-job-admin.jar
scp deploy/systemd/xxl-job-admin.env.example        ubuntu@<node>:/data/xxl-job-admin/.env
scp deploy/systemd/xxl-job-admin.service            ubuntu@<node>:/tmp/
```

在节点上编辑 `/data/xxl-job-admin/.env`，填数据库地址与密码、Lark webhook 与签名、环境标识、调度中心对外地址，然后：

```bash
chmod 600 /data/xxl-job-admin/.env
sudo mv /tmp/xxl-job-admin.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now xxl-job-admin
sudo systemctl status xxl-job-admin --no-pager
journalctl -u xxl-job-admin -f          # 看到 "Started XxlJobAdminApplication" 即启动完成
```

日志文件在 `/data/xxl-job-admin/logs/xxl-job/xxl-job-admin.log`（由 `LOG_HOME` 决定），控制台输出同时进 journald。

### 4. 第二台调度中心与负载均衡

第二台节点重复第 3 步，`.env` 内容相同（同一个数据库、同一个 Lark 配置）。两台靠数据库锁互斥，同一时刻只有一台在调度，另一台热备。前面挂内网负载均衡，控制台、OpenAPI、执行器注册都走负载均衡地址，不需要会话粘性。两台节点时钟必须 NTP 同步。

### 5. 控制台初始化

1. 登录，改 admin 密码。
2. "执行器管理"里为每个接入方建执行器组：AppName 用服务名，自动注册，生成独立 AccessToken。
3. 把 AccessToken 交给对应服务的 `.env`。

### 6. 通用 HTTP 执行器（可选，供不嵌入 xxl-job-core 的服务使用）

```bash
sudo mkdir -p /data/xxl-job-executor-http/logs
sudo chown -R ubuntu:ubuntu /data/xxl-job-executor-http
scp xxl-job-executor-http/target/xxl-job-executor-http-3.5.0-SNAPSHOT.jar ubuntu@<node>:/data/xxl-job-executor-http/xxl-job-executor-http.jar
scp deploy/systemd/xxl-job-executor-http.env.example ubuntu@<node>:/data/xxl-job-executor-http/.env
scp deploy/systemd/xxl-job-executor-http.service     ubuntu@<node>:/tmp/
# 编辑 .env：调度中心地址、AppName、AccessToken、本机私网 ip:port、目标域名白名单
chmod 600 /data/xxl-job-executor-http/.env
sudo mv /tmp/xxl-job-executor-http.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now xxl-job-executor-http
```

生产环境务必设置 `XXL_JOB_HTTPJOB_ALLOWDOMAINS`，否则该执行器可以请求任意地址。每个环境部署两台，路由策略用故障转移。

### 7. 网络放通（私网内）

| 方向 | 端口 | 用途 |
|---|---|---|
| 执行器节点 → 调度中心 | 8080（或负载均衡） | 注册、心跳、回调执行结果 |
| 调度中心 → 执行器节点 | 9999（执行器内嵌端口，可配） | 触发、终止、拉取日志 |
| 调度中心 → PostgreSQL | 5432 | 数据库 |
| 调度中心 → open.larksuite.com / open.feishu.cn | 443 | Lark 告警 |
| 通用 HTTP 执行器 → 业务服务 | 业务端口 | HTTP 任务 |

### 8. 验证

1. 执行器管理里能看到执行器实例地址在线。
2. 新建一个任务手动执行一次，调度日志里调度结果与执行结果都成功，能看到执行器侧滚动日志。
3. 让一个任务故意失败一次，Lark 群里收到告警；同一任务 5 分钟内再失败不重复发。
4. 停掉一台调度中心，任务仍按时触发；停掉一台执行器，故障转移路由切到另一台。

---

## 二、升级

调度中心与执行器里的 xxl-job-core 协议已经改过，两者必须同版本升级，顺序是先调度中心、后执行器。

```bash
# 每台调度中心节点，滚动进行：
scp xxl-job-admin-<新版本>.jar ubuntu@<node>:/data/xxl-job-admin/xxl-job-admin.jar.new
ssh ubuntu@<node> 'cd /data/xxl-job-admin && cp xxl-job-admin.jar xxl-job-admin.jar.bak && mv xxl-job-admin.jar.new xxl-job-admin.jar && sudo systemctl restart xxl-job-admin'
journalctl -u xxl-job-admin -n 50 --no-pager
```

**不要在进程运行时原地覆盖 jar 文件。** JVM 按需加载类，被覆盖后会出现 NoClassDefFoundError 与页面找不到。先写到 `.jar.new` 再原子重命名，紧接着重启。

数据库结构变更走幂等迁移脚本，先在一台执行；已有库从 MySQL 版迁来的迁移语句见《中央调度中心技术方案》8.1 节。

## 三、回滚

```bash
ssh ubuntu@<node> 'cd /data/xxl-job-admin && mv xxl-job-admin.jar.bak xxl-job-admin.jar && sudo systemctl restart xxl-job-admin'
```

数据库结构若已变更，回滚代码前确认旧版本能读新结构（本分支的变更都是加列，旧版本可兼容）。

## 四、日常运维

- **告警**：Lark 通道为全局配置，所有任务失败都发到 `.env` 里的群；邮件通道按任务的"报警邮箱"字段发送。
- **日志清理**：调度日志按 `XXL_JOB_LOGRETENTIONDAYS` 自动清理；执行器侧日志文件按执行器配置的保留天数清理。
- **备份**：xxl_job 库纳入现有 PostgreSQL 备份；`.env` 文件单独备份，注意权限。
- **监控**：调度中心 actuator 健康端点 `/actuator/health`；失败任务数可从 xxl_job_log 中 handle_code 非 200 的记录统计。
- **修改配置**：改 `.env` 后 `sudo systemctl restart xxl-job-admin`，两台滚动重启。
