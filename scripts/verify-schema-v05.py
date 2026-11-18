#!/usr/bin/env python3
"""v0.5 schema 隔离 MySQL 实测：若依+WP1+v0.5 共存、约束、幂等、生成列、视图、幂等重跑。

用法：
  python3 scripts/verify-schema-v05.py \
    --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
    --port 55993

行为：在临时目录初始化一个一次性 mysqld（不碰任何生产库/已有实例），按真实二开顺序
  执行 ry_20260320.sql → noon_wp1_site_auth.sql → noon-erp-schema-v0.5.sql，
  再跑一组断言；结束自动关闭实例、保留报告到 docs/verification/schema-v05/。
"""
import argparse
import os
import shutil
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
RY = ROOT / "yx-erp/sql/ry_20260320.sql"
WP1 = ROOT / "yx-erp/sql/noon_wp1_site_auth.sql"
REPORT_DIR = ROOT / "docs/verification/schema-v05"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(cmd, **kw):
    return subprocess.run([str(x) for x in cmd], capture_output=True, text=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mysql-bin-dir", required=True)
    ap.add_argument("--port", type=int, default=0)
    args = ap.parse_args()
    bindir = Path(args.mysql_bin_dir)
    port = args.port or free_port()
    mysqld, mysql, mysqladmin = bindir / "mysqld", bindir / "mysql", bindir / "mysqladmin"
    for p in (mysqld, mysql, mysqladmin):
        if not p.exists():
            print(f"缺少工具: {p}", file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-v05-"))
    data = work / "data"
    data.mkdir()
    base = bindir.parent
    init = run([mysqld, "--no-defaults", "--initialize-insecure",
                f"--basedir={base}", f"--datadir={data}"])
    if init.returncode:
        print("初始化失败:", init.stderr[-1000:], file=sys.stderr)
        return 2

    proc = subprocess.Popen(
        [str(mysqld), "--no-defaults", f"--basedir={base}", f"--datadir={data}",
         "--bind-address=127.0.0.1", f"--port={port}", f"--socket={work/'mysql.sock'}",
         f"--pid-file={work/'mysql.pid'}", f"--log-error={work/'error.log'}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    cred = ["--no-defaults", "--protocol=TCP", "-h127.0.0.1", "-P", str(port), "-uroot"]

    def q(sql, db=None):
        return run([mysql, *cred, *([db] if db else []), "-e", sql])

    def feed(sqlfile, db):
        with open(sqlfile, "rb") as f:
            return run([mysql, *cred, db], stdin=f)

    checks, passed, failed = [], 0, 0

    def check(label, ok, detail=""):
        nonlocal passed, failed
        checks.append(f"{'PASS' if ok else 'FAIL'} | {label}" + (f" | {detail}" if detail else ""))
        if ok:
            passed += 1
        else:
            failed += 1

    try:
        for _ in range(60):
            if run([mysqladmin, *cred, "ping"]).returncode == 0:
                break
        else:
            print("MySQL 未就绪", file=sys.stderr)
            return 2

        db = "noon_v05"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)

        r1 = feed(RY, db)
        check("若依基础 SQL 执行", r1.returncode == 0, r1.stderr[-300:])
        r2 = feed(WP1, db)
        check("WP1 站点授权 SQL 执行", r2.returncode == 0, r2.stderr[-300:])
        r3 = feed(SCHEMA, db)
        check("schema v0.5 DDL 执行", r3.returncode == 0, r3.stderr[-600:])

        def scalar(sql):
            r = q(sql, db)
            return r.stdout.strip().splitlines()[-1] if r.returncode == 0 and r.stdout.strip() else ""

        tables = scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='noon_v05'")
        views = scalar("SELECT COUNT(*) FROM information_schema.views WHERE table_schema='noon_v05'")
        check("表数量=72（含若依+WP1+v0.5）", tables == "72", f"实际 {tables}")
        check("视图数量=5", views == "5", f"实际 {views}")

        # 若依表未被 v0.5 覆盖/误建
        own = scalar("SELECT GROUP_CONCAT(table_name ORDER BY table_name) FROM information_schema.tables "
                     "WHERE table_schema='noon_v05' AND table_name IN "
                     "('noon_rule_config','noon_rule_config_lock','noon_rule_config_audit',"
                     "'noon_config_snapshot','noon_audit_log')")
        check("v0.5 五张新表存在", own is not None and own.count(",") == 4, own)
        check("sys_audit_log 未误建（已更名 noon_audit_log）",
              scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='noon_v05' "
                     "AND table_name='sys_audit_log'") == "0")

        # 初始化数据
        check("noon_rule_config 初始化 15 行", scalar("SELECT COUNT(*) FROM noon_rule_config") == "15")
        check("锁行 45 行(15键×3scope)", scalar("SELECT COUNT(*) FROM noon_rule_config_lock") == "45")
        check("导出类别 12 行", scalar("SELECT COUNT(*) FROM int_export_category") == "12")

        # P0-1 幂等键
        base_ins = ("INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                    "unit_cost,amount,consumed_at,move_type) VALUES")
        check("首条消耗流水可插入",
              q(f"{base_ins}('C-V1','SALE:V1',1,1,1,10,10,NOW(),'SALE')", db).returncode == 0)
        check("P0-1 重复 biz_event_key 被唯一键拦截",
              q(f"{base_ins}('C-V2','SALE:V1',1,1,1,10,10,NOW(),'SALE')", db).returncode != 0)

        # CHECK 约束
        check("sign=0 被 CHECK 拦截",
              q(f"{base_ins}('C-V3','SALE:V3',1,1,1,10,10,NOW(),'SALE')".replace(
                  ",'SALE')", ",'SALE',0)").replace("move_type) VALUES", "move_type,sign) VALUES"), db).returncode != 0)
        check("config to<=from 被 CHECK 拦截",
              q("INSERT INTO noon_rule_config(config_key,scope,config_value,version_no,effective_from,effective_to,created_by) "
                "VALUES('k1','AE','x',1,'2026-01-01','2026-01-01',1)", db).returncode != 0)
        check("非法 scope 被 CHECK 拦截",
              q("INSERT INTO noon_rule_config(config_key,scope,config_value,version_no,effective_from,created_by) "
                "VALUES('k2','XX','x',1,'2026-01-01',1)", db).returncode != 0)

        # 生成列派生
        q("UPDATE int_export_category SET create_tested_at='2026-11-18 10:00:00' WHERE export_category_code='noon_catalog_catalogexport'", db)
        check("生成列: 仅一层验证 is_usable=0",
              scalar("SELECT is_usable FROM int_export_category WHERE export_category_code='noon_catalog_catalogexport'") == "0")
        q("UPDATE int_export_category SET headers_tested_at='2026-11-18 11:00:00' WHERE export_category_code='noon_catalog_catalogexport'", db)
        check("生成列: 两层验证 is_usable=1",
              scalar("SELECT is_usable FROM int_export_category WHERE export_category_code='noon_catalog_catalogexport'") == "1")

        # 视图拆分
        pv = scalar("SELECT COUNT(*) FROM information_schema.views WHERE table_schema='noon_v05' "
                    "AND table_name IN ('v_ai_profit_site','v_ai_profit_sku')")
        check("利润视图拆 SITE/SKU", pv == "2", f"实际 {pv}")

        # 幂等重跑
        r4 = feed(SCHEMA, db)
        check("v0.5 重复执行成功（幂等）", r4.returncode == 0, r4.stderr[-300:])
        check("重跑后锁行仍 45", scalar("SELECT COUNT(*) FROM noon_rule_config_lock") == "45")
        check("重跑后配置仍 15", scalar("SELECT COUNT(*) FROM noon_rule_config") == "15")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        report = REPORT_DIR / "verify-schema-v05-output.txt"
        lines = [f"# v0.5 schema 隔离 MySQL 实测报告", f"# MySQL: {run([mysqld,'--version']).stdout.strip()}",
                 f"# 命令: python3 scripts/verify-schema-v05.py --mysql-bin-dir {bindir}",
                 f"# 结果: PASS={passed} FAIL={failed}", ""]
        report.write_text("\n".join(lines + checks) + "\n", encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)
        print("\n".join(checks))
        print(f"PASS={passed} FAIL={failed}  报告: {report}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
