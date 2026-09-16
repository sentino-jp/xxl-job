# xxl-job-http-target-python

演示"调度中心 → 通用 HTTP 执行器 → 业务 HTTP 接口"链路的最小目标服务，纯 Python 标准库，无需安装依赖。

## 运行

```bash
python3 hello_service.py                # 监听 127.0.0.1:8399
python3 hello_service.py 0.0.0.0 8399   # 指定地址与端口
```

每收到一次 `/hello` 请求，控制台打印一行：

```
[2026-09-15 18:40:10] #1 hello world  <- POST /hello from 127.0.0.1  body={"msg":"hello world"}
```

## 在调度中心配置任务

前提：有一个执行器在线且其 `httpjob.allowdomains` 允许本服务的地址（为空表示全部允许），例如调度中心内嵌的通用 HTTP 执行器（http-executor 组，随 xxl-job-admin 一起启动）。

| 字段 | 值 |
|---|---|
| 执行器 | 任意带内置 httpJobHandler 的执行器组，如 http-executor |
| 调度类型 | CRON，`0/10 * * * * ?`（每 10 秒） |
| 调用方式 | HTTP 调用 |
| 请求地址 | `http://127.0.0.1:8399/hello` |
| 请求方法 | POST |
| Content-Type | JSON |
| 请求体 | `{"msg":"hello world"}` |
| 超时秒数 | 5 |
| 阻塞处理策略 | 丢弃后续调度 |

保存后点"启动"，本服务的控制台即每 10 秒打印一行 hello world；调度中心"调度日志"里同时出现对应的成功记录，"执行日志"里能看到返回的 JSON。
