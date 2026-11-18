#!/usr/bin/env python3
"""v0.5 三轮独立复核；OBSERVED 表示复现限制或风险，不表示业务通过。原验证：若依+WP1+v0.5 共存、约束、幂等、生成列、视图、幂等重跑。

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
REPORT_DIR = Path(tempfile.mkdtemp(prefix="noon-v05-third-review-"))


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

        # ---- Codex REPRO 反例修复验证（6 项）----
        # 修复1 跨批次：同 biz_event_key 不同 batch_id 应允许（FIFO 一次销售跨多批）
        check("修复1: 同销售跨批次消耗被允许",
              q(f"{base_ins}('C-V1B','SALE:V1',2,1,1,10,10,NOW(),'SALE')", db).returncode == 0)
        # 修复2 退货语义：妥投10退货2合法；退货>妥投被拒
        item = "INSERT INTO sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,qty_delivered,qty_returned,unit_price) VALUES "
        check("修复2a: 妥投10退货2合法状态可插入",
              q(item + "(1,'O1','ITEM1',1,10,10,2,15)", db).returncode == 0)
        check("修复2b: 退货>妥投被 CHECK 拦截",
              q(item + "(1,'O2','ITEM2',1,10,5,6,15)", db).returncode != 0)
        # 修复3 平台行唯一：重复 platform_item_no 被拒
        check("修复3: 同平台订单行重复插入被拦截",
              q(item + "(1,'O1','ITEM1',1,10,0,0,15)", db).returncode != 0)
        # 修复4 跨站快照：AE 请求解析出 SA 被拒；GLOBAL 回退允许
        snap = ("INSERT INTO noon_config_snapshot(snapshot_batch_no,biz_type,config_key,version_id,"
                "requested_site,resolved_scope,value_snapshot,business_time,resolved_at) VALUES")
        check("修复4a: 跨站快照(AE→SA)被 CHECK 拦截",
              q(snap + "('BX','SAL_ORDER','x',1,'AE','SA','v',NOW(),NOW())", db).returncode != 0)
        check("修复4b: GLOBAL 回退快照被允许",
              q(snap + "('BG','SAL_ORDER','x',1,'AE','GLOBAL','v',NOW(),NOW())", db).returncode == 0)
        # 修复5 快照不可变：UPDATE 被触发器拦截
        check("修复5: 快照原地 UPDATE 被触发器拦截",
              q("UPDATE noon_config_snapshot SET value_snapshot='changed' WHERE snapshot_batch_no='BG'", db).returncode != 0)
        # 修复6 AI 闸门：INSERT 直接 CONVERTED 被拒；PENDING 允许
        ai = "INSERT INTO ai_suggestion(suggestion_no,scene,source_agent,payload_json,status,review_mode) VALUES "
        check("修复6a: AI 直接 INSERT CONVERTED 被闸门拦截",
              q(ai + "('S1','REPLENISH','agent','{}','CONVERTED','AUTO')", db).returncode != 0)
        check("修复6b: AI 新建 PENDING/MANUAL 被允许",
              q(ai + "('S2','REPLENISH','agent','{}','PENDING','MANUAL')", db).returncode == 0)

        # ---- Codex followup 二轮：5 项风险修复验证 ----
        # 1) 同一订单行多次部分妥投、同批次：不同事件 key 应允许；同事件重传被拦
        check("F1a: 多次部分妥投同批次(不同事件key)被允许",
              q(f"{base_ins}('C-PART-A','SALE:EVT-A',77,1,2,10,20,NOW(),'SALE')", db).returncode == 0)
        check("F1b: 第二次部分妥投同批次(新事件key)被允许",
              q(f"{base_ins}('C-PART-B','SALE:EVT-B',77,1,3,10,30,NOW(),'SALE')", db).returncode == 0)
        check("F1c: 同一妥投事件重传同批次被拦截",
              q(f"{base_ins}('C-PART-A2','SALE:EVT-A',77,1,2,10,20,NOW(),'SALE')", db).returncode != 0)
        # 2) 同一原消耗多次部分退货：不同退货事件可再次冲回同批；同事件重传被拦
        check("F2a: 首次部分退货冲回被允许",
              q(f"{base_ins}('C-REV-A','REV:EVT-RA',77,1,1,10,10,NOW(),'RETURN_REVERSAL')", db).returncode == 0)
        check("F2b: 第二次部分退货冲回同批(新事件key)被允许",
              q(f"{base_ins}('C-REV-B','REV:EVT-RB',77,1,1,10,10,NOW(),'RETURN_REVERSAL')", db).returncode == 0)
        check("F2c: 同一退货事件重传被拦截",
              q(f"{base_ins}('C-REV-A2','REV:EVT-RA',77,1,1,10,10,NOW(),'RETURN_REVERSAL')", db).returncode != 0)
        # 3) 平台行唯一作用域：不同订单复用同 platform_item_no 应允许；同订单重复被拦
        check("F3a: 不同订单复用平台行号被允许",
              q(item + "(502,'ORDER-B','ITEM1',1,5,0,0,10)", db).returncode == 0)
        check("F3b: 同订单重复平台行号仍被拦截",
              q(item + "(501,'ORDER-A','LOCAL-ITEM-1',1,5,0,0,10)", db).returncode == 0 and
              q(item + "(501,'ORDER-A','LOCAL-ITEM-1',1,5,0,0,10)", db).returncode != 0)
        # 4) 快照 DELETE 被触发器拦截
        check("F4: 快照物理 DELETE 被触发器拦截",
              q("DELETE FROM noon_config_snapshot WHERE snapshot_batch_no='BG'", db).returncode != 0)
        # 5) AI UPDATE 审批闸门
        check("F5a: PENDING→CONVERTED 无审核信息被拦截",
              q("UPDATE ai_suggestion SET status='CONVERTED',review_mode='AUTO' WHERE suggestion_no='S2'", db).returncode != 0)
        check("F5b: PENDING→APPROVED 带审核主体/时间被允许",
              q("UPDATE ai_suggestion SET status='APPROVED',reviewed_by=1,reviewed_at=NOW() WHERE suggestion_no='S2'", db).returncode == 0)
        check("F5c: APPROVED→CONVERTED 带审批依据被允许",
              q("UPDATE ai_suggestion SET status='CONVERTED',review_note='manual ok' WHERE suggestion_no='S2'", db).returncode == 0)
        check("F5d: CONVERTED 终态回退 PENDING 被拦截",
              q("UPDATE ai_suggestion SET status='PENDING' WHERE suggestion_no='S2'", db).returncode != 0)
        check("F5e: AUTO 无 auto_rule_ref 被拦截",
              q(ai + "('S3','REPLENISH','agent','{}','PENDING','MANUAL')", db).returncode == 0 and
              q("UPDATE ai_suggestion SET review_mode='AUTO' WHERE suggestion_no='S3'", db).returncode != 0)

        # 使用不同 fixture，观察真实数据库行为；不能将 root 结果当作应用权限结论。
        def duplicate(result):
            return result.returncode != 0 and "ERROR 1062" in result.stderr

        parent = "INSERT INTO sal_order(id,order_no,site_code,store_id,partner_sku,qty,gross_amount,currency) VALUES "
        check("REVIEW 同号订单 AE/SA 主表都可插入", q(parent + "(91001,'SAME-ORDER','AE',1,'SKU',1,10,'AED'),(91002,'SAME-ORDER','SA',1,'SKU',1,10,'SAR')",db).returncode == 0)
        child = "INSERT INTO sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,unit_price) VALUES "
        check("REVIEW AE 订单行可插入", q(child + "(91001,'SAME-ORDER','LINE-1',1,1,10)",db).returncode == 0)
        check("OBSERVED 同订单号跨站同平台行号被拒(1062)", duplicate(q(child + "(91002,'SAME-ORDER','LINE-1',1,1,10)",db)))
        check("REVIEW SA 另一行号可插入", q(child + "(91002,'SAME-ORDER','LINE-2',1,1,10)",db).returncode == 0)
        return_sql = "INSERT INTO sal_return(return_no,biz_return_key,source_return_no,order_no,sku_id,qty,sal_order_item_id) VALUES "
        check("REVIEW AE 退货来源号可插入", q(return_sql + "('RET-A','BR-A','SOURCE-1','SAME-ORDER',1,1,(SELECT id FROM sal_order_item WHERE sal_order_id=91001 LIMIT 1))",db).returncode == 0)
        check("OBSERVED 同订单号跨站同退货来源号被拒(1062)", duplicate(q(return_sql + "('RET-B','BR-B','SOURCE-1','SAME-ORDER',1,1,(SELECT id FROM sal_order_item WHERE sal_order_id=91002 LIMIT 1))",db)))
        sale = "INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,reversal_of_id) VALUES "
        check("REVIEW 原消耗数量3可插入", q(sale + "('R3-ORIGINAL','SALE:R3',91003,1,3,10,30,NOW(),'SALE',1,NULL)",db).returncode == 0)
        original_id = scalar("SELECT id FROM inv_batch_consumption WHERE consumption_no='R3-ORIGINAL'")
        first = "('R3-REV-1','REV:R3-1',91003,1,2,10,20,NOW(),'RETURN_REVERSAL',-1," + original_id + ")"
        second = "('R3-REV-2','REV:R3-2',91003,1,2,10,20,NOW(),'RETURN_REVERSAL',-1," + original_id + ")"
        check("REVIEW 两次不同事件冲回均可插入", q(sale + first + "," + second,db).returncode == 0)
        check("OBSERVED DDL允许累计红冲4大于原消耗3", scalar("SELECT SUM(qty) FROM inv_batch_consumption WHERE reversal_of_id=" + original_id) == "4")
        check("REVIEW 独立 PENDING 建议可插入", q("INSERT INTO ai_suggestion(suggestion_no,scene,source_agent,site_code,payload_json) VALUES ('THIRD-AI','REPLENISH','agent','AE','{}')",db).returncode == 0)
        check("REVIEW 按要求记录驳回信息可写入", q("UPDATE ai_suggestion SET status='REJECTED',reviewed_by=1,reviewed_at=NOW(),review_note='reject' WHERE suggestion_no='THIRD-AI'",db).returncode == 0)
        check("OBSERVED 驳回终态可被改为 APPROVED", q("UPDATE ai_suggestion SET status='APPROVED' WHERE suggestion_no='THIRD-AI'",db).returncode == 0 and scalar("SELECT status FROM ai_suggestion WHERE suggestion_no='THIRD-AI'") == "APPROVED")
        check("REVIEW 独立建议初态可插入", q("INSERT INTO ai_suggestion(suggestion_no,scene,source_agent,site_code,payload_json) VALUES ('THIRD-AUTO','REPLENISH','agent','AE','{}')",db).returncode == 0)
        check("OBSERVED 未校验规则引用可直接转 AUTO CONVERTED", q("UPDATE ai_suggestion SET status='CONVERTED',review_mode='AUTO',reviewed_by=1,reviewed_at=NOW(),auto_rule_ref='UNVERIFIED' WHERE suggestion_no='THIRD-AUTO'",db).returncode == 0)
        check("REVIEW 规则默认开关仍为 false", scalar("SELECT config_value FROM noon_rule_config WHERE config_key='ai_auto_review_enabled' AND scope='GLOBAL'") == "false")
        check("REVIEW 应用受限账号创建", q("CREATE USER 'noon_review_app'@'localhost' IDENTIFIED BY 'temporary-test-only'",db).returncode == 0)
        check("REVIEW 快照账号仅授 SELECT/INSERT", q("GRANT SELECT,INSERT ON noon_v05.noon_config_snapshot TO 'noon_review_app'@'localhost'",db).returncode == 0)
        restricted = run([mysql,"--no-defaults","--protocol=TCP","-h127.0.0.1","-P",str(port),"-unoon_review_app","noon_v05","-e","DELETE FROM noon_config_snapshot WHERE snapshot_batch_no='BG'"], env={**os.environ,"MYSQL_PWD":"temporary-test-only"})
        check("REVIEW 受限账号快照 DELETE 被权限拒绝(1142)", restricted.returncode != 0 and "ERROR 1142" in restricted.stderr)

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
