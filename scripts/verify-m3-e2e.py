#!/usr/bin/env python3
"""M3 供应商与采购隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

聚焦 Codex 要求的关键流程与风险（常规页面细节采取合理抽查）：
  1) 状态机：DRAFT→CONFIRMED→PARTIAL_RECEIVED→RECEIVED→CLOSED 及非法跳转拒绝
  2) 部分收货数量守恒：超收 400、累计≤订购（并发安全留给真库 IT）
  3) 历史价格与成本口径：报价版本化旧单快照不变；成本只含采购价（CNY fx=1、外币换算）
  4) 站点授权隔离：opAE 仅 AE、viewer 无站点零行/拒绝、跨站详情 403
  5) 引用完整性与审计落库

用法：
  python3 scripts/verify-m3-e2e.py \
    --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
    --redis-bin /tmp/redis-7.2.5/src/redis-server \
    --jar /Users/dream/Work/noonERP/yx-erp/yxerp-admin/target/yxerp-admin.jar
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
REPORT_DIR = ROOT / "docs/verification/m3-purchasing"


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
    ap.add_argument("--no-browser", action="store_true", help="跳过真实浏览器验证（默认执行）")
    args = ap.parse_args()

    bindir = Path(args.mysql_bin_dir)
    mysqld, mysql, mysqladmin = bindir / "mysqld", bindir / "mysql", bindir / "mysqladmin"
    redis_bin = Path(args.redis_bin)
    for p in (mysqld, mysql, mysqladmin, redis_bin, Path(args.jar)):
        if not p.exists():
            print("缺少文件:", p, file=sys.stderr)
            return 2

    work = Path(tempfile.mkdtemp(prefix="noon-m3-e2e-"))
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
        if ok:
            passed += 1
        else:
            failed += 1
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
                ready = True
                break
            time.sleep(0.5)
        check("MySQL 就绪", ready)
        if not ready:
            return 2

        db = "noon_m3_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL),
                         ("M3 采购迁移", M3SQL), ("M3 菜单权限", M3MENU)]:
            r = feed(f, db)
            check(f"灌入{label}", r.returncode == 0, r.stderr[-400:] if r.returncode else "")
        q("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'", db)

        # ---- 种子：SKU（M2）+ 供应商（M3）+ 报价（M3）----
        seed = [
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-P1','Part One','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(2,'SKU-P2','Part Two','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(3,'SKU-OFF','Offline Part','INACTIVE')",
            "INSERT INTO md_supplier(id,supplier_no,supplier_name,status) VALUES(1,'SUP-1','Supplier One','ACTIVE')",
            "INSERT INTO md_supplier(id,supplier_no,supplier_name,status) VALUES(2,'SUP-2','Supplier Two','ACTIVE')",
            "INSERT INTO md_supplier(id,supplier_no,supplier_name,status) VALUES(3,'SUP-OFF','Offline Supplier','INACTIVE')",
            "INSERT INTO md_sku_supplier(id,sku_id,supplier_id,unit_price,currency,moq,lead_time_days,effective_from,effective_to) "
            "VALUES(1,1,1,10.0000,'CNY',10,7,'2026-01-01','2026-06-01')",
            "INSERT INTO md_sku_supplier(id,sku_id,supplier_id,unit_price,currency,moq,lead_time_days,effective_from,effective_to) "
            "VALUES(2,1,1,12.0000,'CNY',10,7,'2026-06-01',NULL)",
            "INSERT INTO md_sku_supplier(id,sku_id,supplier_id,unit_price,currency,moq,lead_time_days,effective_from,effective_to) "
            "VALUES(3,2,1,5.0000,'CNY',0,3,'2026-01-01',NULL)",
        ]
        for sql in seed:
            r = q(sql, db)
            check("种子: " + sql[:46], r.returncode == 0, r.stderr[-200:] if r.returncode else "")

        # ---- 测试用户/角色/站点授权 ----
        pwd = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"  # admin123
        users = [(100, "opAE"), (101, "opAESA"), (102, "viewer")]
        for uid, name in users:
            q(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,status,del_flag,create_time) "
              f"VALUES({uid},103,'{name}','{name}','00','{name}@e2e.local','13800000000','0','{pwd}','0','0',NOW())", db)
        # 业务角色：M3 菜单除新增/修改(3131/3132)外的权限 → 可读 + confirm/receive/close
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(100,'M3采购业务','m3_buyer',5,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu "
          "WHERE menu_id BETWEEN 1 AND 100 OR menu_id IN (3100,3110,3120,3130,3133,3134,3135,3140)", db)
        for uid, name in [(100, "opAE"), (101, "opAESA")]:
            q(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({uid},100)", db)
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(101,'M3只读','m3_viewer',6,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 101,menu_id FROM sys_menu "
          "WHERE menu_id IN (3100,3110,3120,3130,3140)", db)
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

        # ---- 启动应用（外部 logback 覆盖日志路径）----
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
                if code in (200, 500):
                    up = True
                    break
            except Exception:
                pass
            if app.poll() is not None:
                break
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
        tok_ae, _ = login("opAE")
        tok_aesa, _ = login("opAESA")
        tok_viewer, _ = login("viewer")
        check("三个测试用户登录", all([tok_ae, tok_aesa, tok_viewer]))

        def api(token, method, path, body=None):
            return http(method, base + path, token=token, body=body)

        def bcode(resp):
            return resp.get("code") if isinstance(resp, dict) else None
        def data_of(resp):
            d = resp.get("data") if isinstance(resp, dict) else None
            return d if isinstance(d, dict) else {}
        def rows_of(resp):
            rows = data_of(resp).get("rows", [])
            return rows if isinstance(rows, list) else []
        def total_of(resp):
            try:
                return int(data_of(resp).get("total", 0))
            except (TypeError, ValueError):
                return 0

        # ========== 1. 供应商 ==========
        code, resp = api(tok_admin, "GET", "/noon/purchasing/supplier/list")
        check("超管供应商列表可见(3)", bcode(resp) == 200 and total_of(resp) == 3,
              f"bcode={bcode(resp)} total={total_of(resp)}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/supplier/save",
                         {"supplierNo": "SUP-NEW", "supplierName": "New Supplier", "status": "ACTIVE"})
        check("超管新增供应商", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/supplier/save",
                         {"supplierNo": "SUP-1", "supplierName": "重复", "status": "ACTIVE"})
        check("供应商编号唯一冲突被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/supplier/save",
                         {"supplierNo": "SUP-BADJSON", "supplierName": "bad", "contactJson": "{oops", "status": "ACTIVE"})
        check("供应商非法contactJson被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "POST", "/noon/purchasing/supplier/save",
                         {"supplierNo": "SUP-HACK", "supplierName": "hack", "status": "ACTIVE"})
        check("opAE 建供应商被拒(仅超管)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_viewer, "GET", "/noon/purchasing/supplier/list")
        check("viewer 无站点授权供应商列表零行", bcode(resp) == 200 and total_of(resp) == 0,
              f"bcode={bcode(resp)} total={total_of(resp)}")
        audit_cnt = q("SELECT COUNT(*) FROM noon_m3_audit_log WHERE entity_type='SUPPLIER' AND action='INSERT'", db, raw=True).stdout.strip()
        check("审计记录供应商新增", audit_cnt and int(audit_cnt) >= 1, f"audit={audit_cnt}")

        # ========== 2. 报价历史价 / 有效价 ==========
        code, resp = api(tok_admin, "GET", "/noon/purchasing/quotation/effective?skuId=1&supplierId=1&at=2026-03-01")
        old_price = data_of(resp).get("unitPrice") if isinstance(data_of(resp), dict) else None
        check("有效价-历史日期取旧价10", bcode(resp) == 200 and old_price is not None and float(old_price) == 10.0,
              f"bcode={bcode(resp)} price={old_price}")
        code, resp = api(tok_admin, "GET", "/noon/purchasing/quotation/effective?skuId=1&supplierId=1&at=2026-08-01")
        new_price = data_of(resp).get("unitPrice") if isinstance(data_of(resp), dict) else None
        check("有效价-改价后取新价12", bcode(resp) == 200 and new_price is not None and float(new_price) == 12.0,
              f"bcode={bcode(resp)} price={new_price}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/quotation/save",
                         {"skuId": 1, "supplierId": 1, "unitPrice": 11, "currency": "CNY",
                          "moq": 5, "leadTimeDays": 3, "effectiveFrom": "2026-01-01", "effectiveTo": None})
        check("报价唯一键/区间重叠冲突被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/quotation/save",
                         {"skuId": 9999, "supplierId": 1, "unitPrice": 9, "currency": "CNY",
                          "moq": 0, "leadTimeDays": 1, "effectiveFrom": "2026-01-01", "effectiveTo": None})
        check("孤儿报价(不存在SKU)被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/quotation/save",
                         {"skuId": 2, "supplierId": 3, "unitPrice": 9, "currency": "CNY",
                          "moq": 0, "leadTimeDays": 1, "effectiveFrom": "2026-01-01", "effectiveTo": None})
        check("报价引用停用供应商被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")

        # ========== 3. 采购单建单与成本口径 ==========
        order_payload = {
            "orderNo": "PO-AE-1", "supplierId": 1, "siteCode": "AE", "currency": "CNY",
            "source": "MANUAL", "sourceRefNo": None,
            "items": [
                {"skuId": 1, "qtyOrdered": 100, "unitPrice": 12, "currency": "CNY", "fxRate": 1},
                {"skuId": 2, "qtyOrdered": 50, "unitPrice": 5, "currency": "CNY", "fxRate": 1},
            ]
        }
        code, resp = api(tok_admin, "POST", "/noon/purchasing/order/save", order_payload)
        check("超管建采购单DRAFT", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:160]}")
        code, detail = api(tok_admin, "GET", "/noon/purchasing/order/list?orderNo=PO-AE-1")
        po_rows = rows_of(detail)
        po = po_rows[0] if po_rows else {}
        oid = po.get("id")
        check("采购单total_amount=1450(12*100+5*50,只含采购价)",
              bcode(detail) == 200 and po.get("totalAmount") is not None and float(po["totalAmount"]) == 1450.0,
              f"total_amount={po.get('totalAmount')}")
        check("采购单初始状态DRAFT", po.get("status") == "DRAFT", f"status={po.get('status')}")

        code, resp = api(tok_admin, "POST", "/noon/purchasing/order/save", {
            "orderNo": "PO-FX", "supplierId": 1, "siteCode": "AE", "currency": "USD", "source": "MANUAL",
            "items": [{"skuId": 1, "qtyOrdered": 10, "unitPrice": 2, "currency": "USD", "fxRate": 7.2}]
        })
        check("外币采购单建单(fx=7.2)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/order/save", {
            "orderNo": "PO-NOFX", "supplierId": 1, "siteCode": "AE", "currency": "CNY", "source": "MANUAL",
            "items": [{"skuId": 1, "qtyOrdered": 10, "unitPrice": 2, "currency": "CNY"}]
        })
        check("CNY采购行缺fx_rate被拒(要求显式1)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/order/save", {
            "orderNo": "PO-MIX", "supplierId": 1, "siteCode": "AE", "currency": "CNY", "source": "MANUAL",
            "items": [{"skuId": 1, "qtyOrdered": 10, "unitPrice": 2, "currency": "USD", "fxRate": 7.2}]
        })
        check("行币种与订单币种不一致被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_admin, "POST", "/noon/purchasing/order/save", {
            "orderNo": "PO-ORPHAN", "supplierId": 9999, "siteCode": "AE", "currency": "CNY", "source": "MANUAL",
            "items": [{"skuId": 1, "qtyOrdered": 10, "unitPrice": 2, "currency": "CNY", "fxRate": 1}]
        })
        check("孤儿采购单(不存在供应商)被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "POST", "/noon/purchasing/order/save", order_payload)
        check("opAE 建采购单被拒(仅超管)", bcode(resp) == 403, f"bcode={bcode(resp)}")

        # ========== 4. 站点授权隔离 ==========
        q("INSERT INTO pur_order(order_no,supplier_id,site_code,currency,total_amount,status,source,operator_id) "
          "VALUES('PO-SA-1',1,'SA','CNY',100,'CONFIRMED','MANUAL',1)", db)
        q("INSERT INTO pur_order_item(pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
          "VALUES((SELECT id FROM pur_order WHERE order_no='PO-SA-1'),1,10,0,10,'CNY',1)", db)
        code, resp = api(tok_admin, "GET", "/noon/purchasing/order/list")
        # 合法建单 2 张（PO-AE-1/PO-FX，其余 3 张被拒）+ SA 种子 1 张 = 3
        check("超管采购单列表见全部(3)", bcode(resp) == 200 and total_of(resp) == 3,
              f"total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/purchasing/order/list")
        ae_nos = [r.get("orderNo") for r in rows_of(resp)]
        check("opAE 采购单仅见AE站点(不含SA)",
              bcode(resp) == 200 and "PO-SA-1" not in ae_nos and len(ae_nos) == 2
              and set(ae_nos) == {"PO-AE-1", "PO-FX"},
              f"total={total_of(resp)} nos={ae_nos}")
        code, resp = api(tok_viewer, "GET", "/noon/purchasing/order/list")
        check("viewer 无站点采购单列表零行", bcode(resp) == 200 and total_of(resp) == 0,
              f"bcode={bcode(resp)} total={total_of(resp)}")
        sa_id = q("SELECT id FROM pur_order WHERE order_no='PO-SA-1'", db, raw=True).stdout.strip()
        code, resp = api(tok_ae, "GET", f"/noon/purchasing/order/{sa_id}")
        check("opAE 读SA采购单详情被拒(跨站403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "POST", f"/noon/purchasing/order/{sa_id}/confirm")
        check("opAE 确认SA采购单被拒(跨站403)", bcode(resp) == 403, f"bcode={bcode(resp)}")

        # ========== 5. 状态机 ==========
        code, resp = api(tok_ae, "POST", f"/noon/purchasing/order/{oid}/confirm")
        check("opAE 确认AE采购单 DRAFT→CONFIRMED", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_ae, "POST", f"/noon/purchasing/order/{oid}/confirm")
        check("重复确认(CONFIRMED再confirm)被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")
        code, resp = api(tok_ae, "POST", f"/noon/purchasing/order/{oid}/close")
        check("未收货直接关单被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)}")

        # ========== 6. 部分收货数量守恒 + 成本口径 ==========
        items = po.get("items", [])
        item1 = next((it for it in items if it.get("skuId") == 1), None)
        item2 = next((it for it in items if it.get("skuId") == 2), None)
        item1_id = item1.get("id") if item1 else None
        item2_id = item2.get("id") if item2 else None
        check("采购单详情含2行明细", bool(item1_id and item2_id), f"items={[it.get('id') for it in items]}")
        # requestId 由客户端生成（契约 v2 §9.1）：一次提交一个键，重试原样复用
        rid_first = "E2E-RCV-" + uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                         {"orderId": oid, "lines": [{"itemId": item1_id, "qty": 60}], "requestId": rid_first})
        check("部分收货 SKU1 收60 成功", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:160]}")
        check("首次收货 replayed=false", data_of(resp).get("replayed") is False,
              f"replayed={data_of(resp).get('replayed')}")

        # 幂等重放：同一 requestId 再提交，返回首次结果(200/replayed=true)，不得重复入账
        recv_before = q(f"SELECT qty_received FROM pur_order_item WHERE id={item1_id}", db, raw=True).stdout.strip()
        code, replay = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                           {"orderId": oid, "lines": [{"itemId": item1_id, "qty": 60}], "requestId": rid_first})
        check("同requestId重放返回200且replayed=true",
              bcode(replay) == 200 and data_of(replay).get("replayed") is True,
              f"bcode={bcode(replay)} replayed={data_of(replay).get('replayed')}")
        recv_after = q(f"SELECT qty_received FROM pur_order_item WHERE id={item1_id}", db, raw=True).stdout.strip()
        check("幂等重放不重复入账(qty_received保持60)", recv_before == "60" and recv_after == "60",
              f"before={recv_before} after={recv_after}")

        # ---- v2.1（Codex 复核）：重放授权、内容绑定、结果查询 ----
        # 授权每次读 DB 无缓存：撤销 opAE 的 AE 授权后，持合法 rid+orderId 重放/查询都应 403
        q("DELETE FROM sys_user_site WHERE user_id=100 AND site_code='AE'", db)
        code, resp = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                         {"orderId": oid, "lines": [{"itemId": item1_id, "qty": 60}], "requestId": rid_first})
        check("站点授权撤销后同键重放被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_ae, "GET", f"/noon/purchasing/receipt/result?requestId={rid_first}")
        check("站点授权撤销后结果查询被拒(403)", bcode(resp) == 403, f"bcode={bcode(resp)}")
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)
        # 内容绑定：同 requestId 改数量 → 400 内容不一致，不得返回成功
        code, resp = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                         {"orderId": oid, "lines": [{"itemId": item1_id, "qty": 50}], "requestId": rid_first})
        check("同requestId改数量被拒(400内容不一致)", bcode(resp) == 400,
              f"bcode={bcode(resp)} {str(resp)[:160]}")
        # 结果查询：DONE 命中 / 不存在返回 null
        code, resp = api(tok_ae, "GET", f"/noon/purchasing/receipt/result?requestId={rid_first}")
        check("result查询已入账请求返回replayed=true",
              bcode(resp) == 200 and data_of(resp).get("replayed") is True,
              f"bcode={bcode(resp)} {str(resp)[:160]}")
        code, resp = api(tok_ae, "GET", "/noon/purchasing/receipt/result?requestId=NO-SUCH-REQ-ID")
        check("result查询不存在requestId返回null(200)",
              bcode(resp) == 200 and resp.get("data") is None, f"bcode={bcode(resp)} {str(resp)[:120]}")
        recv_v21 = q(f"SELECT qty_received FROM pur_order_item WHERE id={item1_id}", db, raw=True).stdout.strip()
        check("v2.1越权/内容冲突断言未改变数量", recv_v21 == "60", f"qty={recv_v21}")

        rid_over = "E2E-RCV-" + uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                         {"orderId": oid, "lines": [{"itemId": item1_id, "qty": 50}], "requestId": rid_over})
        check("超收(累计110>100)被拒(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:160]}")
        rid_fill = "E2E-RCV-" + uuid.uuid4().hex
        code, resp = api(tok_ae, "POST", "/noon/purchasing/receipt/receive",
                         {"orderId": oid, "lines": [
                             {"itemId": item1_id, "qty": 40}, {"itemId": item2_id, "qty": 50}],
                          "requestId": rid_fill})
        check("补齐收货成功并推进RECEIVED", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:160]}")
        code, detail = api(tok_admin, "GET", f"/noon/purchasing/order/{oid}")
        final = data_of(detail)
        final_status = final.get("status") if isinstance(final, dict) else None
        check("全收齐后状态RECEIVED", final_status == "RECEIVED", f"status={final_status}")
        conserved = q(f"SELECT COUNT(*) FROM pur_order_item WHERE pur_order_id={oid} AND qty_received=qty_ordered", db, raw=True).stdout.strip()
        check("DB数量守恒:全部行 qty_received=qty_ordered", conserved == "2", f"matched={conserved}")
        costs = q(f"SELECT DISTINCT unit_cost_cny FROM noon_m3_receipt WHERE order_id={oid} ORDER BY unit_cost_cny", db, raw=True).stdout.strip().split()
        check("收货成本口径:12.0000与5.0000(只含采购价)",
              "12.0000" in costs and "5.0000" in costs, f"costs={costs}")
        code, resp = api(tok_ae, "GET", f"/noon/purchasing/receipt/list?orderId={oid}")
        check("opAE 收货台账可见本单明细(≥3)", bcode(resp) == 200 and total_of(resp) >= 3,
              f"bcode={bcode(resp)} total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", f"/noon/purchasing/receipt/list?orderId={sa_id}")
        check("opAE 收货台账不含SA单明细", bcode(resp) == 200 and total_of(resp) == 0,
              f"bcode={bcode(resp)} total={total_of(resp)}")
        code, resp = api(tok_ae, "POST", f"/noon/purchasing/order/{oid}/close")
        check("全收齐后关单 RECEIVED→CLOSED", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:120]}")
        audit_recv = q("SELECT COUNT(*) FROM noon_m3_audit_log WHERE entity_type='RECEIPT' AND action='RECEIVE'", db, raw=True).stdout.strip()
        check("审计记录收货RECEIVE", audit_recv and int(audit_recv) >= 1, f"audit={audit_recv}")

        # ========== 7. 菜单路由 ==========
        code, resp = api(tok_ae, "GET", "/getRouters")
        routers = resp.get("data", []) if isinstance(resp, dict) else []
        has_menu = any("noon" in json.dumps(r, ensure_ascii=False) for r in routers)
        check("opAE 路由含 M3 采购菜单", bcode(resp) == 200 and has_menu, f"has={has_menu}")

        # ========== 8. 真实浏览器关键流程 ==========
        if not args.no_browser:
            ui_dir = ROOT / "yx-erp/yxerp-ui"
            bdir = REPORT_DIR / "browser"
            bdir.mkdir(parents=True, exist_ok=True)
            dist = work / "fe-dist"
            # 构建到隔离目录（仓库 dist 批量删除会触发沙箱保护），复用已验证产物也行
            fe_build = run([ui_dir / "node_modules/.bin/vite", "build", "--outDir", str(dist),
                            "--emptyOutDir", "false"], cwd=ui_dir)
            check("前端构建（浏览器验证用）",
                  fe_build.returncode == 0 and (dist / "index.html").exists(),
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
                        if c == 200:
                            fe_ready = True
                            break
                    except Exception:
                        pass
                    if proxy.poll() is not None:
                        break
                    time.sleep(1)
                check("静态前端代理就绪", fe_ready, "" if fe_ready else "见 proxy.log")
                if fe_ready:
                    c_cap, cap = http("GET", front + "/captchaImage", timeout=10)
                    check("静态代理指向隔离后端且验证码已关闭",
                          c_cap == 200 and isinstance(cap, dict) and cap.get("captchaEnabled") is False,
                          f"http={c_cap} body={str(cap)[:180]}")
                    ctx = {"frontendUrl": front, "backendUrl": base, "outDir": str(bdir)}
                    Path("/tmp/noon-m3-browser-ctx.json").write_text(
                        json.dumps(ctx, ensure_ascii=False), encoding="utf-8")
                    check("浏览器 ctx 前置数据(SA单/供应商/SKU)",
                          q("SELECT COUNT(*) FROM pur_order WHERE order_no='PO-SA-1'", db, raw=True).stdout.strip() == "1")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m3-browser-check.js", encoding="utf-8"),
                                        capture_output=True, text=True, timeout=1200)
                    (bdir / "ego-stdout.txt").write_text(br.stdout, encoding="utf-8")
                    (bdir / "ego-stderr.txt").write_text(br.stderr, encoding="utf-8")
                    # ego-browser 把脚本 console.log 输出到 stderr，合并两路解析
                    for line in (br.stderr + "\n" + br.stdout).splitlines():
                        if line.startswith(("PASS |", "FAIL |")):
                            checks.append(line)
                            print(line, flush=True)
                            if line.startswith("PASS |"):
                                passed += 1
                            else:
                                failed += 1
                    check("浏览器验证脚本执行完成", br.returncode == 0,
                          (br.stderr or br.stdout)[-500:])

    finally:
        for p in reversed(procs):
            try:
                p.terminate()
                p.wait(timeout=8)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        report = REPORT_DIR / "e2e-output.txt"
        header = ["# M3 供应商与采购隔离端到端验证报告",
                  f"# 结果: PASS={passed} FAIL={failed}",
                  "# 环境: 一次性 MySQL 8.4 + Redis 7.2 + 若依完整应用（命令行覆盖数据源，未连生产）",
                  "# 测试身份: admin(超管) / opAE(AE) / opAESA(AE+SA) / viewer(无站点)", "",
                  "# 重点: 状态机 / 数量守恒 / 历史价与成本口径 / 站点授权隔离", ""]
        report.write_text("\n".join(header + checks) + "\n", encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)
        print(f"\nPASS={passed} FAIL={failed}  报告: {report}", flush=True)

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
