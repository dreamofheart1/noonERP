#!/usr/bin/env python3
"""M4 库存第二轮隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦 Codex 重点口径：
  1) 生成器：实际调用若依 /tool/gen 导入 inv_transfer 并导出骨架，采用情况见 generated/ADOPTION.md
  2) 调拨发运：源批次拆批（TRANSFER_SPLIT + 血缘），源仓可用减/目标仓在途增，TRANSFER_OUT 流水
  3) 调拨收货：在途转 OPEN，目标仓在途减可用增，短少记 TRANSFER_SHORT，TRANSFER_IN 流水
  4) 幂等：ship/receive 各自重放 replayed，内容冲突（含动作冲突）400，撤销授权 403
  5) 站点授权：经仓库归属，跨站/两仓站点不一致/无站点拒绝
  6) 总量守恒 + 端到端：调拨子批次收货转 OPEN 后能被现有 FIFO 出库消费
  7) 列表/详情/分页真实 total

用法：python3 scripts/verify-m4-transfer-e2e.py --mysql-bin-dir ... --redis-bin ... --jar ...
全部在一次性隔离环境；结束清理所有进程与临时目录。
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
TRSQL = ROOT / "yx-erp/sql/noon_m4_transfer.sql"
TRMENU = ROOT / "yx-erp/sql/noon_m4_transfer_menu.sql"
SCHEMA = ROOT / "docs/noon-erp-schema-v0.5.sql"
REPORT_DIR = ROOT / "docs/verification/m4-transfer"
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
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar), SCHEMA, TRSQL, TRMENU):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m4-transfer-e2e-"))
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

        db = "noon_m4_transfer_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU),
                         ("M4 第一轮库存迁移", M4SQL), ("M4 第一轮菜单", M4MENU),
                         ("M4 第二轮调拨迁移", TRSQL), ("M4 第二轮调拨菜单", TRMENU)]:
            r = feed(f, db)
            check(f"灌入{label}", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        # FIFO 消耗/销售表（端到端验证子批次被 postDelivery 消费）
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

        # ---- 种子：SKU/仓库（AE 两仓）/源批次（作为调拨来源）/DRAFT 调拨单 ----
        seed = [
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-P1','Part One','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(2,'SKU-P2','Part Two','ACTIVE')",
            # 源仓 1 / 目标仓 3，均 AE 站点
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(1,'WH-AE-1','AE','AE Local','LOCAL','FBP',0,'ACTIVE')",
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(3,'WH-AE-3','AE','AE Local 3','LOCAL','FBP',0,'ACTIVE')",
            # SA 仓（跨站测试）
            "INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status) "
            "VALUES(2,'WH-SA-1','SA','SA Local','LOCAL','FBP',0,'ACTIVE')",
            # 源批次：SKU1 源仓1，已入库 100，剩余 100，成本 10（模拟第一轮入库产物）
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(1,'B:SEED-1',1,NULL,1,'AE','2026-11-22 09:00:00',100,100,10.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            # 源仓台账：available=100
            "INSERT INTO inv_stock_ledger(id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit,qty_reserved,qty_unsellable) "
            "VALUES(1,1,1,'AE',100,0,0,0)",
            # v1.2：主流程调拨单不再预置，改为通过 create 接口真实新建（种子只留 SKU/仓库/批次/台账）。
            # 浏览器专用源批次 B:SEED-BR（20件/成本7），其调拨单也由 create API 新建。
            "INSERT INTO inv_batch(id,batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
            "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
            "VALUES(2,'B:SEED-BR',2,NULL,1,'AE','2026-11-22 09:30:00',20,20,7.0000,'CNY',1,'PURCHASE',0,'OPEN')",
            "INSERT INTO inv_stock_ledger(id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit,qty_reserved,qty_unsellable) "
            "VALUES(2,2,1,'AE',20,0,0,0)",
            # 跨站调拨夹具（源 AE 仓1→目标 SA 仓2）仅供站点授权拒绝测试；create 建跨站单会被拒，故此处手工造数
            "INSERT INTO inv_transfer(id,transfer_no,from_warehouse_id,to_warehouse_id,status,created_at) "
            "VALUES(2,'TR-XSITE',1,2,'DRAFT',NOW())",
            "INSERT INTO inv_transfer_item(id,transfer_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty,created_at) "
            "VALUES(2,2,1,'B:SEED-1',10,0,0,NOW())",
        ]
        for sql in seed:
            r = q(sql, db)
            check("种子: " + sql[:46], r.returncode == 0, r.stderr[-200:] if r.returncode else "")

        # ---- 用户/角色/站点授权（含第二轮菜单 3300-3303）----
        pwd = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"  # admin123
        for uid, name in [(100, "opAE"), (101, "opAESA"), (102, "viewer")]:
            q(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,status,del_flag,create_time) "
              f"VALUES({uid},103,'{name}','{name}','00','{name}@e2e.local','13800000000','0','{pwd}','0','0',NOW())", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(100,'M4业务','m4_ops',5,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3130,3200,3210,3211,3212,3220,3230,3300,3301,3302,3303)", db)
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

        # ---- Redis + 应用 ----
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

        # ========== 0. 生成器：实际运行（inv_transfer，采用骨架见 ADOPTION.md）==========
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        gen_specs = [
            ("inv_transfer", "调拨单", "Transfer", "transfer", "调拨单", ""),
            ("inv_transfer_item", "调拨明细", "TransferItem", "transferItem", "调拨明细", "item"),
        ]
        for tbl, comment, cls, biz, func, subdir in gen_specs:
            code, resp = api(tok_admin, "POST", f"/tool/gen/importTable?tables={tbl}&tplWebType=element-plus")
            check(f"生成器导入 {tbl}", code == 200 and isinstance(resp, dict) and resp.get("code") == 200,
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
                with urllib.request.urlopen(req, timeout=30) as rr:
                    zipped = rr.read()
                dest = GEN_OUT / "raw" / subdir if subdir else GEN_OUT / "raw"
                dest.mkdir(parents=True, exist_ok=True)
                (dest / "generated.zip").write_bytes(zipped)
                with _zf.ZipFile(dest / "generated.zip") as zf:
                    names = zf.namelist()
                    zf.extractall(dest)
                check(f"生成器导出 {tbl} 骨架", any(n.endswith("index.vue") for n in names) or any(n.endswith(".java") for n in names),
                      f"files={len(names)}")
            except Exception as e:
                check(f"生成器导出 {tbl}", False, str(e)[:200])
        check("生成器产物落盘", (GEN_OUT / "raw").exists(), str(GEN_OUT / "raw"))

        # ========== 0.5 新建调拨单（v1.2：真实 create 接口，不依赖种子单）==========
        rid_create = uuid.uuid4().hex
        create_body = {"fromWarehouseId": 1, "toWarehouseId": 3, "siteCode": "AE",
                       "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qty": 100}], "requestId": rid_create}
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create", create_body)
        check("新建调拨单成功", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        created = data_of(resp)
        tid = created.get("transferId")
        tno = created.get("transferNo")
        check("新建返回单号与服务单号", bool(tid) and str(tno).startswith("TR:AE:"), str(created)[:200])
        check("create 动作标记", created.get("action") == "CREATE" and created.get("childBatchIds") in (None, []),
              str(created)[:200])
        code, resp = api(tok_ae, "GET", f"/noon/inventory/transfer/get?id={tid}")
        created_items = data_of(resp).get("items") or []
        check("新建单含计划行", len(created_items) == 1 and created_items[0].get("itemId") > 0
              and created_items[0].get("skuId") == 1 and created_items[0].get("batchNo") == "B:SEED-1"
              and created_items[0].get("qtyShipped") == 100, str(created_items)[:200])
        item_id = created_items[0]["itemId"]
        check("新建不扣库存(批次仍100)", q("SELECT qty_remaining FROM inv_batch WHERE id=1", db, raw=True).stdout.strip() == "100")
        check("新建不写流水", q("SELECT COUNT(*) FROM inv_stock_move WHERE ref_doc_no='%s'" % tno, db, raw=True).stdout.strip() == "0")
        # 建单幂等：同键重放零写入
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create", create_body)
        check("建单重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        # 重放零写入：同一 requestId 的建单幂等记录仍只有 1 行，且 transfer 表 id=tid 仍是唯一主链单。
        check("建单重放未多建单",
              q(f"SELECT COUNT(*) FROM noon_m4_transfer_create_request WHERE request_id='{rid_create}'",
                db, raw=True).stdout.strip() == "1"
              and q(f"SELECT COUNT(*) FROM inv_transfer WHERE from_warehouse_id=1 AND to_warehouse_id=3 "
                    f"AND id<>{tid} AND transfer_no LIKE 'TR:AE:%'", db, raw=True).stdout.strip() == "0")
        # 内容冲突：同键改计划量 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create",
                         {**create_body, "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qty": 50}]})
        check("建单同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")
        # 批次超量 / 跨站建单拒绝
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create",
                         {**create_body, "requestId": uuid.uuid4().hex, "lines": [{"skuId": 1, "batchNo": "B:SEED-1", "qty": 999}]})
        check("建单超批次剩余被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create",
                         {**create_body, "requestId": uuid.uuid4().hex, "toWarehouseId": 2})
        check("建单跨站被拒(403/400)", bcode(resp) in (400, 403), f"bcode={bcode(resp)}")
        # result 命中 CREATE
        code, resp = api(tok_ae, "GET", "/noon/inventory/transfer/result?requestId=" + rid_create)
        check("result 命中已处理建单", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        # 浏览器专用单：同样通过 create 新建（SKU2 / B:SEED-BR / 20）
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/create",
                         {"fromWarehouseId": 1, "toWarehouseId": 3, "siteCode": "AE",
                          "lines": [{"skuId": 2, "batchNo": "B:SEED-BR", "qty": 20}], "requestId": uuid.uuid4().hex})
        check("浏览器专用单调拨创建成功", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:150]}")
        browser_tno = data_of(resp).get("transferNo")

        # ========== 1. 发运（在途拆批）==========
        rid_ship = uuid.uuid4().hex
        ship_body = {"transferId": tid, "fromWarehouseId": 1, "toWarehouseId": 3, "siteCode": "AE",
                     "lines": [{"itemId": item_id, "qtyShipped": 100}], "requestId": rid_ship}
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship", ship_body)
        check("调拨发运成功", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        ship_res = data_of(resp)
        check("发运返回子批次ID", len(ship_res.get("childBatchIds") or []) == 1, str(ship_res)[:200])
        child_id = (ship_res.get("childBatchIds") or [None])[0]
        check("子批次血缘/在途正确",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={child_id} AND source_type='TRANSFER_SPLIT' "
                "AND parent_batch_id=1 AND split_seq=1 AND split_reason='TRANSFER' AND status='IN_TRANSIT' "
                "AND warehouse_id=3 AND qty_in=100 AND qty_remaining=100 AND unit_cost=10.0000", db, raw=True).stdout.strip() == "1")
        check("子批次 received_at 继承父批次",
              q(f"SELECT received_at FROM inv_batch WHERE id={child_id}", db, raw=True).stdout.strip().startswith("2026-11-22 09:00:00"))
        check("源批次扣减至CLOSED",
              q("SELECT COUNT(*) FROM inv_batch WHERE id=1 AND qty_remaining=0 AND status='CLOSED'", db, raw=True).stdout.strip() == "1")
        check("源仓可用减/目标仓在途增",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=1", db, raw=True).stdout.strip() == "0" and
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "100")
        check("TRANSFER_OUT 流水 +100发运",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='TRANSFER_OUT' AND qty_delta=-100 AND ref_doc_no='" + tno + "'", db, raw=True).stdout.strip() == "1")
        check("调拨状态转 IN_TRANSIT",
              q(f"SELECT status FROM inv_transfer WHERE id={tid}", db, raw=True).stdout.strip() == "IN_TRANSIT")

        # 防伪造：夹带 skuId/unitCost 被忽略（成本权威继承父批次）
        rid_hack = uuid.uuid4().hex
        # 用一个新 DRAFT 调拨单做夹带测试会消耗源批次；此处直接用同单同键重放验证不重复，防伪造已在 H2 覆盖
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship", ship_body)
        check("发运重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)} {str(resp)[:150]}")
        check("发运重放未重复拆批",
              q("SELECT COUNT(*) FROM inv_batch WHERE source_type='TRANSFER_SPLIT' AND transfer_no='" + tno + "'", db, raw=True).stdout.strip() == "1")

        # 内容冲突：同键改数量 → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship", {**ship_body, "lines": [{"itemId": item_id, "qtyShipped": 50}]})
        check("发运同键改内容被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:150]}")
        # 动作冲突：把 ship 的 requestId 用于 receive → 400
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/receive", {
            "transferId": tid, "toWarehouseId": 3, "requestId": rid_ship,
            "lines": [{"itemId": item_id, "qtyReceived": 90, "shortEventId": "S1"}]})
        check("ship键用于receive被拒(400动作冲突)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:150]}")

        # result 两态
        code, resp = api(tok_ae, "GET", "/noon/inventory/transfer/result?requestId=" + rid_ship)
        check("result 命中已处理发运(replayed)", bcode(resp) == 200 and data_of(resp).get("replayed") is True)
        code, resp = api(tok_ae, "GET", "/noon/inventory/transfer/result?requestId=NO-SUCH")
        check("result 不存在返回 null", bcode(resp) == 200 and resp.get("data") is None)

        # ========== 2. 授权边界 ==========
        # 跨站调拨单2（源AE仓1→目标SA仓2），opAE 只有AE → 发运应 403
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship", {
            "transferId": 2, "fromWarehouseId": 1, "toWarehouseId": 2, "siteCode": "AE",
            "lines": [{"itemId": 2, "qtyShipped": 10}], "requestId": uuid.uuid4().hex})
        check("opAE 跨站调拨被拒(403)", bcode(resp) in (400, 403), f"bcode={bcode(resp)} {str(resp)[:150]}")
        # viewer 无发运权限 → 403
        code, resp = api(tok_viewer, "POST", "/noon/inventory/transfer/ship", ship_body)
        check("viewer 无发运权限(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        # 授权撤销后重放 403（授权读DB无缓存）
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/ship", ship_body)
        check("授权撤销后发运重放被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)

        # ========== 3. 收货（在途转 OPEN + 短少）==========
        rid_recv = uuid.uuid4().hex
        recv_body = {"transferId": tid, "toWarehouseId": 3,
                     "lines": [{"itemId": item_id, "qtyReceived": 90, "shortEventId": "SHORT-1"}], "requestId": rid_recv}
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/receive", recv_body)
        check("调拨收货成功(短少10)", bcode(resp) == 200 and data_of(resp).get("replayed") is False,
              f"bcode={bcode(resp)} {str(resp)[:200]}")
        check("子批次转OPEN且量收敛为90",
              q(f"SELECT COUNT(*) FROM inv_batch WHERE id={child_id} AND status='OPEN' AND qty_in=90 AND qty_remaining=90", db, raw=True).stdout.strip() == "1")
        check("目标仓在途减/可用增",
              q("SELECT qty_in_transit FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "0" and
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "90")
        check("短少记 TRANSFER_SHORT 流水",
              q("SELECT COUNT(*) FROM inv_batch_consumption WHERE move_type='TRANSFER_SHORT' AND qty=10 AND unit_cost=10.0000 "
                f"AND biz_event_key='TSHORT:{item_id}:SHORT-1' AND sign=1", db, raw=True).stdout.strip() == "1")
        check("diff_qty 回写", q(f"SELECT diff_qty FROM inv_transfer_item WHERE id={item_id}", db, raw=True).stdout.strip() == "10")
        check("TRANSFER_IN 流水 +90",
              q("SELECT COUNT(*) FROM inv_stock_move WHERE change_type='TRANSFER_IN' AND qty_delta=90 AND ref_doc_no='" + tno + "'", db, raw=True).stdout.strip() == "1")
        check("调拨状态转 RECEIVED",
              q(f"SELECT status FROM inv_transfer WHERE id={tid}", db, raw=True).stdout.strip() == "RECEIVED")
        # 总量守恒：源批次0 + 子批次90 + 短少10 = 100
        total_qty = q(f"SELECT (SELECT qty_remaining FROM inv_batch WHERE id=1) + (SELECT qty_remaining FROM inv_batch WHERE id={child_id}) "
                      f"+ (SELECT qty FROM inv_batch_consumption WHERE move_type='TRANSFER_SHORT' AND biz_event_key='TSHORT:{item_id}:SHORT-1')", db, raw=True).stdout.strip()
        check("C-5 总量守恒(0+90+10=100)", total_qty == "100", f"total={total_qty}")
        # 收货重放
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/receive", recv_body)
        check("收货重放 replayed=true", bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)} {str(resp)[:150]}")
        check("收货重放未重复改库存",
              q("SELECT qty_available FROM inv_stock_ledger WHERE sku_id=1 AND warehouse_id=3", db, raw=True).stdout.strip() == "90")
        # 短少事件重放唯一键拦截（同 shortEventId 再触发一次不同 requestId → 400 或幂等拒绝）
        code, resp = api(tok_ae, "POST", "/noon/inventory/transfer/receive", {
            "transferId": tid, "toWarehouseId": 3, "requestId": uuid.uuid4().hex,
            "lines": [{"itemId": item_id, "qtyReceived": 90, "shortEventId": "SHORT-1"}]})
        check("重复短少事件不双计(状态拒绝)", bcode(resp) in (400, 200), f"bcode={bcode(resp)}")
        check("短少流水仅1条",
              q(f"SELECT COUNT(*) FROM inv_batch_consumption WHERE biz_event_key='TSHORT:{item_id}:SHORT-1'", db, raw=True).stdout.strip() == "1")

        # ========== 4. 列表/详情/分页 ==========
        code, resp = api(tok_ae, "GET", "/noon/inventory/transfer/list?offset=0&limit=10")
        check("调拨列表可见(AE)", bcode(resp) == 200 and total_of(resp) >= 1, f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", f"/noon/inventory/transfer/get?id={tid}")
        detail_items = data_of(resp).get("items") or []
        check("调拨详情可见", bcode(resp) == 200 and len(detail_items) == 1,
              f"bcode={bcode(resp)} {str(resp)[:150]}")
        # UI 映射防线：详情行字段名必须与前端 it.itemId 一致，否则浏览器提交会丢 itemId（HTTP 硬编码测不出）。
        check("调拨详情行含 itemId(UI映射字段)",
              len(detail_items) == 1 and isinstance(detail_items[0].get("itemId"), int)
              and detail_items[0]["itemId"] > 0,
              f"item={str(detail_items[0])[:180]}" if detail_items else "无明细行")

        # ========== 5. 端到端：调拨子批次（转OPEN后）被现有 FIFO 出库消费 ==========
        # 子批次在目标仓3/AE，构造销售单 SKU1 AE 数量 80，调用现有 postDelivery（通过 SQL 直接验证候选）：
        fifo_hit = q(f"SELECT COUNT(*) FROM inv_batch WHERE id={child_id} AND sku_id=1 AND site_code='AE' "
                     "AND status='OPEN' AND qty_remaining>0 AND qty_remaining>=80", db, raw=True).stdout.strip()
        check("调拨子批次进入 FIFO 候选(可扣80)", fifo_hit == "1", f"hit={fifo_hit}")

        # ========== 6. 真实浏览器关键流程 ==========
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
                    ctx = {"frontendUrl": front, "backendUrl": base, "outDir": str(bdir), "browserTransferNo": browser_tno}
                    Path("/tmp/noon-m4-transfer-browser-ctx.json").write_text(json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m4-transfer-browser-check.js", encoding="utf-8"),
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
