#!/usr/bin/env python3
"""M2 商品与基础资料隔离端到端验证：真实 MySQL + Redis + 完整若依应用。

做三件事（全部在一次性隔离环境，绝不碰生产库/凭据）：
  1) 用真实若依代码生成器从 md_* 表导出前端 Vue/api 骨架到 docs/verification/m2-masterdata/generated/
  2) HTTP 真登录 + 真鉴权，验证 M2 接口在超管/普通用户/无站点授权下的正反行为
  3) 校验菜单 SQL 与 Controller 权限、审计落库

用法：
  python3 scripts/verify-m2-e2e.py \
    --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
    --redis-bin /tmp/redis-7.2.5/src/redis-server \
    --jar /Users/dream/Work/noonERP/yx-erp/yxerp-admin/target/yxerp-admin.jar \
    --java /usr/bin/java

行为：临时 mysqld + redis-server（随机端口）→ 灌若依基础表 + M2 迁移 + 菜单 + 测试用户/角色/站点授权
      → 启动 Spring Boot 应用（命令行覆盖数据源/Redis 地址，跳过验证码）
      → 登录 → 生成器导出 → HTTP 断言 → 清理所有进程与临时目录，报告落 docs/verification/m2-masterdata/
"""
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RY = ROOT / "yx-erp/sql/ry_20260320.sql"
WP1 = ROOT / "yx-erp/sql/noon_wp1_site_auth.sql"
M2SQL = ROOT / "yx-erp/sql/noon_m2_masterdata.sql"
M2MENU = ROOT / "yx-erp/sql/noon_m2_menu.sql"
REPORT_DIR = ROOT / "docs/verification/m2-masterdata"
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
        data = json.dumps(body).encode("utf-8")
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

    work = Path(tempfile.mkdtemp(prefix="noon-m2-e2e-"))
    data = work / "mysql-data"
    data.mkdir()
    myport, redport, appport = free_port(), free_port(), free_port()
    procs = []
    checks = []
    vite_cfg = None
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
            # raw=True 仅去掉表头（-N），仍必须带 -e 把 sql 作为语句执行
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

        db = "noon_m2_e2e"
        check("建库", q(f"CREATE DATABASE {db} CHARACTER SET utf8mb4").returncode == 0)
        for label, f in [("若依基础表", RY), ("站点授权表", WP1), ("M2 主数据迁移", M2SQL), ("M2 菜单权限", M2MENU)]:
            r = feed(f, db)
            check(f"灌入{label}", r.returncode == 0, r.stderr[-400:] if r.returncode else "")

        # 关闭验证码（隔离环境），便于自动化登录
        q("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'", db)

        # 造测试数据：partner + store(AE 单站 / AE+SA 共享 / 无站点) + sku + 仓库 + 映射
        seed = [
            "INSERT INTO md_partner_account(id,project_code,account_name,status) VALUES(1,'PRJ_E2E','E2E Partner','ACTIVE')",
            "INSERT INTO md_store(id,store_no,partner_id,name_en,status) VALUES(1,'STORE_AE',1,'AE Store','ACTIVE')",
            "INSERT INTO md_store(id,store_no,partner_id,name_en,status) VALUES(2,'STORE_SHARED',1,'Shared Store','ACTIVE')",
            "INSERT INTO md_store(id,store_no,partner_id,name_en,status) VALUES(3,'STORE_NONE',1,'No Site Store','ACTIVE')",
            "INSERT INTO md_site(id,site_code,store_id,country,currency,status) VALUES(1,'AE',1,'UAE','AED','ACTIVE')",
            "INSERT INTO md_site(id,site_code,store_id,country,currency,status) VALUES(2,'AE',2,'UAE','AED','ACTIVE')",
            "INSERT INTO md_site(id,site_code,store_id,country,currency,status) VALUES(3,'SA',2,'KSA','SAR','ACTIVE')",
            "INSERT INTO md_sku(id,sku_code,name_en,status) VALUES(1,'SKU-E2E-1','Test SKU','ACTIVE')",
        ]
        for sql in seed:
            r = q(sql, db)
            check("种子数据: " + sql[:40], r.returncode == 0, r.stderr[-200:] if r.returncode else "")
        # 孤儿引用种子：故意插入不存在的 partner_id，验证引用完整性为负例准备（不入库用）
        q("INSERT INTO md_warehouse(id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,status) "
          "VALUES(1,'WH-AE','AE','AE Warehouse','LOCAL','FBN','ACTIVE')", db)

        # 测试用户：opAE(仅AE) / opAESA(AE+SA) / viewer(无站点)，均授 M2 菜单权限
        pwd = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"  # admin123
        users = [(100, "opAE"), (101, "opAESA"), (102, "viewer")]
        for uid, name in users:
            q(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,status,del_flag,create_time) "
              f"VALUES({uid},103,'{name}','{name}','00','{name}@e2e.local','13800000000','0','{pwd}','0','0',NOW())", db)
        # 角色 + 角色菜单（复制普通角色2的菜单 + M2 五个菜单）
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(100,'M2测试角色','m2_test',5,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 100,menu_id FROM sys_menu WHERE menu_id BETWEEN 1 AND 100 "
          "OR menu_id BETWEEN 3000 AND 3029", db)
        q("DELETE FROM sys_role_menu WHERE role_id=100 AND menu_id IN (SELECT menu_id FROM (SELECT menu_id FROM sys_role_menu WHERE role_id=100) t WHERE menu_id NOT IN (SELECT menu_id FROM sys_menu))", db)
        for uid, name in users:
            if uid != 102:
                q(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({uid},100)", db)
        # viewer 只读角色：仅五个列表菜单，无新增/修改/启停按钮权限
        q("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time) "
          "VALUES(101,'M2只读角色','m2_viewer',6,'0','0',NOW())", db)
        q("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 101,menu_id FROM sys_menu "
          "WHERE menu_id IN (3000,3010,3011,3012,3013,3014)", db)
        q("INSERT INTO sys_user_role(user_id,role_id) VALUES(102,101)", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(100,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'AE',1,NOW())", db)
        q("INSERT INTO sys_user_site(user_id,site_code,create_by,create_time) VALUES(101,'SA',1,NOW())", db)
        # viewer 无站点授权行
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
        # logback.xml 写死 /home/ruoyi/logs（沙箱不可建）；用外部 logback 配置覆盖日志路径
        logs_dir = work / "logs"
        logs_dir.mkdir(exist_ok=True)
        # Spring Boot 4 的 SpringBootJoranConfigurator 强制加载 classpath 内 logback.xml，
        # -D / --logging.config 均被其绕过。故复制 jar 并把内部 logback.xml 的日志路径改为临时目录。
        import zipfile as _zf
        test_jar = work / "e2e-app.jar"
        with _zf.ZipFile(args.jar, "r") as zin, _zf.ZipFile(test_jar, "w", _zf.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                blob = zin.read(item.filename)
                if item.filename == "BOOT-INF/classes/logback.xml":
                    txt = blob.decode("utf-8").replace("/home/ruoyi/logs", str(work / "logs"))
                    blob = txt.encode("utf-8")
                zout.writestr(item, blob)
        app = subprocess.Popen(
            [args.java, "-jar", str(test_jar),
             f"--server.port={appport}",
             f"--spring.datasource.druid.master.url={jdbc}",
             "--spring.datasource.druid.master.username=root",
             "--spring.datasource.druid.master.password=",
             f"--spring.data.redis.host=127.0.0.1", f"--spring.data.redis.port={redport}",
             "--ruoyi.addressEnabled=false", "--logging.level.com.yx=info"],
            stdout=open(work / "app.log", "w"), stderr=subprocess.STDOUT, cwd=work)
        procs.append(app)

        base = f"http://127.0.0.1:{appport}"
        up = False
        for _ in range(90):
            try:
                code, _ = http("GET", base + "/captchaImage", timeout=3)
                if code in (200, 500):  # 接口可达即认为应用已起
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

        # ---- 登录 ----
        def login(name, pwd_plain="admin123"):
            code, resp = http("POST", base + "/login", body={"username": name, "password": pwd_plain})
            return (resp.get("token") if isinstance(resp, dict) else None), resp

        tok_admin, r = login("admin")
        check("超管登录", bool(tok_admin), str(r)[:150] if not tok_admin else "")
        tok_ae, _ = login("opAE")
        tok_aesa, _ = login("opAESA")
        tok_viewer, _ = login("viewer")
        check("三个测试用户登录", all([tok_ae, tok_aesa, tok_viewer]))

        # ---- HTTP 正反验证 ----
        def api(token, method, path, body=None):
            return http(method, base + path, token=token, body=body)

        # 若依 AjaxResult：HTTP 恒 200，业务码在 JSON 的 code 字段；v3 列表在 data={rows,total}
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

        # 店铺列表：超管见3个，opAE 见 AE 的2个（STORE_AE+STORE_SHARED），viewer 见0个
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list")
        admin_rows = rows_of(resp)
        check("超管店铺列表可见全部授权站点店铺", bcode(resp) == 200 and len(admin_rows) == 3 and total_of(resp) == 3, f"bcode={bcode(resp)} rows={len(admin_rows)} total={total_of(resp)}")
        code, resp = api(tok_ae, "GET", "/noon/masterdata/store/list")
        ae_rows = rows_of(resp)
        check("opAE 店铺列表仅见 AE 站点店铺(2)", bcode(resp) == 200 and len(ae_rows) == 2 and total_of(resp) == 2, f"bcode={bcode(resp)} rows={[r.get('storeNo') for r in ae_rows]} total={total_of(resp)}")
        code, resp = api(tok_viewer, "GET", "/noon/masterdata/store/list")
        viewer_rows = rows_of(resp)
        check("viewer 无站点授权店铺列表零行", bcode(resp) == 200 and len(viewer_rows) == 0 and total_of(resp) == 0, f"bcode={bcode(resp)} rows={len(viewer_rows)} total={total_of(resp)}")

        # 店铺详情/写入：opAE 读共享店铺可(有AE)，但写店铺应被拒(仅超管)
        code, resp = api(tok_ae, "GET", "/noon/masterdata/store/2")
        check("opAE 可读 AE+SA 共享店铺(有AE授权)", bcode(resp) == 200, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_ae, "POST", "/noon/masterdata/store/save",
                         {"storeNo": "HACK", "partnerId": 1, "status": "ACTIVE"})
        check("opAE 新建店铺被拒(仅超管)", bcode(resp) == 403, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_viewer, "GET", "/noon/masterdata/store/1")
        check("viewer 读店铺被拒(无站点授权)", bcode(resp) == 403, f"bcode={bcode(resp)} {str(resp)[:120]}")

        # SKU：viewer 无站点授权应 403（登录≠业务授权）
        code, resp = api(tok_viewer, "GET", "/noon/masterdata/sku/list")
        check("viewer SKU 列表被拒(无站点授权)", bcode(resp) == 403, f"bcode={bcode(resp)} {str(resp)[:120]}")
        code, resp = api(tok_admin, "GET", "/noon/masterdata/sku/list")
        check("超管 SKU 列表可见", bcode(resp) == 200, f"bcode={bcode(resp)}")

        # 引用完整性：新建店铺用不存在的 partner_id 应 400
        code, resp = api(tok_admin, "POST", "/noon/masterdata/store/save",
                         {"storeNo": "ORPHAN", "partnerId": 9999, "status": "ACTIVE"})
        check("孤儿店铺(不存在partner)被拒", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:120]}")

        # 审计落库：超管新建一家合法店铺后，审计表应有 INSERT 记录
        code, resp = api(tok_admin, "POST", "/noon/masterdata/store/save",
                         {"storeNo": "STORE_AUDIT", "partnerId": 1, "nameEn": "Audit Store", "status": "ACTIVE"})
        audit_cnt = q("SELECT COUNT(*) FROM noon_m2_audit_log WHERE entity_type='STORE' AND action='INSERT'", db, raw=True).stdout.strip()
        check("审计表记录店铺新增", bcode(resp) == 200 and audit_cnt and int(audit_cnt) >= 1, f"bcode={bcode(resp)} audit={audit_cnt}")

        # ---- 分页/搜索（Codex 2026-11-22 复审必须修项）----
        # 追加 12 家无站点店铺，构造跨页数据；普通用户授权视图不受影响
        for i in range(1, 13):
            r = q(f"INSERT INTO md_store(store_no,partner_id,name_en,status) "
                  f"VALUES('STORE_PAGE_{i:02d}',1,'Page Store {i:02d}','ACTIVE')", db)
            if r.returncode:
                check(f"分页种子数据 {i:02d}", False, r.stderr[-150:])
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list?storeNo=PAGE_0&limit=4&offset=0")
        p1, p1_total = rows_of(resp), total_of(resp)
        code, r2 = api(tok_admin, "GET", "/noon/masterdata/store/list?storeNo=PAGE_0&limit=4&offset=4")
        p2, p2_total = rows_of(r2), total_of(r2)
        code, r3 = api(tok_admin, "GET", "/noon/masterdata/store/list?storeNo=PAGE_0&limit=4&offset=8")
        p3, p3_total = rows_of(r3), total_of(r3)
        ids = [r.get("id") for r in p1 + p2 + p3]
        check("店铺搜索命中数与真实总数一致", bcode(resp) == 200 and p1_total == 9 and len(p1) == 4,
              f"total={p1_total} first_page={len(p1)}")
        check("店铺分页三页无重复无遗漏且 total 恒定",
              len(ids) == 9 and len(set(ids)) == 9 and p2_total == 9 and p3_total == 9,
              f"ids={len(ids)} unique={len(set(ids))} totals={p1_total}/{p2_total}/{p3_total}")
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list?nameEn=Shared")
        check("店铺按英文名搜索命中共享店铺",
              total_of(resp) == 1 and bool(rows_of(resp)) and rows_of(resp)[0].get("storeNo") == "STORE_SHARED",
              f"total={total_of(resp)} rows={[r.get('storeNo') for r in rows_of(resp)]}")
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list?status=INACTIVE")
        check("店铺按停用状态搜索初始未命中", total_of(resp) == 0, f"total={total_of(resp)}")
        q("UPDATE md_store SET status='INACTIVE' WHERE store_no='STORE_PAGE_12'", db)
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list?status=INACTIVE")
        check("店铺按停用状态搜索命中真实行",
              total_of(resp) == 1 and bool(rows_of(resp)) and rows_of(resp)[0].get("storeNo") == "STORE_PAGE_12",
              f"total={total_of(resp)}")
        code, resp = api(tok_admin, "GET", "/noon/masterdata/store/list?status=BOGUS")
        check("非法状态筛选项被拒绝(400)", bcode(resp) == 400, f"bcode={bcode(resp)} {str(resp)[:100]}")
        code, resp = api(tok_ae, "GET", "/noon/masterdata/store/list")
        check("新增跨站店铺后 opAE 授权总数不变", total_of(resp) == 2, f"total={total_of(resp)}")
        code, resp = api(tok_viewer, "GET", "/noon/masterdata/store/list")
        check("新增跨站店铺后 viewer 授权总数仍为零", total_of(resp) == 0, f"total={total_of(resp)}")

        q("INSERT INTO md_partner_sku(partner_sku,sku_id,site_code,business_model,status) "
          "VALUES('PS-E2E-1',1,'AE','noon','ACTIVE')", db)
        # 跨页种子在下方追加，此处仅验证原始种子命中（用唯一值，避免与 SKU-PAGE/PS-PAGE/WH-PAGE 冲突）
        search_cases = [
            ("站点按站点编码搜索", "/noon/masterdata/site/list?siteCode=SA", 1),
            ("站点按店铺ID搜索", "/noon/masterdata/site/list?storeId=2", 2),
            ("仓库按编码搜索", "/noon/masterdata/warehouse/list?warehouseCode=WH-AE", 1),
            ("仓库按站点搜索未命中", "/noon/masterdata/warehouse/list?siteCode=SA", 0),
            ("SKU按编码搜索", "/noon/masterdata/sku/list?skuCode=SKU-E2E-1", 1),
            ("SKU按品牌搜索未命中", "/noon/masterdata/sku/list?brand=NOPE", 0),
            ("PartnerSKU按编码搜索", "/noon/masterdata/partner-sku/list?partnerSku=PS-E2E-1", 1),
            ("PartnerSKU按站点搜索未命中", "/noon/masterdata/partner-sku/list?siteCode=SA", 0),
        ]
        for label, path, expected in search_cases:
            code, resp = api(tok_admin, "GET", path)
            check(label, bcode(resp) == 200 and total_of(resp) == expected,
                  f"bcode={bcode(resp)} total={total_of(resp)} expected={expected}")
        for label, path in [("店铺", "/noon/masterdata/store/list"), ("站点", "/noon/masterdata/site/list"),
                            ("仓库", "/noon/masterdata/warehouse/list"), ("SKU", "/noon/masterdata/sku/list"),
                            ("PartnerSKU", "/noon/masterdata/partner-sku/list")]:
            _, r_small = api(tok_admin, "GET", path + "?limit=2")
            _, r_large = api(tok_admin, "GET", path + "?limit=50")
            check(f"{label}改变每页条数时 total 恒定",
                  total_of(r_small) == total_of(r_large) and len(rows_of(r_small)) <= 2,
                  f"limit2_total={total_of(r_small)} limit50_total={total_of(r_large)}")

        # 菜单可见性：opAE 的路由应含 noon-masterdata
        code, resp = api(tok_ae, "GET", "/getRouters")
        routers = resp.get("data", []) if isinstance(resp, dict) else []
        has_menu = any("noon" in json.dumps(r, ensure_ascii=False) for r in routers)
        check("opAE 路由含 M2 菜单", bcode(resp) == 200 and has_menu, f"bcode={bcode(resp)} has={has_menu}")

        # ---- 五页跨页种子（Codex 2026-11-22 复核：其余四页数据不足跳过翻页）----
        # 放在所有 HTTP 授权断言之后，避免改变 opAE(2)/viewer(0) 的店铺总数口径。
        # 站点 store_id 只允许指向 ACTIVE 店铺：复用 store 1/2/3；仓库站点必须存在 ACTIVE 站点。
        for i in range(1, 13):
            r = q(f"INSERT INTO md_sku(sku_code,name_en,brand,status) "
                  f"VALUES('SKU-PAGE-{i:02d}','Page SKU {i:02d}','PageBrand','ACTIVE')", db)
            if r.returncode:
                check(f"SKU跨页种子 {i:02d}", False, r.stderr[-150:])
            r = q(f"INSERT INTO md_warehouse(warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,status) "
                  f"VALUES('WH-PAGE-{i:02d}','AE','Page WH {i:02d}','LOCAL','FBN','ACTIVE')", db)
            if r.returncode:
                check(f"仓库跨页种子 {i:02d}", False, r.stderr[-150:])
            r = q(f"INSERT INTO md_partner_sku(partner_sku,sku_id,site_code,business_model,status) "
                  f"VALUES('PS-PAGE-{i:02d}',1,'AE','noon','ACTIVE')", db)
            if r.returncode:
                check(f"映射跨页种子 {i:02d}", False, r.stderr[-150:])
        # site 唯一键 (store_id, site_code)：现有 store1=AE/store2=AE,SA/store3=无；
        # site_code 仅允许 AE/SA，故用 store3 造 SA 1 条 + 复用 STORE_PAGE 店铺造 AE，凑够跨页。
        r = q("INSERT INTO md_site(site_code,store_id,country,currency,status) "
              "VALUES('SA',3,'KSA','SAR','ACTIVE')", db)
        if r.returncode:
            check("站点跨页种子 SA", False, r.stderr[-150:])
        page_store_ids = q("SELECT id FROM md_store WHERE store_no LIKE 'STORE_PAGE_%' ORDER BY id", db, raw=True).stdout.strip().split()
        for n, sid in enumerate(page_store_ids[:12]):
            r = q(f"INSERT INTO md_site(site_code,store_id,country,currency,status) "
                  f"VALUES('AE',{sid},'UAE','AED','ACTIVE')", db)
            if r.returncode:
                check(f"站点跨页种子 AE-{n}", False, r.stderr[-150:])
        for label, path in [("站点", "/noon/masterdata/site/list"), ("仓库", "/noon/masterdata/warehouse/list"),
                            ("SKU", "/noon/masterdata/sku/list"), ("PartnerSKU", "/noon/masterdata/partner-sku/list")]:
            _, rp = api(tok_admin, "GET", path + "?limit=10&offset=0")
            _, rp2 = api(tok_admin, "GET", path + "?limit=10&offset=10")
            check(f"{label}跨页种子已就绪(>10行且第二页非空)",
                  total_of(rp) > 10 and len(rows_of(rp2)) > 0,
                  f"total={total_of(rp)} page2={len(rows_of(rp2))}")

        # ---- 代码生成器：导入 md_* 表并导出前端骨架 ----
        GEN_OUT.mkdir(parents=True, exist_ok=True)
        code, resp = api(tok_admin, "POST", "/tool/gen/importTable?tables=md_store,md_site,md_warehouse,md_sku,md_partner_sku&tplWebType=element-plus")
        check("生成器导入 md_* 表", code == 200 and isinstance(resp, dict) and resp.get("code") == 200, f"code={code} {str(resp)[:150]}")
        code, resp = api(tok_admin, "PUT", "/tool/gen", body={
            "tableName": "md_store", "tableComment": "店铺", "className": "Store", "packageName": "com.yx.noon",
            "moduleName": "masterdata", "businessName": "store", "functionName": "店铺", "tplCategory": "crud",
            "tplWebType": "element-plus", "genType": "0", "functionAuthor": "WorkBuddy"})
        check("生成器配置 md_store", code == 200 and isinstance(resp, dict) and resp.get("code") == 200, f"code={code} {str(resp)[:150]}")
        # 下载 zip（batchGenCode），保存并解压
        req = urllib.request.Request(base + "/tool/gen/batchGenCode?tables=md_store",
                                     headers={"Authorization": "Bearer " + tok_admin})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                zipped = r.read()
            (GEN_OUT / "generated.zip").write_bytes(zipped)
            import zipfile
            with zipfile.ZipFile(GEN_OUT / "generated.zip") as zf:
                names = zf.namelist()
                zf.extractall(GEN_OUT / "raw")
            vue_ok = any(n.endswith("index.vue") for n in names)
            api_ok = any("/api/" in n and n.endswith(".js") for n in names)
            check("生成器导出 Vue 页面骨架", vue_ok, f"files={len(names)}")
            check("生成器导出 api.js 骨架", api_ok, f"files={len(names)}")
            check("生成器产物落盘", (GEN_OUT / "raw").exists(), str(GEN_OUT / "raw"))
        except Exception as e:
            check("生成器导出", False, str(e)[:200])

        # ---- 真实浏览器：五页增查改、启停、分页搜索、权限拒绝 ----
        if not args.no_browser:
            ui_dir = ROOT / "yx-erp/yxerp-ui"
            bdir = REPORT_DIR / "browser"
            bdir.mkdir(parents=True, exist_ok=True)
            dist = work / "fe-dist"
            fe_build = run([ui_dir / "node_modules/.bin/vite", "build", "--outDir", str(dist),
                            "--emptyOutDir", "false"], cwd=ui_dir)
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
                    # 跨页种子后 opAE 的可见店铺数会变化；把后端真实口径传给浏览器，
                    # 由浏览器验证 UI 行数与后端授权结果一致（真正的授权隔离证据）。
                    _, ae_list = api(tok_ae, "GET", "/noon/masterdata/store/list?limit=200")
                    ae_total = total_of(ae_list)
                    Path("/tmp/noon-m2-browser-ctx.json").write_text(
                        json.dumps({"frontendUrl": front, "outDir": str(bdir),
                                    "adminToken": tok_admin, "viewerToken": tok_viewer,
                                    "opAEStoreTotal": ae_total},
                                   ensure_ascii=False), encoding="utf-8")
                    br = subprocess.run(["ego-browser", "nodejs"],
                                        stdin=open(ROOT / "scripts/m2-browser-check.js", encoding="utf-8"),
                                        capture_output=True, text=True, timeout=1200)
                    (bdir / "ego-stdout.txt").write_text(br.stdout, encoding="utf-8")
                    (bdir / "ego-stderr.txt").write_text(br.stderr, encoding="utf-8")
                    for line in br.stdout.splitlines():
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
        if vite_cfg is not None:
            try:
                vite_cfg.unlink(missing_ok=True)
            except Exception:
                pass
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
        header = ["# M2 商品与基础资料隔离端到端验证报告",
                  f"# 结果: PASS={passed} FAIL={failed}",
                  "# 环境: 一次性 MySQL 8.4 + Redis 7.2 + 若依完整应用（命令行覆盖数据源，未连生产）",
                  "# 测试身份: admin(超管) / opAE(AE) / opAESA(AE+SA) / viewer(无站点)", ""]
        report.write_text("\n".join(header + checks) + "\n", encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)
        print(f"\nPASS={passed} FAIL={failed}  报告: {report}", flush=True)

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
