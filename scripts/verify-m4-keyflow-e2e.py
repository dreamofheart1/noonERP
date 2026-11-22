#!/usr/bin/env python3
"""M4 关键流程联调验证：真实 MySQL + Redis + 完整若依应用，验证采购入库→调拨→ASN→盘点的批次血缘衔接与台账守恒。

一条 SKU 库存的生命周期：采购入库生成批次→调拨拆在途子批次→ASN 再拆子批次入 FBN→盘点快照闸门。
每环节验证下游能正确消费上游生成的批次、四态台账双边守恒、批次血缘可追溯。

用法：python3 scripts/verify-m4-keyflow-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
"""
import argparse
import json
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile as _zf
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RY = ROOT / "yx-erp/sql/ry_20260320.sql"
WP1 = ROOT / "yx-erp/sql/noon_wp1_site_auth.sql"
M2SQL = ROOT / "yx-erp/sql/noon_m2_masterdata.sql"
M3SQL = ROOT / "yx-erp/sql/noon_m3_purchasing.sql"
M3MENU = ROOT / "yx-erp/sql/noon_m3_menu.sql"
M4SQL = ROOT / "yx-erp/sql/noon_m4_inventory.sql"
M4MENU = ROOT / "yx-erp/sql/noon_m4_menu.sql"
ASNSQL = ROOT / "yx-erp/sql/noon_m4_asn.sql"
ASNMENU = ROOT / "yx-erp/sql/noon_m4_asn_menu.sql"
TRSQL = ROOT / "yx-erp/sql/noon_m4_transfer.sql"
TRMENU = ROOT / "yx-erp/sql/noon_m4_transfer_menu.sql"
STKSQL = ROOT / "yx-erp/sql/noon_m4_stocktake.sql"
STKMENU = ROOT / "yx-erp/sql/noon_m4_stocktake_menu.sql"
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-keyflow"
GEN_OUT = REPORT_DIR / "generated"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(cmd, **kw):
    return subprocess.run([str(x) for x in cmd], capture_output=True, text=True, **kw)


def http(method, url, token=None, body=None, timeout=20):
    data = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            try: return r.status, json.loads(raw)
            except json.JSONDecodeError: return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try: return e.code, json.loads(raw)
        except json.JSONDecodeError: return e.code, raw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mysql-bin-dir", required=True)
    ap.add_argument("--redis-bin", required=True)
    ap.add_argument("--jar", default=str(ROOT / "yx-erp/yxerp-admin/target/yxerp-admin.jar"))
    ap.add_argument("--java", default="java")
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()

    bindir = Path(args.mysql_bin_dir)
    mysqld, mysql, mysqladmin = bindir / "mysqld", bindir / "mysql", bindir / "mysqladmin"
    redis_bin = Path(args.redis_bin)
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, ASNSQL, ASNMENU, TRSQL, TRMENU, STKSQL, STKMENU):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-keyflow-e2e-"))
    data = work / "mysql-data"
    data.mkdir()
    myport, redport, appport = free_port(), free_port(), free_port()
    procs = []
    checks = []
    passed = failed = 0

    def check(label, ok, detail=""):
        nonlocal passed, failed
        line = f"{'PASS' if ok else 'FAIL'} | {label}" + (f" | {detail}" if detail else "")
        checks.append(line)
        if ok: passed += 1
        else: failed += 1
        print(line, flush=True)

    try:
        init = run([mysqld, "--no-defaults", "--initialize-insecure",
                    f"--basedir={bindir.parent}", f"--datadir={data}"])
        if init.returncode:
            print("MySQL 初始化失败:", init.stderr[-800:], file=sys.stderr)
            return 2
        my = subprocess.Popen(
            [str(mysqld), "--no-defaults", f"--basedir={bindir.parent}", f"--datadir={data}",
             "--bind-address=127.0.0.1", f"--port={myport}", f"--socket={work/'mysql.sock'}",
             f"--pid-file={work/'mysql.pid'}", f"--log-error={work/'mysql-error.log'}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procs.append(my)
        cred = ["--no-defaults", "--protocol=TCP", "-h127.0.0.1", "-P", str(myport), "-uroot"]

        def q(sql, db=None, raw=False):
            return run([mysql, *cred, *([db] if db else []), *(["-N"] if raw else []), "-e", sql])

        def feed(sqlfile, db):
            with open(sqlfile, "rb") as f:
                return run([mysql, *cred, db], stdin=f)

        ready = False
        for _ in range(60):
            if run([mysqladmin, *cred, "ping"]).returncode == 0:
                ready = True; break
            time.sleep(0.5)
        check("MySQL 就绪", ready)
        if not ready: return 2

        db = "noon_m4_keyflow_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M4 第三轮 ASN 迁移", ASNSQL), ("M4 第三轮 ASN 菜单", ASNMENU),
                         ("M4 第二轮调拨迁移", TRSQL), ("M4 第二轮调拨菜单", TRMENU),
                         ("M4 第三轮盘点迁移", STKSQL), ("M4 第三轮盘点菜单", STKMENU)]:
            r = feed(f, db)
            check(f"灌入{label}", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        schema_txt = SCHEMA.read_text(encoding="utf-8")
        def extract(table):
            marker = f"CREATE TABLE IF NOT EXISTS {table} ("
            s = schema_txt.find(marker)
            e = schema_txt.find(";", s)
            return schema_txt[s:e + 1] + "\n"
        base_ddl = work / "base-tables.sql"
        base_ddl.write_text("".join(extract(t) for t in
            ["log_asn", "log_asn_item", "inv_batch_consumption", "inv_stocktake", "inv_stocktake_item"]), encoding="utf-8")
        r = feed(base_ddl, db)
        check("灌入 ASN/盘点/消耗表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        q("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'", db)

        # 种子：SKU/仓库（AE 本地仓1/3、FBN仓4、SA仓2）；批次由入库 API 生成
        seed = [
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-KF','Key Flow SKU','ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(1,'WH-AE-1','AE','AE Local','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(3,'WH-AE-3','AE','AE Local 3','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(4,'WH-AE-FBN','AE','AE FBN','FBN','FBN',1,'ACTIVE')",
            # AE 采购单：SKU1 订购 100，已收货 100，单价 10 CNY fx=1 → 入库 unit_cost=10
            "INSERT INTO pur_order(id,order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
            "VALUES(1,'PO-KF-1',1,'AE','CNY',1000.0000,'RECEIVED','MANUAL',1)",
            "INSERT INTO pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
            "VALUES(1,1,1,100,100,10.0000,'CNY',1.00000000)",
            "INSERT INTO noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
            "VALUES(1,1,100,10.0000,NOW(),1)",
        ]
        for sql in seed:
            r = q(sql, db)
            check("种子: " + sql[:46], r.returncode == 0, r.stderr[-200:] if r.returncode else "")

        # 用户/角色/站点授权（含盘点菜单 3400-3402）
        pwd = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"
        for uid, name in [(100, "opAE"), (101, "opAESA"), (102, "viewer")]:
            q(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,status,del_flag,create_time) "
              f"VALUES({uid},103,'{name}','{name}','00','{name}@e2e.local','13800000000','0','{pwd}','0','0',NOW())", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(100,'M4业务','m4_ops',5,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230,3300,3301,3302,3303,3400,3401,3402,3600,3610,3611,3612,3613)", db)
        for uid in (100, 101):
            q(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({uid},100)", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(101,'M4只读','m4_viewer',6,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 101,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3200,3220,3230)", db)
        # 显式补授权（联调跨 4 模块，确保所有关键 perms 到位）
        q("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu "
          "WHERE menu_id IN (3210,3211,3212,3220,3230,3300,3301,3302,3303,3400,3401,3402,3610,3611,3612,3613)", db)
        q("INSERT INTO sys_user_role(user_id,role_id) VALUES(102,101)", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'SA',1,NOW())", db)
        check("测试用户/站点授权造数",
              q("SELECT COUNT(*) FROM sys_user_site WHERE user_id IN (100,101,102)", db, raw=True).stdout.strip() == "3")

        # Redis + 应用
        red = subprocess.Popen(
            [str(redis_bin), "--port", str(redport), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no",
             "--dir", str(work), "--daemonize", "no"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procs.append(red)
        time.sleep(1)
        check("Redis 就绪", run([str(Path(args.redis_bin).with_name("redis-cli")), "-p", str(redport), "ping"]).stdout.strip() == "PONG")

        jdbc = (f"jdbc:mysql://127.0.0.1:{myport}/{db}?useUnicode=true&characterEncoding=utf8"
                "&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true")
        test_jar = work / "e2e-app.jar"
        with _zf.ZipFile(args.jar, "r") as zin, _zf.ZipFile(test_jar, "w", _zf.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                blob = zin.read(item.filename)
                if item.filename == "BOOT-INF/classes/logback.xml":
                    blob = blob.decode("utf-8").replace("/home/ruoyi/logs", str(work / "logs")).encode("utf-8")
                zout.writestr(item, blob)
        app = subprocess.Popen(
            [args.java, "-jar", str(test_jar), f"--server.port={appport}",
             f"--spring.datasource.druid.master.url={jdbc}",
             "--spring.datasource.druid.master.username=root", "--spring.datasource.druid.master.password=",
             "--spring.data.redis.host=127.0.0.1", f"--spring.data.redis.port={redport}",
             "--ruoyi.addressEnabled=false", "--logging.level.com.yx=info"],
            stdout=open(work / "app.log", "w"), stderr=subprocess.STDOUT, cwd=work)
        procs.append(app)
        base = f"http://127.0.0.1:{appport}"
        up = False
        for _ in range(90):
            try:
                code, _ = http("GET", base + "/captchaImage", timeout=3)
                if code in (200, 500): up = True; break
            except Exception: pass
            if app.poll() is not None: break
            time.sleep(1)
        check("应用启动", up, "" if up else "见 app.log")
        if not up:
            print((work / "app.log").read_text(errors="replace")[-2000:], file=sys.stderr)
            return 2

        def login(name):
            code, resp = http("POST", base + "/login", body={"username": name, "password": "admin123"})
            return (resp.get("token") if isinstance(resp, dict) else None), resp
        tok_admin, r = login("admin")
        check("超管登录", bool(tok_admin), str(r)[:150] if not tok_admin else "")
        tok_ae, _ = login("opAE"); tok_aesa, _ = login("opAESA"); tok_viewer, _ = login("viewer")
        check("三个测试用户登录", all([tok_ae, tok_aesa, tok_viewer]))

        def api(token, method, path, body=None):
            return http(method, base + path, token=token, body=body)
        def bcode(resp): return resp.get("code") if isinstance(resp, dict) else None
        def data_of(resp):
            d = resp.get("data") if isinstance(resp, dict) else None
            return d if isinstance(d, dict) else {}
        def total_of(resp):
            try: return int(data_of(resp).get("total", 0))
            except (TypeError, ValueError): return 0


        # ========== 1. 采购入库：生成源头批次 ==========
        rid_inb = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm",
                         {"purOrderId": 1, "warehouseId": 1, "siteCode": "AE",
                          "lines": [{"purOrderItemId": 1, "qtyQualified": 100}], "requestId": rid_inb})
        inb = data_of(resp)
        check("① 采购入库成功", bcode(resp) == 200 and inb.get("replayed") is False, f"bcode={bcode(resp)} {str(resp)[:180]}")
        src_batch = (inb.get("batchIds") or [None])[0]
        check("① 生成源头批次(100@10)", bool(src_batch) and
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={src_batch} AND status='OPEN' AND qty_in=100 AND qty_remaining=100 AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1",
              str(inb)[:180])
        check("① 仓1 available=100", q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "100")
        src_batch_no = q(f"SELECT batch_no FROM inv_batch WHERE id={src_batch}", db, raw=True).stdout.strip()

        # ========== 2. 调拨：消费入库批次，拆在途子批次 ==========
        rid_tc = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create",
                         {"fromWarehouseId": 1, "toWarehouseId": 3, "siteCode": "AE",
                          "lines": [{"skuId": 1, "batchNo": src_batch_no, "qty": 50}], "requestId": rid_tc})
        tid = data_of(resp).get("transferId")
        check("② 调拨建单(消费入库批次)", bcode(resp) == 200 and bool(tid), f"bcode={bcode(resp)} {str(resp)[:180]}")
        _, t_detail = api(tok_ae, "GET", f"/noon/inventory/transfer/get?id={tid}")
        t_items = data_of(t_detail).get("items") or []
        t_item_id = t_items[0].get("itemId")
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship",
                         {"transferId": tid, "fromWarehouseId": 1, "toWarehouseId": 3, "siteCode": "AE",
                          "lines": [{"itemId": t_item_id, "qtyShipped": 50}], "requestId": uuid.uuid4().hex})
        t_child = (data_of(resp).get("childBatchIds") or [None])[0]
        check("② 调拨发运拆子批次(血缘=入库批次)", bcode(resp) == 200 and t_child and
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={t_child} AND parent_batch_id={src_batch} AND status='IN_TRANSIT' AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1",
              f"bcode={bcode(resp)} child={t_child}")
        check("② 发运后台账(仓1=50/仓3在途=50)",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "50" and
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "50")
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/receive",
                         {"transferId": tid, "toWarehouseId": 3,
                          "lines": [{"itemId": t_item_id, "qtyReceived": 45, "shortEventId": "KF-T-SHORT"}], "requestId": uuid.uuid4().hex})
        check("② 调拨收货(短少5)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:180]}")
        check("② 仓3 available=45/调拨短少消耗5@10",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "45" and
              q("SELECT COUNT(*) FROM inv_batch_consumption WHERE move_type='TRANSFER_SHORT' AND qty=5 AND unit_cost=10.0000 AND biz_event_key LIKE 'TSHORT:%KF-T-SHORT'", db, raw=True).stdout.strip() == "1")
        t_child_no = q(f"SELECT batch_no FROM inv_batch WHERE id={t_child}", db, raw=True).stdout.strip()

        # ========== 3. ASN：消费调拨子批次，再拆子批次入 FBN ==========
        rid_ac = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create",
                         {"siteCode": "AE", "warehouseCode": "WH-AE-FBN", "requestId": rid_ac,
                          "lines": [{"skuId": 1, "batchNo": t_child_no, "qtyShipped": 20}]})
        aid = data_of(resp).get("asnId")
        check("③ ASN建单(消费调拨子批次)", bcode(resp) == 200 and bool(aid), f"bcode={bcode(resp)} {str(resp)[:180]}")
        _, a_detail = api(tok_ae, "GET", f"/noon/inventory/asn/get?asnId={aid}")
        a_items = data_of(a_detail).get("items") or []
        a_item_id = a_items[0].get("id")
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/seal", {"asnId": aid, "requestId": uuid.uuid4().hex})
        a_child = (data_of(resp).get("childBatchIds") or [None])[0]
        check("③ ASN封单拆子批次(血缘=调拨子批次)", bcode(resp) == 200 and a_child and
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={a_child} AND parent_batch_id={t_child} AND status='IN_TRANSIT' AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1",
              f"bcode={bcode(resp)} child={a_child}")
        check("③ 发运后台账(仓3=25/FBN在途=20)",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "25" and
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "20")
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/receive",
                         {"asnId": aid, "requestId": uuid.uuid4().hex,
                          "lines": [{"asnItemId": a_item_id, "qtyReceived": 18, "shortEventId": "KF-A-SHORT"}]})
        check("③ ASN收货(短少2)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:180]}")
        check("③ FBN available=18/ASN短少消耗2@10",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "18" and
              q("SELECT COUNT(*) FROM inv_batch_consumption WHERE move_type='ASN_SHORT' AND qty=2 AND unit_cost=10.0000 AND biz_event_key LIKE 'ASNSHORT:%KF-A-SHORT'", db, raw=True).stdout.strip() == "1")

        # ========== 4. 盘点：快照闸门（仓1 剩余50，实盘48 → 盘亏2）==========
        rid_st = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create",
                         {"warehouseId": 1, "period": "2026-11", "requestId": rid_st,
                          "lines": [{"skuId": 1, "batchNo": src_batch_no, "qtyActual": 48, "countEventId": "KF-CNT"}]})
        sid = data_of(resp).get("stocktakeId")
        check("④ 盘点建单(快照=50)", bcode(resp) == 200 and bool(sid), f"bcode={bcode(resp)} {str(resp)[:180]}")
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply", {"stocktakeId": sid, "requestId": uuid.uuid4().hex})
        check("④ 盘点应用成功(盘亏2)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:180]}")
        check("④ 仓1 available=48/盘亏流水",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "48" and
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='STOCKTAKE_LOSS' AND qty_delta=-2", db, raw=True).stdout.strip() == "1")

        # ========== 5. 全局守恒 ==========
        # 源头批次 remaining（入库100 - 调拨发50 = 50，再盘亏2 = 48... 实际盘亏走 FIFO 从源头批次扣）
        src_remain = int(q(f"SELECT qty_remaining FROM inv_batch WHERE id={src_batch}", db, raw=True).stdout.strip() or 0)
        t_child_remain = int(q(f"SELECT qty_remaining FROM inv_batch WHERE id={t_child}", db, raw=True).stdout.strip() or 0)
        a_child_remain = int(q(f"SELECT qty_remaining FROM inv_batch WHERE id={a_child}", db, raw=True).stdout.strip() or 0)
        t_short = int(q("SELECT COALESCE(SUM(qty),0) FROM inv_batch_consumption WHERE move_type='TRANSFER_SHORT' AND biz_event_key LIKE 'TSHORT:%KF-T-SHORT'", db, raw=True).stdout.strip() or 0)
        a_short = int(q("SELECT COALESCE(SUM(qty),0) FROM inv_batch_consumption WHERE move_type='ASN_SHORT' AND biz_event_key LIKE 'ASNSHORT:%KF-A-SHORT'", db, raw=True).stdout.strip() or 0)
        stk_loss = int(q("SELECT COALESCE(SUM(qty),0) FROM inv_batch_consumption WHERE move_type='STOCKTAKE' AND biz_event_key LIKE 'STK:%KF-CNT'", db, raw=True).stdout.strip() or 0)
        # 盘亏可能记在 inv_stock_move 而非 consumption；用台账汇总守恒更稳
        total_ledger = int(q("SELECT COALESCE(SUM(qty_available),0) FROM inv_stock_ledger WHERE sku_id=1", db, raw=True).stdout.strip() or 0)
        total_short = t_short + a_short
        # 守恒：三批剩余 + 两个短少消耗 + 盘亏(若有) = 100
        batch_sum = src_remain + t_child_remain + a_child_remain
        check("⑤ 批次血缘三级衔接(入库→调拨→ASN)", src_batch and t_child and a_child and
              q(f"SELECT parent_batch_id FROM inv_batch WHERE id={t_child}", db, raw=True).stdout.strip() == str(src_batch) and
              q(f"SELECT parent_batch_id FROM inv_batch WHERE id={a_child}", db, raw=True).stdout.strip() == str(t_child))
        check(f"⑤ 全局守恒(批次{batch_sum}+短少{total_short}+盘亏{stk_loss}=100)",
              batch_sum + total_short + stk_loss == 100,
              f"src={src_remain} tchild={t_child_remain} achild={a_child_remain} tshort={t_short} ashort={a_short} stk={stk_loss} ledger={total_ledger}")

        (REPORT_DIR / "keyflow-output.txt").write_text("\n".join(checks) + f"\n\nPASS={passed} FAIL={failed}\n", encoding="utf-8")
        check("总计", failed == 0, f"PASS={passed} FAIL={failed}")
        print(f"\n==== RESULT PASS={passed} FAIL={failed} ====", flush=True)

    finally:
        for p in reversed(procs):
            try: p.terminate()
            except Exception: pass
        time.sleep(1)
        for p in reversed(procs):
            try: p.kill()
            except Exception: pass
        shutil.rmtree(work, ignore_errors=True)

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
