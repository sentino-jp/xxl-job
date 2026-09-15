#!/usr/bin/env python3
"""
最小 HTTP 目标服务，用来演示 xxl-job 的 HTTP 调用任务。

调度中心里新建"调用方式 = HTTP"的任务，指向本服务的 /hello 接口，
每次被调用都会在控制台打印一行 hello world，并返回 JSON。

运行：
    python3 hello_service.py                # 默认 127.0.0.1:8399
    python3 hello_service.py 0.0.0.0 8399   # 指定监听地址与端口

接口：
    GET/POST /hello    打印 hello world，返回 {"code":200,"msg":"hello world",...}
    GET      /health   健康检查，返回 {"status":"UP"}
    其他路径           404
"""
import json
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 只依赖标准库，无需安装任何包
counter = 0


def now():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class HelloHandler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        # 关闭默认的访问日志，改为在 _handle 里打印自己的格式
        pass

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle(self):
        global counter
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""

        if self.path.startswith("/health"):
            self._json(200, {"status": "UP"})
            return

        if not self.path.startswith("/hello"):
            print(f"[{now()}] {self.command} {self.path} -> 404", flush=True)
            self._json(404, {"code": 404, "msg": "not found"})
            return

        counter += 1
        print(f"[{now()}] #{counter} hello world  <- {self.command} {self.path} from {self.client_address[0]}"
              + (f"  body={raw}" if raw else ""), flush=True)
        self._json(200, {"code": 200, "msg": "hello world", "seq": counter, "time": now(), "received": raw})

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8399
    server = ThreadingHTTPServer((host, port), HelloHandler)
    print(f"[{now()}] hello_service listening on http://{host}:{port}/hello  (Ctrl+C to stop)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print(f"\n[{now()}] hello_service stopped", flush=True)


if __name__ == "__main__":
    main()
