#!/usr/bin/env python3
"""M4 库存第三轮第二批「头程费用登记」隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦口径：纯登记不动库存；create/list/get/result；动作级幂等；站点授权/跨站 ASN 拒绝；
fee_type/fx/period/allocation_flag 校验；生成器实调 log_shipment_cost；真实浏览器 UI 新增。

用法：python3 scripts/verify-m4-shipment-cost-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
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
SCSQL = ROOT / "yx-erp/sql/noon_m4_shipment_cost.sql"
SCMENU = ROOT / "yx-erp/sql/noon_m4_shipment_cost_menu.sql"
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-shipment-cost"
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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, SCSQL, SCMENU):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-shipment-e2e-"))
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

        db = "noon_m4_shipment_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M4 第三轮头程费用迁移", SCSQL), ("M4 第三轮头程费用菜单", SCMENU)]:
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
            ["log_shipment_cost", "log_asn", "log_asn_item"]), encoding="utf-8")
        r = feed(base_ddl, db)
        check("灌入头程费用/ASN基础表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
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
            # ASN 夹具：AE ASN(id=1) 与 SA ASN(id=2)，用于 asnId 关联与跨站拒绝
            "INSERT INTO log_asn(id,asn_no,site_code,warehouse_code,status,source,created_at) "
            "VALUES(1,'ASN-AE-1','AE','WH-AE-1','DRAFT','MANUAL',NOW())",
            "INSERT INTO log_asn(id,asn_no,site_code,warehouse_code,status,source,created_at) "
            "VALUES(2,'ASN-SA-1','SA','WH-SA-1','DRAFT','MANUAL',NOW())",
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
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230,3500,3510,3511,3512)", db)
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


        # ========== 0. 生成器：实际运行（log_shipment_cost，采用见 ADOPTION.md）==========
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        code, resp = api(tok_admin, "POST", "/tool/gen/importTable?tables=log_shipment_cost&tplWebType=element-plus")
        check("生成器导入 log_shipment_cost", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        code, resp = api(tok_admin, "PUT", "/tool/gen", body={
            "tableName": "log_shipment_cost", "tableComment": "头程费用", "className": "ShipmentCost",
            "packageName": "com.yx.noon", "moduleName": "logistics", "businessName": "shipmentCost",
            "functionName": "头程费用", "tplCategory": "crud", "tplWebType": "element-plus",
            "genType": "0", "functionAuthor": "WorkBuddy"})
        check("生成器配置 log_shipment_cost", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        try:
            req = urllib.request.Request(base + "/tool/gen/batchGenCode?tables=log_shipment_cost",
                                         headers={"Authorization": "Bearer " + tok_admin})
            with urllib.request.urlopen(req, timeout=30) as rr:
                zipped = rr.read()
            dest = GEN_OUT / "raw"
            dest.mkdir(parents=True, exist_ok=True)
            (dest / "generated.zip").write_bytes(zipped)
            with _zf.ZipFile(dest / "generated.zip") as zf:
                names = zf.namelist()
                zf.extractall(dest)
            check("生成器导出 log_shipment_cost 骨架", any(n.endswith("index.vue") for n in names) or any(n.endswith(".java") for n in names),
                  f"files={len(names)}")
        except Exception as e:
            check("生成器导出 log_shipment_cost 骨架", False, str(e)[:200])

        # ========== 1. 登记成功（CNY，allocation_flag=NONE，不动库存）==========
        rid1 = uuid.uuid4().hex
        body1 = {"siteCode": "AE", "asnId": 1, "period": "2026-11", "feeType": "FREIGHT",
                 "amount": 1234.5600, "currency": "CNY", "fxRate": 1, "allocationFlag": "NONE",
                 "remark": "E2E 头程运费", "requestId": rid1}
        ledger_before = q("SELECT qty_available,qty_in_transit FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip()
        batch_cnt_before = q("SELECT COUNT(*) FROM inv_batch", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create", body1)
        created = data_of(resp)
        cid1 = created.get("costId")
        check("登记成功(CNY)", bcode(resp) == 200 and created.get("replayed") is False and bool(cid1),
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        cno1 = created.get("costNo")
        check("费用单号格式", bool(cno1) and str(cno1).startswith("SC:AE:"), str(cno1))
        # 不动库存/批次/台账
        check("登记不改台账", q("SELECT qty_available,qty_in_transit FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == ledger_before)
        check("登记不改批次", q("SELECT COUNT(*) FROM inv_batch", db, raw=True).stdout.strip() == batch_cnt_before)
        check("登记行落库", q("SELECT COUNT(*) FROM log_shipment_cost WHERE id=%s" % cid1, db, raw=True).stdout.strip() == "1")
        check("登记行字段权威",
              q("SELECT COUNT(*) FROM log_shipment_cost WHERE id=%s AND asn_id=1 AND fee_type='FREIGHT' "
                "AND amount=1234.5600 AND currency='CNY' AND fx_rate=1.00000000 AND allocation_flag='NONE'" % cid1,
                db, raw=True).stdout.strip() == "1")
        # 审计：复用 M4 共享审计表 noon_m4_inventory_audit，biz_type=SHIPMENT_COST
        check("写 CREATE 审计",
              q("SELECT COUNT(*) FROM noon_m4_inventory_audit WHERE biz_type='SHIPMENT_COST' AND action='CREATE' "
                "AND biz_id=%s" % cid1, db, raw=True).stdout.strip() == "1")

        # ========== 2. 幂等：重放/内容冲突/动作隔离/result ==========
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create", body1)
        check("建单重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("建单重放未多登记",
              q("SELECT COUNT(*) FROM noon_m4_shipment_cost_request WHERE request_id='%s'" % rid1, db, raw=True).stdout.strip() == "1"
              and q("SELECT COUNT(*) FROM log_shipment_cost WHERE cost_no='%s'" % cno1, db, raw=True).stdout.strip() == "1")
        # 同键改内容 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": rid1, "amount": 999.0000})
        check("同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")
        # result 命中
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/result?requestId=" + rid1)
        check("result 命中已登记", bcode(resp) == 200 and data_of(resp).get("replayed") is True
              and data_of(resp).get("costId") == cid1, str(resp)[:150])

        # ========== 3. 校验规则 ==========
        # 外币 fx：AED fxRate=1 合法（>0），CNY fxRate=2 拒绝，缺失 fxRate 拒绝
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "currency": "AED", "fxRate": 3.5, "amount": 100})
        check("外币(AED fx=3.5)登记成功", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:150]}")
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "currency": "CNY", "fxRate": 2})
        check("CNY fx 非1 被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "fxRate": None})
        check("fx 缺失被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # fee_type 非法
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "feeType": "UNKNOWN"})
        check("feeType 非法被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # amount<=0
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "amount": 0})
        check("amount<=0 被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # period 非法
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "period": "2026-13"})
        check("period 非法被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # allocation_flag 非法
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "allocationFlag": "ALL"})
        check("allocationFlag 非法被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        # PARTIAL 标记仅登记不摊成本（台账/批次仍不变）
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "allocationFlag": "PARTIAL", "amount": 50})
        check("PARTIAL 标记登记成功", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:150]}")
        check("PARTIAL 不实际分摊(台账不变)",
              q("SELECT qty_available,qty_in_transit FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == ledger_before)
        # 客户端伪造 id/cost_no 被忽略
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "id": 999, "costNo": "HACK", "status": "DONE"})
        forged = data_of(resp)
        check("伪造字段被忽略", bcode(resp) == 200 and str(forged.get("costNo", "")).startswith("SC:AE:")
              and forged.get("costId") != 999, str(forged)[:150])

        # ========== 4. 授权边界 ==========
        # 跨站 ASN(asnId=2 是 SA) 用 AE 登记 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "asnId": 2})
        check("跨站 ASN 被拒(400/403)", bcode(resp) in (400, 403), f"bcode={bcode(resp)}")
        # 越权站点：opAE 仅 AE，登记 SA 站点 → 403
        code, resp = api(tok_ae, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex, "siteCode": "SA", "asnId": None})
        check("越权站点登记被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # viewer 无 create 权限 → 403
        code, resp = api(tok_viewer, "POST", "/noon/inventory/shipment-cost/create",
                         {**body1, "requestId": uuid.uuid4().hex})
        check("viewer 无登记权限(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # 撤销 AE 授权后 result/get/list 403
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/result?requestId=" + rid1)
        check("撤销授权后 result 403", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/get?id=" + str(cid1))
        check("撤销授权后详情 403", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/list")
        check("撤销授权后列表 403/空", bcode(resp) == 403 or (bcode(resp) == 200 and total_of(resp) == 0), f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)

        # ========== 5. 列表/详情/分页/过滤 ==========
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/list?offset=0&limit=10")
        check("费用列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 4, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/list?offset=0&limit=10&feeType=DUTY")
        check("feeType 过滤(total=0)", bcode(resp) == 200 and total_of(resp) == 0, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/list?offset=0&limit=10&period=2026-11")
        check("period 过滤可见", bcode(resp) == 200 and total_of(resp) >= 4, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/shipment-cost/get?id=" + str(cid1))
        d = data_of(resp)
        check("详情字段完整", bcode(resp) == 200 and d.get("costNo") == cno1 and d.get("feeType") == "FREIGHT"
              and d.get("asnId") == 1, str(d)[:200])

        # ========== 6. 真实浏览器：UI 新增费用 ==========
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
                fe_ready = False
                for _ in range(30):
                    try:
                        c, _ = http("GET", front, timeout=3)
                        if c == 200: fe_ready = True; break
                    except Exception: pass
                    if proxy.poll() is not None: break
                    time.sleep(1)
                check("静态前端代理就绪", fe_ready, "" if fe_ready else "见 proxy.log")
                if fe_ready:
                    ctx = {"frontendUrl": front, "backendUrl": base, "outDir": str(bdir)}
                    Path("/tmp/noon-m4-shipment-browser-ctx.json").write_text(json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m4-shipment-browser-check.js", encoding="utf-8"),
                                        capture_output=True, text=True, timeout=1200)
                    (bdir / "ego-stdout.txt").write_text(br.stdout, encoding="utf-8")
                    (bdir / "ego-stderr.txt").write_text(br.stderr, encoding="utf-8")
                    for line in (br.stderr + "\n" + br.stdout).splitlines():
                        if line.startswith(("PASS |", "FAIL |")):
                            checks.append(line)
                            print(line, flush=True)
                            if line.startswith("PASS |"): passed += 1
                            else: failed += 1
                    check("浏览器验证脚本执行完成", br.returncode == 0,
                          (br.stderr or br.stdout)[-500:])

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
