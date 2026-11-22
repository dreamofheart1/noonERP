#!/usr/bin/env python3
"""M2 浏览器验证用静态服务 + API 反向代理（一次性隔离环境）。

前端构建产物中 axios baseURL 未定义，接口走相对路径；本代理把非静态请求
原样转发到隔离后端，SPA 路由回退 index.html。仅用于验证，不连生产。
"""
import argparse
import http.server
import socketserver
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API_PREFIXES = ("login", "logout", "getInfo", "getRouters", "captchaImage", "register",
                "noon", "tool", "system", "monitor", "common", "dev-api", "prod-api")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist", required=True)
    ap.add_argument("--backend-port", required=True, type=int)
    ap.add_argument("--port", required=True, type=int)
    args = ap.parse_args()
    dist = Path(args.dist).resolve()
    backend = f"http://127.0.0.1:{args.backend_port}"

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(dist), **kw)

        def log_message(self, fmt, *vals):
            pass

        def _api(self):
            path = urllib.parse.urlsplit(self.path).path.lstrip("/")
            return any(path == p or path.startswith(p + "/") for p in API_PREFIXES)

        def _proxy(self):
            target = backend + self.path
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else None
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in ("host", "content-length", "connection")}
            req = urllib.request.Request(target, data=body, headers=headers, method=self.command)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    payload, status = r.read(), r.status
                    ct = r.headers.get("Content-Type", "application/json")
            except urllib.error.HTTPError as e:
                payload, status, ct = e.read(), e.code, e.headers.get("Content-Type", "application/json")
            except Exception as e:
                payload, status, ct = str(e).encode(), 502, "text/plain"
            self.send_response(status)
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            p = urllib.parse.urlsplit(self.path).path.lstrip("/")
            f = dist / p
            if self._api() or (p and not f.is_file() and "." in Path(p).name and not p.startswith("assets")):
                return self._proxy()
            if not p or not f.is_file():
                self.path = "/index.html"
            return super().do_GET()

        def do_POST(self):
            return self._proxy()

        def do_PUT(self):
            return self._proxy()

        def do_DELETE(self):
            return self._proxy()

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    with Server(("127.0.0.1", args.port), Handler) as srv:
        srv.serve_forever()


if __name__ == "__main__":
    main()
