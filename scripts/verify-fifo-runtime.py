#!/usr/bin/env python3
"""NOON-20261121-FIFO 隔离验证：自建临时 MySQL，跑 MySqlFifoIT，然后清理。

仅连接 127.0.0.1；不读取应用数据源配置、不访问生产、不部署、不打印密码。
用法：
  python3 scripts/verify-fifo-runtime.py \
    --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
    --maven /tmp/apache-maven-3.9.11/bin/mvn \
    --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
"""
import argparse
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
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
    raise TimeoutError("测试服务未按时就绪")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-bin-dir", type=Path, required=True)
    parser.add_argument("--maven", required=True)
    parser.add_argument("--maven-settings", type=Path)
    parser.add_argument("--maven-repo", type=Path)
    args = parser.parse_args()
    for f in [args.mysql_bin_dir / "mysqld", args.mysql_bin_dir / "mysql"]:
        if not f.is_file():
            parser.error(f"缺少可执行文件：{f}")

    work = Path(tempfile.mkdtemp(prefix="noon-fifo-"))
    work.chmod(0o700)
    print(f"隔离验证目录：{work}", flush=True)
    port = free_port()
    password = secrets.token_hex(24)
    processes = []
    logs = []

    def run(command, **kwargs):
        return subprocess.run(command, check=True, text=True, capture_output=True, **kwargs).stdout

    def start(command, name):
        log = (work / f"{name}.log").open("w")
        logs.append(log)
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    mysql_env = {**os.environ, "MYSQL_PWD": password}
    mysql_cmd = [str(args.mysql_bin_dir / "mysql"), "--no-defaults", "--protocol=TCP",
                 "--host=127.0.0.1", f"--port={port}", "--user=root", "--batch", "--skip-column-names"]

    try:
        init = subprocess.run([str(args.mysql_bin_dir / "mysqld"), "--no-defaults", "--initialize-insecure",
                               f"--basedir={args.mysql_bin_dir.parent}", f"--datadir={work / 'mysql-data'}"],
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / "mysql-init.log").write_text(init.stdout)
        if init.returncode:
            raise RuntimeError("MySQL 初始化失败，见 mysql-init.log")
        server = start([str(args.mysql_bin_dir / "mysqld"), "--no-defaults",
                        f"--basedir={args.mysql_bin_dir.parent}", f"--datadir={work / 'mysql-data'}",
                        "--bind-address=127.0.0.1", f"--port={port}", f"--socket={work / 'mysql.sock'}",
                        "--mysqlx=0", "--skip-log-bin", "--default-time-zone=+08:00"], "mysql")
        empty_env = {k: v for k, v in os.environ.items() if k != "MYSQL_PWD"}

        def mysql_ready():
            probe = subprocess.run(mysql_cmd + ["-e", "select 1"], env=empty_env,
                                   capture_output=True, text=True)
            return probe.returncode == 0 and probe.stdout.strip() == "1"

        wait_until(mysql_ready, [server])
        run(mysql_cmd + ["-e", f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{password}';"], env=empty_env)

        runtime_env = {**os.environ, "NOON_MYSQL_TEST_PORT": str(port), "NOON_MYSQL_TEST_PASSWORD": password}
        command = [args.maven, "-B", "-ntp"]
        if args.maven_settings:
            command += ["-s", str(args.maven_settings)]
        if args.maven_repo:
            command += ["-Dmaven.repo.local=" + str(args.maven_repo)]
        command += ["-Dtest=MySqlFifoIT", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        report = ROOT / "yx-erp/yxerp-framework/target/surefire-reports/TEST-com.yx.framework.fifo.MySqlFifoIT.xml"
        report.unlink(missing_ok=True)
        with (work / "mysql-maven.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT / "yx-erp", env=runtime_env,
                                    stdout=log, stderr=subprocess.STDOUT)
        if not report.is_file():
            print("未生成测试报告，见 mysql-maven.log", flush=True)
            raise SystemExit(1)
        tree = ET.parse(report)
        suite = tree.getroot()
        tests = int(suite.get("tests", 0))
        errors = int(suite.get("errors", 0))
        failures = int(suite.get("failures", 0))
        skipped = int(suite.get("skipped", 0))
        print(f"MySQL JUnit: tests={tests} errors={errors} failures={failures} skipped={skipped}", flush=True)
        print(f"maven_exit={result.returncode}", flush=True)
        print(f"证据目录：{work}", flush=True)
        raise SystemExit(0 if result.returncode == 0 and errors == 0 and failures == 0 else 1)
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
