#!/usr/bin/env python3
"""自建/清理临时 MySQL、Redis、应用进程；仅连接 127.0.0.1，不读取部署配置。

需预先提供官方 mysql-bin-dir、redis-bin-dir、已构建应用 jar、Maven。
输出仅含断言和日志目录，不打印 token/密码；不会下载或安装系统服务。
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_until(check, processes, timeout=75):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError("测试进程提前退出，请检查临时日志")
        try:
            if check():
                return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        time.sleep(0.25)
    raise TimeoutError("测试服务未按时就绪，请检查临时日志")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-bin-dir", type=Path, required=True)
    parser.add_argument("--redis-bin-dir", type=Path, required=True)
    parser.add_argument("--maven", required=True)
    parser.add_argument("--maven-settings", type=Path)
    parser.add_argument("--maven-repo", type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    jar = ROOT / "yx-erp/yxerp-admin/target/yxerp-admin.jar"
    if not jar.is_file():
        parser.error("先在 yx-erp 执行 mvn package，生成待验证应用包")
    for file in [args.mysql_bin_dir / "mysqld", args.mysql_bin_dir / "mysql",
                 args.redis_bin_dir / "redis-server", args.redis_bin_dir / "redis-cli"]:
        if not file.is_file():
            parser.error(f"缺少可执行文件：{file}")
    work = Path(tempfile.mkdtemp(prefix="noon-runtime-"))
    work.chmod(0o700)
    print(f"隔离验证目录：{work}", flush=True)
    mysql_port, redis_port, http_port = free_port(), free_port(), free_port()
    mysql_password, redis_password = secrets.token_hex(24), secrets.token_hex(24)
    processes, logs, results = [], [], []

    def run(command, **kwargs):
        return subprocess.run(command, check=True, text=True, capture_output=True, **kwargs).stdout

    def start(command, name, env=None):
        log = (work / f"{name}.log").open("w")
        logs.append(log)
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env)
        processes.append(process)
        return process

    mysql_env = {**os.environ, "MYSQL_PWD": mysql_password}
    redis_env = {**os.environ, "REDISCLI_AUTH": redis_password}
    mysql_command = [str(args.mysql_bin_dir / "mysql"), "--no-defaults", "--protocol=TCP",
                     "--host=127.0.0.1", f"--port={mysql_port}", "--user=root", "--batch", "--skip-column-names"]

    def sql(text, database=None):
        return run(mysql_command + ([database] if database else []), input=text, env=mysql_env).strip()

    def redis(*command):
        return run([str(args.redis_bin_dir / "redis-cli"), "-h", "127.0.0.1", "-p", str(redis_port),
                    "--raw", *command], env=redis_env).strip()

    def http(method, path, token=None, payload=None):
        headers = {"Content-Type": "application/json", "User-Agent": "Noon-Isolated-Test/1.0"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(f"http://127.0.0.1:{http_port}" + path, method=method,
                                         data=None if payload is None else json.dumps(payload).encode(), headers=headers)
        # 禁用代理：任何 HTTP 流量都必须直接到本机。
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(request, timeout=10) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            return json.load(error)

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        results.append(name)
        print("PASS " + name, flush=True)

    try:
        initialize = subprocess.run([str(args.mysql_bin_dir / "mysqld"), "--no-defaults", "--initialize-insecure",
                                     f"--basedir={args.mysql_bin_dir.parent}", f"--datadir={work / 'mysql-data'}"],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / "mysql-init.log").write_text(initialize.stdout)
        if initialize.returncode:
            raise RuntimeError("MySQL 初始化失败，见 mysql-init.log")
        mysql = start([str(args.mysql_bin_dir / "mysqld"), "--no-defaults",
                       f"--basedir={args.mysql_bin_dir.parent}", f"--datadir={work / 'mysql-data'}",
                       "--bind-address=127.0.0.1", f"--port={mysql_port}", f"--socket={work / 'mysql.sock'}",
                       "--mysqlx=0", "--skip-log-bin", "--default-time-zone=+08:00"], "mysql")
        # 仅此自行初始化实例使用空初始密码，立即设置随机凭据。
        empty_env = {key: value for key, value in os.environ.items() if key != "MYSQL_PWD"}
        wait_until(lambda: run(mysql_command, input="select 1", env=empty_env).strip() == "1", [mysql])
        run(mysql_command, input=f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{mysql_password}';", env=empty_env)
        redis_process = start([str(args.redis_bin_dir / "redis-server"), "--bind", "127.0.0.1", "--port", str(redis_port),
                               "--protected-mode", "yes", "--save", "", "--appendonly", "no",
                               "--requirepass", redis_password, "--dir", str(work)], "redis")
        wait_until(lambda: redis("PING") == "PONG", [redis_process])
        check("MySQL 8.x 与 Redis 实例就绪", sql("select version()").startswith("8.") and redis("PING") == "PONG")
        runtime_env = {**os.environ, "NOON_MYSQL_TEST_PORT": str(mysql_port),
                       "NOON_MYSQL_TEST_PASSWORD": mysql_password}
        command = [args.maven, "-B", "-ntp"]
        if args.maven_settings:
            command += ["-s", str(args.maven_settings)]
        if args.maven_repo:
            command += ["-Dmaven.repo.local=" + str(args.maven_repo)]
        command += ["-Dtest=MySqlSiteAuthorizationIT", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        # 原生套件：继承 14 项 + RR/确定性锁等待 2 项，各用独立数据库。
        with (work / "mysql-maven.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT / "yx-erp", env=runtime_env, stdout=log, stderr=subprocess.STDOUT)
        report = ROOT / "yx-erp/yxerp-framework/target/surefire-reports/TEST-com.yx.framework.security.site.MySqlSiteAuthorizationIT.xml"
        suite = ET.parse(report).getroot()
        check("原生 MySQL 16 项权限、回滚与 RR 锁等待测试",
              result.returncode == 0 and suite.get("tests") == "16" and suite.get("failures") == "0"
              and suite.get("errors") == "0" and suite.get("skipped") == "0")
        sql("CREATE DATABASE noon_runtime character set utf8mb4;")
        sql((ROOT / "yx-erp/sql/ry_20260320.sql").read_text(), "noon_runtime")
        before = sql("select count(*) from sys_user", "noon_runtime")
        sql((ROOT / "yx-erp/sql/noon_wp1_site_auth.sql").read_text(), "noon_runtime")
        check("若依完整初始化 + 站点原生迁移保留用户", before == sql("select count(*) from sys_user", "noon_runtime"))
        duplicate = subprocess.run(mysql_command + ["noon_runtime"], env=mysql_env, text=True, capture_output=True,
                                   input=(ROOT / "yx-erp/sql/noon_wp1_site_auth.sql").read_text())
        check("重复迁移明确失败且保留原用户", duplicate.returncode != 0
              and sql("select count(*) from sys_user", "noon_runtime") == before)
        sql("update sys_job set status='1';", "noon_runtime")
        # 避免加载仓库 druid 外部数据源配置；命令行只启用独立 profile，配置全部使用本机。
        config = work / "application-noon-runtime.properties"
        config.write_text(f"""server.address=127.0.0.1
server.port={http_port}
server.tomcat.threads.min-spare=2
server.tomcat.threads.max=16
ruoyi.profile={work / 'uploads'}
spring.datasource.druid.master.url=jdbc:mysql://127.0.0.1:{mysql_port}/noon_runtime?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
spring.datasource.druid.master.username=root
spring.datasource.druid.master.password={mysql_password}
spring.datasource.druid.slave.enabled=false
spring.datasource.druid.initialSize=1
spring.datasource.druid.minIdle=1
spring.datasource.druid.maxActive=6
spring.datasource.driverClassName=com.mysql.cj.jdbc.Driver
spring.datasource.druid.maxWait=10000
spring.datasource.druid.connectTimeout=5000
spring.datasource.druid.socketTimeout=10000
spring.datasource.druid.timeBetweenEvictionRunsMillis=60000
spring.datasource.druid.minEvictableIdleTimeMillis=300000
spring.datasource.druid.maxEvictableIdleTimeMillis=900000
spring.datasource.druid.validationQuery=SELECT 1
spring.datasource.druid.testWhileIdle=true
spring.datasource.druid.testOnBorrow=false
spring.datasource.druid.testOnReturn=false
spring.datasource.druid.statViewServlet.enabled=false
spring.data.redis.host=127.0.0.1
spring.data.redis.port={redis_port}
spring.data.redis.password={redis_password}
spring.data.redis.database=0
spring.quartz.auto-startup=false
spring.devtools.restart.enabled=false
token.secret={secrets.token_hex(48)}
logging.config=file:{work / 'console-logback.xml'}
logging.level.com.yx=info
""")
        config.chmod(0o600)
        (work / "console-logback.xml").write_text('''<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%d %p %logger{30} %msg%n</pattern></encoder></appender><root level="INFO"><appender-ref ref="CONSOLE"/></root></configuration>''')
        application = start([args.java, "-Dlogback.configurationFile=" + str(work / "console-logback.xml"),
                             "-jar", str(jar), "--spring.profiles.active=noon-runtime",
                             "--spring.config.location=classpath:application.yml,file:" + str(config)], "application",
                            env={key: value for key, value in os.environ.items()
                                 if not key.startswith(("SPRING_", "SERVER_", "TOKEN_", "LOGGING_"))
                                 and key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")})
        wait_until(lambda: http("GET", "/captchaImage").get("code") == 200, [application], timeout=90)

        def login(username):
            captcha = http("GET", "/captchaImage")
            code = json.loads(redis("GET", "captcha_codes:" + captcha["uuid"]))
            reply = http("POST", "/login", payload={"username": username, "password": "admin123",
                                                     "uuid": captcha["uuid"], "code": str(code)})
            if reply.get("code") != 200 or "token" not in reply:
                raise AssertionError("真实验证码/密码登录失败：" + username + ": " + str(reply.get("msg")))
            return reply["token"]

        check("未登录受保护接口拒绝", http("GET", "/noon/sites").get("code") == 401)
        admin, user = login("admin"), login("ry")
        check("登录 JWT 已在真实 Redis 存储会话", len(redis("KEYS", "login_tokens:*").splitlines()) == 2)
        check("超管无需关联行拥有 AE/SA", http("GET", "/noon/sites", admin).get("data") == ["AE", "SA"])
        check("普通用户空授权", http("GET", "/noon/sites", user).get("data") == [])
        check("普通用户不能修改自己的授权", http("PUT", "/noon/site-grants/2", user, ["SA"]).get("code") == 403)
        check("超管授权 AE", http("PUT", "/noon/site-grants/2", admin, ["AE"]).get("code") == 200)
        check("原登录态立即读取 AE", http("GET", "/noon/sites", user).get("data") == ["AE"])
        check("非法站点不改变旧授权", http("PUT", "/noon/site-grants/2", admin, ["XX"]).get("code") == 400
              and http("GET", "/noon/sites", user).get("data") == ["AE"])
        check("超管不能被覆盖", http("PUT", "/noon/site-grants/1", admin, []).get("code") == 400)
        check("撤权后旧 JWT 无站点", http("PUT", "/noon/site-grants/2", admin, []).get("code") == 200
              and http("GET", "/noon/sites", user).get("data") == [])
        check("授权审计前后值落库", sql("select concat(before_codes,'>',after_codes) from sys_user_site_audit order by id", "noon_runtime").splitlines() == [">AE", "AE>"])
        sql("update sys_user set status='1' where user_id=2", "noon_runtime")
        check("账号停用后旧 JWT 被站点闸门拒绝", http("GET", "/noon/sites", user).get("code") == 403)
        sql("update sys_user set status='0' where user_id=2", "noon_runtime")
        parts = admin.split(".")
        parts[1] = base64.urlsafe_b64encode(b'{"login_user_key":"forged"}').decode().rstrip("=")
        check("篡改签名 JWT 拒绝", http("GET", "/noon/sites", ".".join(parts)).get("code") == 401)
        check("退出清除 Redis 登录态", http("POST", "/logout", user).get("code") == 200
              and http("GET", "/noon/sites", user).get("code") == 401)
        # 模拟会话自然到期：只缩短本次管理员会话键 TTL，不清库。
        admin_keys = redis("KEYS", "login_tokens:*").splitlines()
        check("剩余会话只有本次超管", len(admin_keys) == 1)
        redis("EXPIRE", admin_keys[0], "1")
        time.sleep(1.2)
        check("Redis 会话到期后 JWT 拒绝", http("GET", "/noon/sites", admin).get("code") == 401)
        (work / "result.json").write_text(json.dumps({"passed": results, "mysql": sql("select version()"),
                                                      "redis": redis("INFO", "server").splitlines()[1]}, ensure_ascii=False, indent=2))
        print(f"验证完成：{len(results)} 项运行断言；MySQL JUnit 结果见 mysql-maven.log。", flush=True)
    finally:
        # 只终止由本脚本 Popen 创建的进程，不扫描/终止用户已有服务，不删除既有数据库。
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in logs:
            log.close()
        print(f"本次临时进程已停止；证据保留：{work}", flush=True)


if __name__ == "__main__":
    main()
