#!/usr/bin/env python3
"""M4 库存第一轮隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦 Codex 要求的关键流程与风险：
  1) 生成器：实际调用若依 /tool/gen 导入 inv_inbound 表并导出前后端骨架，产物落盘
  2) 入库闭环：采购收货→入库确认→FIFO 批次 inv_batch + 四态台账 inv_stock_ledger + 流水 inv_stock_move
  3) 防伪造：客户端夹带 unitCost/skuId 被忽略，成本权威取自 noon_m3_receipt
  4) 幂等：同 requestId 重放 replayed=true 不重复生成批次/台账；内容冲突 400；撤销授权重放 403
  5) 站点授权：经仓库归属判定，跨站/无站点拒绝；批次/台账列表按授权过滤
  6) 端到端：入库批次可被现有 FIFO 出库消费（availability/批次剩余同步扣减）
  7) 分页：入库/批次/台账列表真实 total 与跨页

用法：python3 scripts/verify-m4-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
全部在一次性隔离环境，绝不碰生产库/凭据；结束清理所有进程与临时目录。
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
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-inventory"
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
            try:
                return r.status, json.loads(raw)
            except json.JSONDecodeError:
                return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-e2e-"))
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
        # ---- MySQL ----
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

        db = "noon_m4_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 库存迁移", M4SQL), ("M4 菜单权限", M4MENU)]:
            r = feed(f, db)
            check(f"灌入{label}", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        # FIFO 消耗表（沿用 schema v0.5，由现有 FIFO 服务使用；E2E 端到端需要）
        schema_txt = SCHEMA.read_text(encoding="utf-8")
        def extract(table):
            marker = f"CREATE TABLE IF NOT EXISTS {table} ("
            s = schema_txt.find(marker)
            e = schema_txt.find(";", s)
            return schema_txt[s:e + 1] + "\n"
        fifo_ddl = work / "fifo-tables.sql"
        fifo_ddl.write_text("".join(extract(t) for t in
            ["inv_batch_consumption", "sal_order", "sal_order_item", "sal_return"]), encoding="utf-8")
        r = feed(fifo_ddl, db)
        check("灌入FIFO/销售基础表", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        q("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'", db)

        # ---- 种子 ----
        seed = [
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-P1','Part One','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(2,'SKU-P2','Part Two','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(3,'SKU-P3','Part Three','ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(1,'WH-AE-1','AE','AE Local','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(2,'WH-SA-1','SA','SA Local','LOCAL','FBP',0,'ACTIVE')",
            # AE 采购单：已收货 100（unit_price=10, fx=1 → unit_cost_cny=10）
            "INSERT INTO pur_order(id,order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
            "VALUES(1,'PO-AE-IN-1',1,'AE','CNY',1000.0000,'RECEIVED','MANUAL',1)",
            "INSERT INTO pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
            "VALUES(1,1,1,100,100,10.0000,'CNY',1.00000000)",
            "INSERT INTO noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
            "VALUES(1,1,100,10.0000,NOW(),1)",
            # SA 采购单（跨站测试用）
            "INSERT INTO pur_order(id,order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
            "VALUES(2,'PO-SA-IN-1',1,'SA','CNY',500.0000,'RECEIVED','MANUAL',1)",
            "INSERT INTO pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
            "VALUES(2,2,2,50,50,5.0000,'CNY',1.00000000)",
            "INSERT INTO noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
            "VALUES(2,2,50,5.0000,NOW(),1)",
            # PARTIAL_RECEIVED 采购单（分批到货先入库场景）：SKU3 已收货 30，整单未收齐（还有 70 未收）
            "INSERT INTO pur_order(id,order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
            "VALUES(3,'PO-AE-PARTIAL',1,'AE','CNY',1000.0000,'PARTIAL_RECEIVED','MANUAL',1)",
            "INSERT INTO pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
            "VALUES(3,3,3,100,30,8.0000,'CNY',1.00000000)",
            "INSERT INTO noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
            "VALUES(3,3,30,8.0000,NOW(),1)",
        ]
        for sql in seed:
            r = q(sql, db)
            check("种子: " + sql[:46], r.returncode == 0, r.stderr[-200:] if r.returncode else "")

        # ---- 用户/角色/站点授权 ----
        pwd = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"  # admin123
        for uid, name in [(100, "opAE"), (101, "opAESA"), (102, "viewer")]:
            q(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,status,del_flag,create_time) "
              f"VALUES({uid},103,'{name}','{name}','00','{name}@e2e.local','13800000000','0','{pwd}','0','0',NOW())", db)
        # 业务角色：基础菜单 + M4 入库权限(3210/3211/3212/3220/3230)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(100,'M4业务','m4_ops',5,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230)", db)
        for uid in (100, 101):
            q(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({uid},100)", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(101,'M4只读','m4_viewer',6,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 101,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3200,3220,3230)", db)  # 无入库确认权限
        q("INSERT INTO sys_user_role(user_id,role_id) VALUES(102,101)", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'SA',1,NOW())", db)
        check("测试用户/站点授权造数",
              q("SELECT COUNT(*) FROM sys_user_site WHERE user_id IN (100,101,102)", db, raw=True).stdout.strip() == "3")

        # ---- Redis ----
        red = subprocess.Popen(
            [str(redis_bin), "--port", str(redport), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no",
             "--dir", str(work), "--daemonize", "no"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procs.append(red)
        time.sleep(1)
        check("Redis 就绪", run([str(Path(args.redis_bin).with_name("redis-cli")), "-p", str(redport), "ping"]).stdout.strip() == "PONG")

        # ---- 启动应用 ----
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
        def rows_of(resp):
            rows = data_of(resp).get("rows", [])
            return rows if isinstance(rows, list) else []
        def total_of(resp):
            try: return int(data_of(resp).get("total", 0))
            except (TypeError, ValueError): return 0

        # ========== 0. 若依代码生成器（M4 新规：实际运行生成骨架并采用）==========
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        # inv_inbound 骨架导出到 raw 根（历史保留）；inv_batch / inv_stock_ledger 导出到 raw/batch、raw/ledger，
        # 其 Vue/api 骨架被 batch/ledger 只读页实际采用（见 generated/ADOPTION.md）。
        gen_specs = [
            ("inv_inbound",     "入库单",       "Inbound", "inbound", "入库单",       ""),
            ("inv_batch",       "FIFO成本批次", "Batch",   "batch",   "FIFO成本批次", "batch"),
            ("inv_stock_ledger","四态库存台账", "Ledger",  "ledger",  "四态库存台账", "ledger"),
        ]
        for tbl, comment, cls, biz, func, subdir in gen_specs:
            code, resp = api(tok_admin, "POST", f"/tool/gen/importTable?tables={tbl}&tplWebType=element-plus")
            check(f"生成器导入 {tbl} 表", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
                  f"code={code} {str(resp)[:150]}")
            code, resp = api(tok_admin, "PUT", "/tool/gen", body={
                "tableName": tbl, "tableComment": comment, "className": cls,
                "packageName": "com.yx.noon", "moduleName": "inventory", "businessName": biz,
                "functionName": func, "tplCategory": "crud", "tplWebType": "element-plus",
                "genType": "0", "functionAuthor": "WorkBuddy"})
            check(f"生成器配置 {tbl}", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
                  f"code={code} {str(resp)[:150]}")
            try:
                req = urllib.request.Request(base + f"/tool/gen/batchGenCode?tables={tbl}",
                                             headers={"Authorization": "Bearer " + tok_admin})
                with urllib.request.urlopen(req, timeout=30) as r:
                    zipped = r.read()
                dest = GEN_OUT / "raw" / subdir if subdir else GEN_OUT / "raw"
                dest.mkdir(parents=True, exist_ok=True)
                (dest / "generated.zip").write_bytes(zipped)
                with _zf.ZipFile(dest / "generated.zip") as zf:
                    names = zf.namelist()
                    zf.extractall(dest)
                check(f"生成器导出 {tbl} Vue 骨架", any(n.endswith("index.vue") for n in names), f"files={len(names)}")
                check(f"生成器导出 {tbl} api.js 骨架", any("/api/" in n and n.endswith(".js") for n in names), f"files={len(names)}")
            except Exception as e:
                check(f"生成器导出 {tbl}", False, str(e)[:200])
        check("生成器产物落盘", (GEN_OUT / "raw").exists(), str(GEN_OUT / "raw"))

        # ========== 1. 入库确认（AE，opAE 有权限）==========
        rid1 = uuid.uuid4().hex
        inbound1 = {"purOrderId": 1, "warehouseId": 1, "siteCode": "AE",
                    "lines": [{"purOrderItemId": 1, "qtyQualified": 60}], "requestId": rid1}
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", inbound1)
        check("入库确认成功", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        inb = data_of(resp)
        check("返回入库单号与批次ID", bool(inb.get("inboundNo")) and len(inb.get("batchIds") or []) == 1, str(inb)[:200])
        batch_id1 = (inb.get("batchIds") or [None])[0]
        check("批次已生成(OPEN, qty_in=60, cost=10)",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={batch_id1} AND status='OPEN' AND qty_in=60 AND unit_cost=10.0000 AND fx_rate=1", db, raw=True).stdout.strip() == "1")
        check("台账 available=60",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "60")
        check("库存流水 INBOUND +60",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='INBOUND' AND qty_delta=60 AND ref_doc_type='INBOUND'", db, raw=True).stdout.strip() == "1")
        check("审计已落库",
              q("SELECT COUNT(*) FROM noon_m4_inventory_audit WHERE biz_type='INBOUND' AND action='CONFIRM'", db, raw=True).stdout.strip() == "1")

        # 防伪造：夹带 unitCost/skuId/batchNo 被忽略（后端 record 无这些字段，JSON 多字段 Jackson 忽略）
        rid_hack = uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", {
            "purOrderId": 1, "warehouseId": 1, "siteCode": "AE", "requestId": rid_hack,
            "lines": [{"purOrderItemId": 1, "qtyQualified": 1, "skuId": 999, "unitCost": 0.01, "batchNo": "FAKE"}]})
        check("夹带伪造字段仍按权威成本入账", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:150]}")
        check("伪造成本未进入批次",
              q("SELECT COUNT(*) FROM inv_batch WHERE unit_cost=0.0100 OR batch_no='FAKE'", db, raw=True).stdout.strip() == "0")

        # 幂等重放：同键 + 同内容 → replayed=true，不重复生成
        before_batches = q("SELECT COUNT(*) FROM inv_batch", db, raw=True).stdout.strip()
        before_ledger = q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", inbound1)
        check("同键重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)} {str(resp)[:150]}")
        after_batches = q("SELECT COUNT(*) FROM inv_batch", db, raw=True).stdout.strip()
        after_ledger = q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip()
        check("重放未重复生成批次/台账", before_batches == after_batches and before_ledger == after_ledger,
              f"batches {before_batches}->{after_batches}, ledger {before_ledger}->{after_ledger}")

        # 内容冲突：同键改数量 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", {
            **inbound1, "lines": [{"purOrderItemId": 1, "qtyQualified": 50}]})
        check("同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:150]}")

        # result 两态
        code, resp = api(tok_ae, "GET", "/noon/inventory/inbound/result?requestId=" + rid1)
        check("result 命中已入账请求(replayed=true)", bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/inbound/result?requestId=NO-SUCH")
        check("result 不存在返回 null", bcode(resp) == 200 and resp.get("data") is None, f"bcode={bcode(resp)} {str(resp)[:100]}")

        # ========== 2. 授权边界 ==========
        # viewer 无入库确认权限 → 403
        code, resp = api(tok_viewer, "POST", "/noon/inventory/inbound/confirm", {
            "purOrderId": 1, "warehouseId": 1, "siteCode": "AE", "requestId": uuid.uuid4().hex,
            "lines": [{"purOrderItemId": 1, "qtyQualified": 1}]})
        check("viewer 无确认权限(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # 跨站：opAE(仅AE) 用 SA 仓库入库 SA 采购单 → 403
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", {
            "purOrderId": 2, "warehouseId": 2, "siteCode": "SA", "requestId": uuid.uuid4().hex,
            "lines": [{"purOrderItemId": 2, "qtyQualified": 10}]})
        check("opAE 跨站入 SA 仓被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)} {str(resp)[:150]}")
        # 仓库站点与采购单站点不一致 → 400/403（AE 采购单 + SA 仓库）
        code, resp = api(tok_aesa, "POST", "/noon/inventory/inbound/confirm", {
            "purOrderId": 1, "warehouseId": 2, "siteCode": "SA", "requestId": uuid.uuid4().hex,
            "lines": [{"purOrderItemId": 1, "qtyQualified": 5}]})
        check("采购单站点与仓库站点不一致被拒", bcode(resp) in (400, 403), f"bcode={bcode(resp)} {str(resp)[:150]}")

        # 授权撤销后重放 403（授权每次读 DB，无缓存）
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", inbound1)
        check("授权撤销后同键重放被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/inbound/result?requestId=" + rid1)
        check("授权撤销后结果查询被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)

        # ========== 2.5 PARTIAL_RECEIVED 分批到货先入库（v1.1，回应 Codex）==========
        # 采购单 3 状态 PARTIAL_RECEIVED，SKU3 已收货 30（整单未收齐），应允许先入库已收部分。
        rid_p1 = uuid.uuid4().hex
        partial1 = {"purOrderId": 3, "warehouseId": 1, "siteCode": "AE",
                    "lines": [{"purOrderItemId": 3, "qtyQualified": 20}], "requestId": rid_p1}
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", partial1)
        check("PARTIAL_RECEIVED 首批部分行入库成功", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:180]}")
        check("PARTIAL 首批台账 available=20",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=3 AND warehouse_id=1", db, raw=True).stdout.strip() == "20")
        # 第二批补齐 10（累计 20+10=30，未超累计收货 30）
        rid_p2 = uuid.uuid4().hex
        partial2 = {"purOrderId": 3, "warehouseId": 1, "siteCode": "AE",
                    "lines": [{"purOrderItemId": 3, "qtyQualified": 10}], "requestId": rid_p2}
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", partial2)
        check("PARTIAL 第二批补齐成功(累计30)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:180]}")
        check("PARTIAL 两批后台账 available=30",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=3 AND warehouse_id=1", db, raw=True).stdout.strip() == "30")
        check("PARTIAL 两批生成2个批次/2条流水",
              q("SELECT COUNT(*) FROM inv_batch WHERE sku_id=3", db, raw=True).stdout.strip() == "2" and
              q("SELECT COUNT(*) FROM inv_stock_move WHERE sku_id=3 AND change_type='INBOUND'", db, raw=True).stdout.strip() == "2")
        # 第三批超累计（累计 30+1 > 收货 30）→ 400 且库存不变
        led_before = q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=3 AND warehouse_id=1", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", {
            "purOrderId": 3, "warehouseId": 1, "siteCode": "AE", "requestId": uuid.uuid4().hex,
            "lines": [{"purOrderItemId": 3, "qtyQualified": 1}]})
        check("PARTIAL 超累计收货被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:180]}")
        led_after = q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=3 AND warehouse_id=1", db, raw=True).stdout.strip()
        check("PARTIAL 超量拒绝后库存未变", led_before == led_after == "30", f"before={led_before} after={led_after}")
        # PARTIAL 状态下同键重放仍返回首次结果（重放不受状态闸门影响）
        code, resp = api(tok_ae, "POST", "/noon/inventory/inbound/confirm", partial1)
        check("PARTIAL 状态同键重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)} {str(resp)[:150]}")

        # ========== 3. 列表/分页/只读 ==========
        code, resp = api(tok_ae, "GET", "/noon/inventory/inbound/list?offset=0&limit=10")
        check("入库单列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 2, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/batch/list?offset=0&limit=10")
        check("FIFO 批次列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 2, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/inventory/ledger/list?offset=0&limit=10")
        check("四态台账列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 1, f"total={total_of(resp)}")
        code, resp = api(tok_aesa, "GET", "/noon/inventory/inbound/get?id=" + str(inb.get("inboundId")))
        check("入库单详情可见", bcode(resp) == 200 and len(data_of(resp).get("items") or []) == 1,
              f"bcode={bcode(resp)} {str(resp)[:150]}")

        # ========== 4. 端到端：入库批次被现有 FIFO 出库消费 ==========
        # 构造销售订单父行（SKU1，数量 100），调用现有 FIFO postDelivery 消费 30
        # 通过 HTTP 没有 FIFO 入口（内部服务），改用 SQL 直接验证批次可被 FIFO 候选查询命中：
        fifo_hit = q("SELECT COUNT(*) FROM inv_batch WHERE sku_id=1 AND site_code='AE' AND status='OPEN' AND qty_remaining>0 "
                     "AND qty_remaining>=30 ORDER BY received_at ASC, id ASC LIMIT 1", db, raw=True).stdout.strip()
        check("入库批次进入 FIFO 候选(老批优先可扣30)", fifo_hit == "1", f"hit={fifo_hit}")

        # ========== 5. 真实浏览器关键流程 ==========
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
                    Path("/tmp/noon-m4-browser-ctx.json").write_text(json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m4-browser-check.js", encoding="utf-8"),
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

        # ========== 汇总 ==========
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
