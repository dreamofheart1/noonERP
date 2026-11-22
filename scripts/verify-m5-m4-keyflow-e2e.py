#!/usr/bin/env python3
"""M5↔M4 关键流程联调：一条 SKU 库存从 M4 采购入库生成批次，经 M5 订单→妥投→退货→红冲→A/B 分级处置，
验证跨模块批次血缘衔接、qty_unsellable 隔离与 M4 台账对接、站点权限、重复事件、全局数量守恒。

与 verify-m5-sales-e2e.py 区别：源头批次由 M4 采购入库 API 真实生成（非种子直建），并加全局守恒断言。
用法：python3 scripts/verify-m5-m4-keyflow-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
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
M5SQL = ROOT / "yx-erp/sql/noon_m5_sales.sql"
INTAKE_REVIEW = ROOT / "yx-erp/sql/noon_intake_review.sql"
FIFO_REVIEW = ROOT / "yx-erp/sql/noon_fifo_review.sql"
REVERSAL_REVIEW = ROOT / "yx-erp/sql/noon_reversal_review.sql"
M5MENU = ROOT / "yx-erp/sql/noon_m5_sales_menu.sql"
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m5-sales-return"
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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, M5SQL, M5MENU, INTAKE_REVIEW, FIFO_REVIEW, REVERSAL_REVIEW):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m5-m4-keyflow-"))
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

        db = "noon_m5_m4_keyflow"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M5 订单退货迁移", M5SQL), ("M5 订单退货菜单", M5MENU),
                         ("M5 入账隔离表", INTAKE_REVIEW),
                         ("M5 FIFO 审计隔离表", FIFO_REVIEW),
                         ("M5 红冲审计隔离表", REVERSAL_REVIEW)]:
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
            ["sal_order", "sal_order_item", "sal_return", "int_webhook_event", "inv_batch_consumption"]), encoding="utf-8")
        r = feed(base_ddl, db)
        check("灌入 M5 销售/退货/事件/消耗表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
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
            # 源头批次由 M4 采购入库 API 生成（联调关键：真实跨模块衔接），此处只放采购单
            "INSERT INTO pur_order(id,order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
            "VALUES(1,'PO-KF-1',1,'AE','CNY',1000.0000,'RECEIVED','MANUAL',1)",
            "INSERT INTO pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
            "VALUES(1,1,1,100,100,10.0000,'CNY',1.00000000)",
            "INSERT INTO noon_m3_receipt(id,order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
            "VALUES(1,1,1,100,10.0000,NOW(),1)",
            # M5 主数据：门店 + 站点归属 + Partner SKU 映射（intake 入账闸门要求）
            "INSERT INTO md_store(id,store_no,partner_id,name_en,status) VALUES(7,'STORE-AE',1,'AE Store','ACTIVE')",
            "INSERT INTO md_site(id,site_code,store_id,country,currency,status) VALUES(1,'AE',7,'UAE','AED','ACTIVE')",
            "INSERT INTO md_partner_sku(id,partner_sku,sku_id,site_code,business_model,status) "
            "VALUES(1,'PSKU-1',1,'AE','FBP','ACTIVE')",
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


        # ========== 1. M4 采购入库：生成源头批次（跨模块衔接）==========
        rid_in = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm",
                         {"purOrderId": 1, "warehouseId": 1, "siteCode": "AE",
                          "lines": [{"purOrderItemId": 1, "qtyQualified": 100}], "requestId": rid_in})
        inb = data_of(resp)
        check("⓪ M4 采购入库成功", bcode(resp) == 200 and bool(inb.get("batchIds")), f"bcode={bcode(resp)} {str(resp)[:200]}")
        src_batch = (inb.get("batchIds") or [None])[0]
        src_no = q(f"SELECT batch_no FROM inv_batch WHERE id={src_batch}", db, raw=True).stdout.strip()
        check("⓪ 入库生成源头批次(100@10)",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={src_batch} AND qty_in=100 AND qty_remaining=100 AND unit_cost=10.0000 AND source_type='PURCHASE'", db, raw=True).stdout.strip() == "1")
        check("⓪ M4 台账 available=100",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "100")

        # ========== 2. M5 订单导入 → 妥投（消费 M4 批次）==========
        rid_o = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/sales/order/import",
                         {"requestId": rid_o, "siteCode": "AE", "orderNo": "KF5-O-1", "storeId": 7,
                          "skuId": 1, "partnerSku": "PSKU-1", "grossAmount": 150.0000,
                          "qty": 20, "currency": "AED", "sourceChannel": "MANUAL"})
        oid = data_of(resp).get("orderId")
        check("① M5 订单导入(消费M4主数据)", bcode(resp) == 200 and bool(oid), f"bcode={bcode(resp)} {str(resp)[:180]}")
        oitem = q(f"SELECT id FROM sal_order_item WHERE sal_order_id={oid}", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "POST", "/noon/sales/order/deliver",
                         {"requestId": uuid.uuid4().hex, "siteCode": "AE", "salOrderItemId": int(oitem), "qty": 20})
        check("② 妥投 FIFO 消费 M4 入库批次", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:180]}")
        cons = q(f"SELECT id FROM inv_batch_consumption WHERE move_type='SALE' AND sal_order_item_id={oitem}", db, raw=True).stdout.strip()
        check("② SALE 消耗血缘=M4 批次",
              q(f"SELECT COUNT(*) FROM inv_batch_consumption WHERE id={cons} AND batch_id={src_batch} AND qty=20 AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1")
        check("② M4 批次扣减80/台账80",
              q(f"SELECT qty_remaining FROM inv_batch WHERE id={src_batch}", db, raw=True).stdout.strip() == "80" and
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "80")

        # ========== 3. 退货 B 级：红冲 + RETURN_LOSS + qty_unsellable ==========
        def do_return(return_no, qty, grade, comp=None, restock=None):
            rr = uuid.uuid4().hex
            c, r = api(tok_ae, "POST", "/noon/sales/return/import",
                       {"requestId": rr, "siteCode": "AE", "orderNo": "KF5-O-1",
                        "sourceReturnNo": return_no, "salOrderItemId": int(oitem), "qty": qty})
            rid = data_of(r).get("returnId")
            c2, r2 = api(tok_ae, "POST", "/noon/sales/return/reverse",
                         {"requestId": uuid.uuid4().hex, "siteCode": "AE", "salReturnId": rid,
                          "originalConsumptionId": int(cons), "qty": qty})
            body = {"requestId": uuid.uuid4().hex, "siteCode": "AE", "salReturnId": rid, "grade": grade, "qty": qty}
            if comp is not None: body["compensationAmount"] = comp
            if restock is not None: body["restockCost"] = restock
            c3, r3 = api(tok_ae, "POST", "/noon/sales/return/dispose", body)
            return rid, c, c2, c3, r3

        rb, c1, c2, c3, r3 = do_return("KF5-R-B", 4, "B", comp=30)
        check("③ 退货B级全链路(入账→红冲→处置)", c1 == 200 and c2 == 200 and c3 == 200,
              f"c1={c1} c2={c2} c3={c3} {str(r3)[:180]}")
        check("③ B级 RETURN_LOSS 全额40(非扣赔偿)",
              q(f"SELECT COUNT(*) FROM inv_batch_consumption WHERE move_type='RETURN_LOSS' AND sal_return_id={rb} AND amount=40.0000", db, raw=True).stdout.strip() == "1")
        check("③ qty_unsellable 隔离+4(M4台账写入点)",
              q("SELECT qty_unsellable FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "4")

        # ========== 4. 退货 A 级：红冲 + 二次上架新批次 ==========
        ra, a1, a2, a3, ar = do_return("KF5-R-A", 2, "A")
        check("④ 退货A级全链路", a1 == 200 and a2 == 200 and a3 == 200,
              f"a1={a1} a2={a2} a3={a3} {str(ar)[:180]}")
        check("④ A级建 RETURN_RESTOCK 新批次(2@10)",
              q("SELECT COUNT(*) FROM inv_batch WHERE source_type='RETURN_RESTOCK' AND sku_id=1 AND qty_remaining=2 AND unit_cost=10.0000 AND status='OPEN'", db, raw=True).stdout.strip() == "1")
        check("④ A级不增 qty_unsellable(仍4)",
              q("SELECT qty_unsellable FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "4")

        # ========== 5. 跨站权限 / 重复事件 / 数量守恒 ==========
        # 重复事件：同 requestId 重放订单导入不重复建单
        code, resp = api(tok_ae, "POST", "/noon/sales/order/import",
                         {"requestId": rid_o, "siteCode": "AE", "orderNo": "KF5-O-1", "storeId": 7,
                          "skuId": 1, "partnerSku": "PSKU-1", "grossAmount": 150.0000,
                          "qty": 20, "currency": "AED", "sourceChannel": "MANUAL"})
        check("⑤ 重复事件(同requestId)只建1单", bcode(resp) == 200 and data_of(resp).get("replayed") is True
              and q("SELECT COUNT(*) FROM sal_order WHERE order_no='KF5-O-1' AND site_code='AE'", db, raw=True).stdout.strip() == "1")
        # 跨站：opAE 无 SA 授权，导入 SA 站点订单 → 403
        code, resp = api(tok_ae, "POST", "/noon/sales/order/import",
                         {"requestId": uuid.uuid4().hex, "siteCode": "SA", "orderNo": "KF5-X-1", "storeId": 7,
                          "skuId": 2, "partnerSku": "PSKU-2", "grossAmount": 50.0000,
                          "qty": 1, "currency": "SAR", "sourceChannel": "MANUAL"})
        check("⑤ 跨站导入被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # 全局守恒：入库100 = M4批次剩余80 + SALE消耗20；SALE20 = B红冲4 + A红冲2 + 未退14
        src_remain = int(q(f"SELECT qty_remaining FROM inv_batch WHERE id={src_batch}", db, raw=True).stdout.strip())
        sale_qty = int(q(f"SELECT qty FROM inv_batch_consumption WHERE id={cons}", db, raw=True).stdout.strip())
        rev_qty = int(q("SELECT COALESCE(SUM(qty),0) FROM inv_batch_consumption WHERE move_type='RETURN_REVERSAL' AND reversal_of_id=%s" % cons, db, raw=True).stdout.strip())
        loss_qty = int(q("SELECT COALESCE(SUM(qty),0) FROM inv_batch_consumption WHERE move_type='RETURN_LOSS'", db, raw=True).stdout.strip())
        unsellable = int(q("SELECT qty_unsellable FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip())
        restock_qty = int(q("SELECT COALESCE(SUM(qty_in),0) FROM inv_batch WHERE source_type='RETURN_RESTOCK'", db, raw=True).stdout.strip())
        check(f"⑤ M4入库守恒(剩余{src_remain}+SALE{sale_qty}=100)", src_remain + sale_qty == 100)
        check(f"⑤ SALE分解(红冲{rev_qty}+未退{sale_qty - rev_qty}={sale_qty})", rev_qty == 6 and sale_qty - rev_qty == 14)
        check(f"⑤ 分级守恒(B损耗{loss_qty}+A上架{restock_qty}+不合格{unsellable})", loss_qty == 4 and restock_qty == 2 and unsellable == 4)

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
