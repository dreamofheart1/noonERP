#!/usr/bin/env python3
"""M4 库存第三轮第一批隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦口径：
  1) 生成器：实际调用若依 /tool/gen 导入 inv_stocktake，采用见 generated/ADOPTION.md
  2) 创建盘点单：DRAFT、不改库存、幂等 + 动作隔离
  3) 应用盘点：盘亏 FIFO 消耗 + STK 事件键流水；盘盈新建 ADJUSTMENT 批次承接成本
  4) 时序闸门(v1.2)：建单 qty_book 快照为唯一合法基线；建单后台账变动→apply 拒绝且不写入；重盘正确路径；零成本盘盈拒绝
  5) 幂等：CREATE/APPLY 各重放 replayed；同键改动作/内容 400；撤销授权/跨站 403
  6) 四态台账/流水守恒、列表/详情/分页、真实浏览器新建→应用闭环

用法：python3 scripts/verify-m4-stocktake-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
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
STSQL = ROOT / "yx-erp/sql/noon_m4_stocktake.sql"
STMENU = ROOT / "yx-erp/sql/noon_m4_stocktake_menu.sql"
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-stocktake"
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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, STSQL, STMENU):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-stocktake-e2e-"))
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

        db = "noon_m4_stocktake_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M4 第三轮盘点迁移", STSQL), ("M4 第三轮盘点菜单", STMENU)]:
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
            ["inv_stocktake", "inv_stocktake_item", "inv_batch_consumption", "sal_order", "sal_order_item"]), encoding="utf-8")
        r = feed(base_ddl, db)
        check("灌入盘点/消耗基础表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
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
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230,3400,3401,3402)", db)
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

        # ========== 0. 生成器：实际运行（inv_stocktake，采用见 ADOPTION.md）==========
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        code, resp = api(tok_admin, "POST", "/tool/gen/importTable?tables=inv_stocktake&tplWebType=element-plus")
        check("生成器导入 inv_stocktake", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        code, resp = api(tok_admin, "PUT", "/tool/gen", body={
            "tableName": "inv_stocktake", "tableComment": "盘点单", "className": "Stocktake",
            "packageName": "com.yx.noon", "moduleName": "inventory", "businessName": "stocktake",
            "functionName": "盘点单", "tplCategory": "crud", "tplWebType": "element-plus",
            "genType": "0", "functionAuthor": "WorkBuddy"})
        check("生成器配置 inv_stocktake", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
              f"code={code} {str(resp)[:150]}")
        try:
            req = urllib.request.Request(base + "/tool/gen/batchGenCode?tables=inv_stocktake",
                                         headers={"Authorization": "Bearer " + tok_admin})
            with urllib.request.urlopen(req, timeout=30) as rr:
                zipped = rr.read()
            dest = GEN_OUT / "raw"
            dest.mkdir(parents=True, exist_ok=True)
            (dest / "generated.zip").write_bytes(zipped)
            with _zf.ZipFile(dest / "generated.zip") as zf:
                names = zf.namelist()
                zf.extractall(dest)
            check("生成器导出 inv_stocktake 骨架", any(n.endswith("index.vue") for n in names) or any(n.endswith(".java") for n in names),
                  f"files={len(names)}")
        except Exception as e:
            check("生成器导出 inv_stocktake 骨架", False, str(e)[:200])

        # ========== 1. 创建盘点单 ==========
        rid_create = uuid.uuid4().hex
        create_body = {"warehouseId": 1, "period": "2026-11", "requestId": rid_create,
                       "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qtyActual": 90, "countEventId": "CNT-D1"}]}
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create", create_body)
        check("创建盘点单成功", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        created = data_of(resp)
        sid = created.get("stocktakeId")
        sno = created.get("stocktakeNo")
        check("建单返回单号", bool(sid) and str(sno).startswith("ST:AE:"), str(created)[:200])
        # 建单不改库存/不写流水
        check("建单不改库存", q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "100")
        check("建单不写流水", q("SELECT COUNT(*) FROM inv_stock_move WHERE ref_doc_no='%s'" % sno, db, raw=True).stdout.strip() == "0")
        # 建单详情：qtyBook 快照=100，qtyActual=90
        code, resp = api(tok_ae, "GET", f"/noon/inventory/stocktake/get?id={sid}")
        created_items = data_of(resp).get("items") or []
        check("建单含明细行且 qtyBook 快照", len(created_items) == 1 and created_items[0].get("qtyBook") == 100
              and created_items[0].get("qtyActual") == 90, str(created_items)[:200])
        item_id = created_items[0]["id"]
        # 建单幂等：重放零写入
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create", create_body)
        check("建单重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("建单重放未多建单",
              q(f"SELECT COUNT(*) FROM noon_m4_stocktake_request WHERE request_id='{rid_create}'", db, raw=True).stdout.strip() == "1"
              and q(f"SELECT COUNT(*) FROM inv_stocktake WHERE warehouse_id=1 AND id<>{sid}", db, raw=True).stdout.strip() == "0")
        # 同键改内容 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create",
                         {**create_body, "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qtyActual": 80, "countEventId": "CNT-D1"}]})
        check("建单同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")
        # 跨站建单拒绝（仓2 是 SA，用户 opAE 仅 AE）
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create",
                         {**create_body, "requestId": uuid.uuid4().hex, "warehouseId": 2})
        check("建单跨站被拒(403/400)", bcode(resp) in (400, 403), f"bcode={bcode(resp)}")

        # ========== 2. 时序错位闸门：建单后真实出库 → apply 必须拒绝（v1.2 核心修正）==========
        # 模拟 create 后真实销售出库 10（实物与账面同步降）：快照=100、实盘=90 → 出库后台账=90、实物也=90。
        # v1 错误口径会以旧实盘 90... 实际此处旧实盘 90=新账面 90，故换个方向：让实物未变、账面被出库，
        # 构造「旧实盘 90 vs 新账面 80」的错位（真实业务：盘的是出库前的 90，出库后账面 80，旧实盘不能再调新账面）。
        q("UPDATE inv_stock_ledger SET qty_available=80 WHERE id=1", db)
        q("UPDATE inv_batch SET qty_remaining=40 WHERE id=1", db)
        rid_apply_stale = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply",
                         {"stocktakeId": sid, "requestId": rid_apply_stale})
        check("账面变动后 apply 被拒(非200)", bcode(resp) != 200,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        check("stale 拒绝文案含重新盘点", "重新盘点" in str(resp) or "snapshotBook" in str(resp),
              str(resp)[:200])
        # 拒绝后不得有任何写入：台账仍 80、单仍 DRAFT、qty_book 快照仍 100、无 ADJUSTMENT/流水
        code, resp = api(tok_ae, "GET", f"/noon/inventory/stocktake/get?id={sid}")
        stale_items = data_of(resp).get("items") or []
        check("stale 后单仍 DRAFT", data_of(resp).get("head", {}).get("status") == "DRAFT",
              str(data_of(resp).get("head"))[:150])
        check("stale 后 qty_book 快照不变(100)", len(stale_items) == 1 and stale_items[0].get("qtyBook") == 100,
              str(stale_items)[:200])
        check("stale 后台账未被调整(80)", q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "80")
        check("stale 后无 ADJUSTMENT 批次",
              q("SELECT COUNT(*) FROM inv_batch WHERE source_type='ADJUSTMENT' AND sku_id=1", db, raw=True).stdout.strip() == "0")
        check("stale 后无盘盈盘亏流水",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type IN ('STOCKTAKE_GAIN','STOCKTAKE_LOSS')", db, raw=True).stdout.strip() == "0")
        # 同 requestId 重试仍拒绝（错误结果不得被幂等缓存）
        code, resp2 = api(tok_ae, "POST", "/noon/inventory/stocktake/apply",
                          {"stocktakeId": sid, "requestId": rid_apply_stale})
        check("stale 同键重试仍被拒(非200)", bcode(resp2) != 200, f"bcode={bcode(resp2)} {str(resp2)[:120]}")

        # ========== 3. 重盘正确路径：按新账面重新建单再应用 ==========
        # 新账面 80、实物 80（出库后真实一致）。重新建单实盘 85（此后真实盘盈 5，且有历史成本可承接）。
        rid_create_r = uuid.uuid4().hex
        cr = {"warehouseId": 1, "period": "2026-11", "requestId": rid_create_r,
              "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qtyActual": 85, "countEventId": "CNT-R1"}]}
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create", cr)
        sid_r = data_of(resp).get("stocktakeId")
        sno_r = data_of(resp).get("stocktakeNo")
        check("重盘建单成功(快照=80)", bcode(resp) == 200 and bool(sid_r) and str(sno_r).startswith("ST:AE:"),
              f"bcode={bcode(resp)} {str(resp)[:150]}")
        code, resp = api(tok_ae, "GET", f"/noon/inventory/stocktake/get?id={sid_r}")
        r_items = data_of(resp).get("items") or []
        check("重盘快照=80/qtyActual=85", len(r_items) == 1 and r_items[0].get("qtyBook") == 80
              and r_items[0].get("qtyActual") == 85, str(r_items)[:200])
        rid_apply = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply",
                         {"stocktakeId": sid_r, "requestId": rid_apply})
        check("重盘应用成功(真实盘盈+5)", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        code, resp = api(tok_ae, "GET", f"/noon/inventory/stocktake/get?id={sid_r}")
        applied_items = data_of(resp).get("items") or []
        check("apply 后 qtyBook 保持快照=80/qtyDiff=5",
              len(applied_items) == 1 and applied_items[0].get("qtyBook") == 80 and applied_items[0].get("qtyDiff") == 5,
              str(applied_items)[:200])
        check("apply 后状态 APPLIED", data_of(resp).get("head", {}).get("status") == "APPLIED", str(data_of(resp).get("head"))[:150])
        # 盘盈 +5：台账 80→85；ADJUSTMENT 承接加权成本（批1剩余40@10+批2 40@20 → 加权15）；GAIN 流水；不写消耗
        check("盘盈后台账=85", q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "85")
        check("盘盈新建 ADJUSTMENT 批次(qty=5)",
              q("SELECT COUNT(*) FROM inv_batch WHERE source_type='ADJUSTMENT' AND sku_id=1 AND qty_in=5 AND status='OPEN'", db, raw=True).stdout.strip() == "1")
        check("盘盈写 STOCKTAKE_GAIN 流水",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='STOCKTAKE_GAIN' AND qty_delta=5", db, raw=True).stdout.strip() == "1")
        check("盘盈不写批次消耗", q("SELECT COUNT(*) FROM inv_batch_consumption WHERE biz_event_key LIKE 'STK:%'", db, raw=True).stdout.strip() == "0")
        # apply 幂等重放
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply", {"stocktakeId": sid_r, "requestId": rid_apply})
        check("apply 重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        check("apply 重放未重复过账", q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "85")
        # 动作冲突：同一 requestId 当作 create → 400（CREATE/APPLY 隔离）
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create",
                         {"warehouseId": 1, "requestId": rid_apply, "lines": [{"skuId": 1, "qtyActual": 1, "countEventId": "C1"}]})
        check("同键跨动作被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")

        # ========== 3b. 零成本盘盈必须拒绝（v1.2：无历史成本不进 FIFO）==========
        # SKU 无批次/无成本，建单实盘 5 → 快照=0、盘盈 5 → 拒绝
        rid_zc = uuid.uuid4().hex
        zc = {"warehouseId": 1, "period": "2026-11", "requestId": rid_zc,
              "lines": [{"skuId": 777, "batchNo": "", "qtyActual": 5, "countEventId": "CNT-ZC"}]}
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create", zc)
        sid_zc = data_of(resp).get("stocktakeId")
        check("零成本盘盈单创建成功", bcode(resp) == 200 and bool(sid_zc), f"bcode={bcode(resp)} {str(resp)[:150]}")
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply",
                         {"stocktakeId": sid_zc, "requestId": uuid.uuid4().hex})
        check("零成本盘盈被拒(非200)", bcode(resp) != 200, f"bcode={bcode(resp)} {str(resp)[:200]}")
        check("零成本拒绝文案含成本/估值", "成本" in str(resp) or "估值" in str(resp), str(resp)[:200])
        check("零成本盘盈无批次写入",
              q("SELECT COUNT(*) FROM inv_batch WHERE sku_id=777 AND source_type='ADJUSTMENT'", db, raw=True).stdout.strip() == "0")

        # ========== 4. 盘亏闭环（独立盘点单：按当前账面重新建单，实盘低于账面 → FIFO 守恒）==========
        # 当前台账=85、批1剩余40@10、批2 40@20。建单实盘 75 → 快照85、盘亏10，FIFO 先扣批1 40→30。
        rid_create2 = uuid.uuid4().hex
        c2 = {"warehouseId": 1, "period": "2026-11", "requestId": rid_create2,
              "lines": [{"skuId": 1, "batchNo": "", "qtyActual": 75, "countEventId": "CNT-D2"}]}
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/create", c2)
        sid2 = data_of(resp).get("stocktakeId")
        check("盘亏单创建成功(快照=85)", bcode(resp) == 200 and bool(sid2), f"bcode={bcode(resp)} {str(resp)[:150]}")
        rid_apply2 = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/stocktake/apply", {"stocktakeId": sid2, "requestId": rid_apply2})
        check("盘亏应用成功", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:200]}")
        check("盘亏后台账=75", q("SELECT qty_available FROM inv_stock_ledger WHERE id=1", db, raw=True).stdout.strip() == "75")
        check("盘亏 FIFO 扣批1剩余=30", q("SELECT qty_remaining FROM inv_batch WHERE id=1", db, raw=True).stdout.strip() == "30")
        check("盘亏写 STOCKTAKE_LOSS 流水",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='STOCKTAKE_LOSS' AND qty_delta=-10", db, raw=True).stdout.strip() == "1")
        # STK 事件键消耗：键=STK:<item表id>:CNT-D2，批1 一条，unit_cost=10，qty=10
        item_id_2 = q(f"SELECT id FROM inv_stocktake_item WHERE stocktake_id={sid2}", db, raw=True).stdout.strip()
        check("盘亏写 STK 消耗(批1/成本10)",
              q(f"SELECT COUNT(*) FROM inv_batch_consumption WHERE biz_event_key='STK:{item_id_2}:CNT-D2' "
                "AND batch_id=1 AND qty=10 AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1")

        # ========== 4. 授权边界 ==========
        # viewer 无盘点写权限 → 403
        code, resp = api(tok_viewer, "POST", "/noon/inventory/stocktake/create",
                         {"warehouseId": 1, "requestId": uuid.uuid4().hex, "lines": [{"skuId": 1, "qtyActual": 1, "countEventId": "C2"}]})
        check("viewer 无建单权限(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # 撤销 AE 授权后，opAE 不能读盘点详情（result/get 按仓库真实站点鉴权）
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "GET", f"/noon/inventory/stocktake/get?id={sid}")
        check("撤销授权后详情 403", bcode(resp) == 403, f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)

        # ========== 5. 列表/详情/分页 ==========
        code, resp = api(tok_ae, "GET", "/noon/inventory/stocktake/list?offset=0&limit=10")
        check("盘点列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 2, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/stocktake/result?requestId=" + rid_apply)
        check("result 命中已过账盘点", bcode(resp) == 200 and data_of(resp).get("replayed") is True)

        # ========== 6. 真实浏览器：新建 → 应用 ==========
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
                    Path("/tmp/noon-m4-stocktake-browser-ctx.json").write_text(json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m4-stocktake-browser-check.js", encoding="utf-8"),
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
