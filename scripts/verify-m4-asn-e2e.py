#!/usr/bin/env python3
"""M4 库存第三轮第三批「FBN 入仓 ASN 收货闭环」隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦口径：create 只登记→seal 扣源仓拆在途子批次→receive 在途转可用+短少 ASN_SHORT；
四态台账双边守恒、子批次血缘、幂等三态（CREATE/SEAL/RECEIVE 隔离）、站点授权；
生成器实调 log_asn；真实浏览器 UI 走 create→seal→receive。

用法：python3 scripts/verify-m4-asn-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
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
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-asn"
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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, ASNSQL, ASNMENU):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-asn-e2e-"))
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

        db = "noon_m4_asn_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M4 第三轮 ASN 迁移", ASNSQL), ("M4 第三轮 ASN 菜单", ASNMENU)]:
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
            ["log_asn", "log_asn_item", "inv_batch_consumption"]), encoding="utf-8")
        r = feed(base_ddl, db)
        check("灌入 ASN 业务/FIFO消耗表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        q("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'", db)

        # 种子：SKU/仓库（AE 两仓 + SA 跨站仓）/批次/台账
        seed = [
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-P1','Part One','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(2,'SKU-P2','Part Two','ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(1,'WH-AE-1','AE','AE Local','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(3,'WH-AE-3','AE','AE Local 3','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(2,'WH-SA-1','SA','SA Local','LOCAL','FBP',0,'ACTIVE')",
            # FBN 目标仓（ASN 收货目标，is_fbn=1）
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(4,'WH-AE-FBN','AE','AE FBN','FBN','FBN',1,'ACTIVE')",
            # 跨站 FBN 仓（opAE 越权测试用）
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(5,'WH-SA-FBN','SA','SA FBN','FBN','FBN',1,'ACTIVE')",
            # SKU1 仓1 两批：先入 60@10，后入 40@20 → 加权平均 14；盘亏按 FIFO 先扣批1
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(1,'B:SEED-1',1,NULL,1,'AE','2026-11-22 09:00:00',60,60,10.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(2,'B:SEED-2',1,NULL,1,'AE','2026-11-22 09:10:00',40,40,20.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            "INSERT INTO inv_stock_ledger(id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit,qty_reserved,qty_unsellable) "
            "VALUES(1,1,1,'AE',100,0,0,0)",
            # SKU2 仓1 一批 20@7（浏览器盘盈用：无其它批次则加权=7）
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(3,'B:SEED-BR',2,NULL,1,'AE','2026-11-22 09:30:00',20,20,7.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            "INSERT INTO inv_stock_ledger(id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit,qty_reserved,qty_unsellable) "
            "VALUES(2,2,1,'AE',20,0,0,0)",
            # SKU3 SA 仓源批次（授权测试：opAESA 建 SA ASN）
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(3,'SKU-SA','Part SA','ACTIVE')",
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(4,'B:SEED-SA',3,NULL,2,'SA','2026-11-22 09:40:00',20,20,5.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            "INSERT INTO inv_stock_ledger(id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit,qty_reserved,qty_unsellable) "
            "VALUES(3,3,2,'SA',20,0,0,0)",
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
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230,3600,3610,3611,3612,3613)", db)
        for uid in (100, 101):
            q(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({uid},100)", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(101,'M4只读','m4_viewer',6,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 101,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3200,3220,3230)", db)
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


        # ========== 0. 生成器：实际运行（log_asn，采用见 ADOPTION.md）==========
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        code, resp = api(tok_admin, "POST", "/tool/gen/importTable?tables=log_asn&tplWebType=element-plus")
        check("生成器导入 log_asn", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        code, resp = api(tok_admin, "PUT", "/tool/gen", body={
            "tableName": "log_asn", "tableComment": "FBN入仓ASN", "className": "Asn",
            "packageName": "com.yx.noon", "moduleName": "inbound", "businessName": "asn",
            "functionName": "ASN入仓", "tplCategory": "crud", "tplWebType": "element-plus",
            "genType": "0", "functionAuthor": "WorkBuddy"})
        check("生成器配置 log_asn", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        try:
            req = urllib.request.Request(base + "/tool/gen/batchGenCode?tables=log_asn",
                                         headers={"Authorization": "Bearer " + tok_admin})
            with urllib.request.urlopen(req, timeout=30) as rr:
                zipped = rr.read()
            dest = GEN_OUT / "raw"
            dest.mkdir(parents=True, exist_ok=True)
            (dest / "generated.zip").write_bytes(zipped)
            with _zf.ZipFile(dest / "generated.zip") as zf:
                names = zf.namelist()
                zf.extractall(dest)
            check("生成器导出 log_asn 骨架", any(n.endswith("index.vue") for n in names) or any(n.endswith(".java") for n in names),
                  f"files={len(names)}")
        except Exception as e:
            check("生成器导出 log_asn 骨架", False, str(e)[:200])

        # ========== 1. 新建 ASN（DRAFT，只登记不动库存）==========
        rid_create = uuid.uuid4().hex
        create_body = {"siteCode": "AE", "warehouseCode": "WH-AE-FBN", "requestId": rid_create,
                       "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qtyShipped": 30, "partnerSku": "PSKU-1"}]}
        avail_before = q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create", create_body)
        created = data_of(resp)
        aid = created.get("asnId")
        check("新建 ASN 成功", bcode(resp) == 200 and created.get("replayed") is False and bool(aid),
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        ano = created.get("asnNo")
        check("ASN 单号格式", bool(ano) and str(ano).startswith("ASN:AE:"), str(ano))
        check("create 动作标记", created.get("action") == "CREATE" and not created.get("childBatchIds"), str(created)[:150])
        code, resp = api(tok_ae, "GET", f"/noon/inventory/asn/get?asnId={aid}")
        detail0 = data_of(resp)
        items0 = detail0.get("items") or []
        check("新建含计划行", len(items0) == 1 and items0[0].get("id") > 0
              and items0[0].get("qtyShipped") == 30 and items0[0].get("qtyReceived") == 0, str(items0)[:200])
        item_id = items0[0].get("id")
        check("新建不扣库存(源批次仍60)",
              q("SELECT qty_remaining FROM inv_batch WHERE id=1", db, raw=True).stdout.strip() == "60")
        check("新建不改台账",
              q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == avail_before)
        check("新建不写流水", q("SELECT COUNT(*) FROM inv_stock_move WHERE ref_doc_no='%s'" % ano, db, raw=True).stdout.strip() == "0")
        # 幂等
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create", create_body)
        check("新建重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("新建重放未多建单", q("SELECT COUNT(*) FROM log_asn WHERE asn_no='%s'" % ano, db, raw=True).stdout.strip() == "1")
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create",
                         {**create_body, "requestId": rid_create, "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qtyShipped": 99}]})
        check("新建同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # 跨站 FBN 仓（AE 用户建 SA FBN）→ 403/400
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create",
                         {**create_body, "requestId": uuid.uuid4().hex, "warehouseCode": "WH-SA-FBN"})
        check("跨站 FBN 仓被拒(400/403)", bcode(resp) in (400, 403), f"bcode={bcode(resp)}")
        # 非 FBN 仓（WH-AE-1 是 LOCAL）→ 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create",
                         {**create_body, "requestId": uuid.uuid4().hex, "warehouseCode": "WH-AE-1"})
        check("非 FBN 仓被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # viewer 无权限
        code, resp = api(tok_viewer, "POST", "/noon/inventory/asn/create",
                         {**create_body, "requestId": uuid.uuid4().hex})
        check("viewer 无新建权限(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/asn/result?requestId=" + rid_create)
        check("result 命中已建单", bcode(resp) == 200 and data_of(resp).get("replayed") is True
              and data_of(resp).get("asnId") == aid, str(resp)[:150])

        # ========== 2. 封单发运（DRAFT→SEALED，扣源仓+在途子批次）==========
        rid_seal = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/seal", {"asnId": aid, "requestId": rid_seal})
        sealed = data_of(resp)
        check("封单成功", bcode(resp) == 200 and sealed.get("replayed") is False, f"bcode={bcode(resp)} {str(resp)[:200]}")
        child_ids = sealed.get("childBatchIds") or []
        check("封单返回在途子批次", len(child_ids) == 1, str(sealed)[:200])
        child_id = child_ids[0]
        check("在途子批次血缘正确",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={child_id} AND source_type='TRANSFER_SPLIT' "
                f"AND parent_batch_id=1 AND asn_no='{ano}' AND status='IN_TRANSIT' "
                "AND qty_in=30 AND qty_remaining=30 AND unit_cost=10.0000 AND warehouse_id=4", db, raw=True).stdout.strip() == "1")
        check("子批次 received_at 继承父批次",
              q(f"SELECT received_at FROM inv_batch WHERE id={child_id}", db, raw=True).stdout.strip().startswith("2026-11-22 09:00:00"))
        check("源批次扣减30(60→30)",
              q("SELECT qty_remaining FROM inv_batch WHERE id=1", db, raw=True).stdout.strip() == "30")
        check("源仓可用减/FBN仓在途增",
              q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "70" and
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "30")
        check("ASN_OUT 流水 -30",
              q(f"SELECT COUNT(*) FROM inv_stock_move WHERE change_type='ASN_OUT' AND qty_delta=-30 AND ref_doc_no='{ano}'", db, raw=True).stdout.strip() == "1")
        check("ASN 状态转 SEALED",
              q(f"SELECT status FROM log_asn WHERE id={aid}", db, raw=True).stdout.strip() == "SEALED")
        # 封单幂等
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/seal", {"asnId": aid, "requestId": rid_seal})
        check("封单重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("封单重放未重复拆批",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE parent_batch_id=1 AND asn_no='{ano}'", db, raw=True).stdout.strip() == "1")
        # 动作隔离：幂等表按 (request_id, action) 独立，seal 键只在 SEAL action 有行
        check("动作隔离(幂等表按request_id+action)",
              q(f"SELECT COUNT(*) FROM noon_m4_asn_request WHERE request_id='{rid_seal}' AND action='SEAL'", db, raw=True).stdout.strip() == "1"
              and q(f"SELECT COUNT(*) FROM noon_m4_asn_request WHERE request_id='{rid_seal}' AND action='RECEIVE'", db, raw=True).stdout.strip() == "0")
        # 重复封单（不同键，已 SEALED）→ 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/seal", {"asnId": aid, "requestId": uuid.uuid4().hex})
        check("重复封单被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")

        # ========== 3. 收货（SEALED→RECEIVED，短少 ASN_SHORT）==========
        rid_recv = uuid.uuid4().hex
        recv_body = {"asnId": aid, "requestId": rid_recv,
                     "lines": [{"asnItemId": item_id, "qtyReceived": 28, "shortEventId": "SHORT-1"}]}
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/receive", recv_body)
        recvd = data_of(resp)
        check("收货成功(短少2)", bcode(resp) == 200 and recvd.get("replayed") is False, f"bcode={bcode(resp)} {str(resp)[:200]}")
        check("在途子批次转OPEN且量收敛28",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={child_id} AND status='OPEN' AND qty_in=28 AND qty_remaining=28", db, raw=True).stdout.strip() == "1")
        check("FBN仓在途减/可用增",
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "0" and
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "28")
        check("短少记 ASN_SHORT 消耗",
              q(f"SELECT COUNT(*) FROM inv_batch_consumption WHERE move_type='ASN_SHORT' AND qty=2 AND unit_cost=10.0000 "
                f"AND biz_event_key LIKE 'ASNSHORT:{item_id}:SHORT-1' AND sign=1", db, raw=True).stdout.strip() == "1")
        check("diff_qty 回写=2", q(f"SELECT diff_qty FROM log_asn_item WHERE id={item_id}", db, raw=True).stdout.strip() == "2")
        check("ASN_IN 流水 +28",
              q(f"SELECT COUNT(*) FROM inv_stock_move WHERE change_type='ASN_IN' AND qty_delta=28 AND ref_doc_no='{ano}'", db, raw=True).stdout.strip() == "1")
        check("ASN 状态转 RECEIVED",
              q(f"SELECT status FROM log_asn WHERE id={aid}", db, raw=True).stdout.strip() == "RECEIVED")
        # 守恒：源批次30 + 子批次28 + 短少2 = 60
        total_qty = q(f"SELECT (SELECT qty_remaining FROM inv_batch WHERE id=1) + "
                      f"(SELECT qty_remaining FROM inv_batch WHERE id={child_id}) + "
                      f"(SELECT qty FROM inv_batch_consumption WHERE move_type='ASN_SHORT' AND biz_event_key LIKE 'ASNSHORT:{item_id}:SHORT-1')",
                      db, raw=True).stdout.strip()
        check("四态守恒(30+28+2=60)", total_qty == "60", f"total={total_qty}")
        # 收货幂等
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/receive", recv_body)
        check("收货重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("收货重放未重复改库存",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=4", db, raw=True).stdout.strip() == "28")
        # 短少事件号缺失 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/create",
                         {**create_body, "requestId": uuid.uuid4().hex})
        aid2 = data_of(resp).get("asnId")
        api(tok_ae, "POST", "/noon/inventory/asn/seal", {"asnId": aid2, "requestId": uuid.uuid4().hex})
        code, resp = api(tok_ae, "POST", "/noon/inventory/asn/receive",
                         {"asnId": aid2, "requestId": uuid.uuid4().hex,
                          "lines": [{"asnItemId": data_of(api(tok_ae, "GET", f"/noon/inventory/asn/get?asnId={aid2}")).get("items", [{}])[0].get("id"),
                                     "qtyReceived": 28, "shortEventId": None}]})
        check("短少缺事件号被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")

        # ========== 4. 列表/详情/授权 ==========
        code, resp = api(tok_ae, "GET", "/noon/inventory/asn/list?offset=0&limit=10")
        check("ASN 列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 2, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", f"/noon/inventory/asn/get?asnId={aid}")
        d = data_of(resp)
        check("详情含血缘", bcode(resp) == 200 and bool(d.get("head")) and len(d.get("items") or []) == 1, str(d)[:200])
        # opAE 无 SA 授权，读 SA ASN 详情 → 403。先建一张 SA ASN（用 opAESA）
        code, resp = api(tok_aesa, "POST", "/noon/inventory/asn/create",
                         {"siteCode": "SA", "warehouseCode": "WH-SA-FBN", "requestId": uuid.uuid4().hex,
                          "lines": [{"skuId": 3, "batchNo": "B:SEED-SA", "qtyShipped": 5}]})
        check("opAESA 建 SA ASN(备授权测试)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:150]}")
        # 撤销 AE 授权后列表/详情 403
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "GET", "/noon/inventory/asn/list")
        check("撤销授权后列表 403/空", bcode(resp) == 403 or (bcode(resp) == 200 and total_of(resp) == 0), f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)

        # ========== 5. 真实浏览器：UI 走 create→seal→receive ==========
        if not args.no_browser:
            ui_dir = ROOT / "yx-erp/yxerp-ui"
            bdir = REPORT_DIR / "browser"
            bdir.mkdir(parents=True, exist_ok=True)
            dist = work / "fe-dist"
            fe_build = run([ui_dir / "node_modules/.bin/vite", "build", "--outDir", str(dist), "--emptyOutDir", "false"], cwd=ui_dir)
            check("前端构建（浏览器验证用）", fe_build.returncode == 0 and (dist / "index.html").exists(),
                  fe_build.stderr[-300:] if fe_build.returncode else "")
            if fe_build.returncode == 0 and (dist / "index.html").exists():
                frontport = free_port()
                proxy = subprocess.Popen(
                    [sys.executable, str(ROOT / "scripts/m2-static-proxy.py"), "--dist", str(dist),
                     "--backend-port", str(appport), "--port", str(frontport)],
                    stdout=open(work / "proxy.log", "w"), stderr=subprocess.STDOUT)
                procs.append(proxy)
                front = f"http://127.0.0.1:{frontport}"
                for _ in range(30):
                    try:
                        code, _ = http("GET", front + "/", timeout=2); break
                    except Exception: time.sleep(1)
                ctx = {"frontendUrl": front, "backendUrl": base, "outDir": str(bdir)}
                Path("/tmp/noon-m4-asn-browser-ctx.json").write_text(json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                br = subprocess.run(["ego-browser", "nodejs"],
                                    stdin=open(ROOT / "scripts/m4-asn-browser-check.js", encoding="utf-8"),
                                    capture_output=True, text=True, timeout=360)
                bout = (br.stdout or "") + "\n" + (br.stderr or "")
                (bdir / "ego-stdout.txt").write_text(br.stdout, encoding="utf-8")
                (bdir / "ego-stderr.txt").write_text(br.stderr, encoding="utf-8")
                for line in bout.splitlines():
                    if line.startswith("PASS |"): check("浏览器: " + line[7:].split(" |")[0], True)
                    elif line.startswith("FAIL |"): check("浏览器: " + line[7:].split(" |")[0], False, line[:200])
                m = [l for l in bout.splitlines() if l.startswith("BROWSER RESULT")]
                if m:
                    bm = m[-1]
                    bf = int(bm.split("FAIL=")[-1].strip()) if "FAIL=" in bm else 1
                    check("浏览器闭环总体", bf == 0, bm)
                else:
                    check("浏览器闭环总体", False, "未读到 BROWSER RESULT: " + bout[-300:])

        (REPORT_DIR / "e2e-output.txt").write_text("\n".join(checks) + f"\n\nPASS={passed} FAIL={failed}\n", encoding="utf-8")
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
