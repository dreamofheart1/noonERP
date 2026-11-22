# M3 供应商与采购 · 后端内核实现报告（豆包）

日期：2026-11-22 · 执行者：豆包 · 依据：`docs/verification/m3-purchasing/contract.md` v1（含 4.4 只读收货台账补充）· 范围：仅后端内核（Service + records + Rules + Audit + 测试）

## 1. 改动文件清单

**新增 main（`yx-erp/yxerp-noon/src/main/java/com/yx/noon/purchasing/`）**

| 文件 | 职责 |
|---|---|
| `PurchasingRules.java` | 包内 final 校验工具：required/optional/requiredId/limit(200/20)/offset/siteCode(AE/SA)/like/currency/positiveMoney/fxRate/qty/日期/status/orderStatus/source/duplicate/json/money |
| `PurchasingAudit.java` | `noon_m3_audit_log` 审计（同事务、before/after JSON、operator 真实登录人、UTC+8、降级 UNKNOWN） |
| `PurchasingRefs.java` | 引用完整性：supplier / sku 存在且 ACTIVE |
| `SupplierInput/Row/Query.java` | 供应商 records |
| `SupplierService.java` | 供应商 list/get/save/update/setStatus |
| `QuotationInput/Row/Query.java` | 报价 records |
| `QuotationService.java` | 报价 list/get/save/update + effectivePrice + 区间重叠 |
| `OrderInput/ItemInput/Row/ItemRow/Query.java` | 采购单 records |
| `OrderService.java` | 采购单 list/get/save/update/confirm/close（无通用 setStatus） |
| `ReceiptInput/LineInput/Row/Result.java` | 收货 records |
| `ReceiptService.java` | `receive` + 只读台账 `list(siteCode,offset,limit,orderId)` |

**新增 test（`yx-erp/yxerp-noon/src/test/java/com/yx/noon/purchasing/`）**

- `PurchasingServiceTest.java`：H2(MySQL 模式) 单测 6 个用例，真 Spring 事务 + 真 MyBatis 站点 Mapper。
- `MySqlPurchasingIT.java`：隔离 MySQL 真库 IT 3 个用例（迁移幂等/CHECK/JSON、审计与回滚、并发超收）。

**文档**：`docs/verification/m3-purchasing/doubao-report.md`（本文件）。

未修改任何他人 owner 文件：Controller、前端、scripts/、SQL 迁移、contract、协作记录、schema、M2、config/secrets 全部零改动。

## 2. 关键实现说明

### 2.1 权限与站点隔离（契约 §3）

- 统一 `SiteAuthGuard`，不读请求体 userId。
- 供应商/报价：`siteCode` 仅闸门（传值先 `requireSite`），有 ≥1 授权站点可读全量（全局档案，无 site_code 行过滤），空授权列表零行、详情 `AccessDeniedException`；写仅 `requireAdministrator()`。
- 采购单 `list`：普通用户 `site_code IN 授权集合`，超管全站，空授权零行；入参 `siteCode` 与 `OrderQuery.siteCode` 取交集（交集为空即零行，不抛越权）；COUNT 与 SELECT 共用 WHERE，`total` 是筛选后真实总数。
- 采购单 `get`：先读真实 `site_code` 再 `requireSite`，不信任请求体。
- `confirm/close`：站点内普通用户可操作（先按订单真实站点校验）；`save/update` 仅超管（防跨站建单）。
- 收货：先锁订单再 `requireSite(订单真实 site_code)`；台账 `list` 用 `JOIN pur_order` 按订单站点过滤，空授权零行、`orderId` 精确过滤。

### 2.2 状态机（契约 §5）

`DRAFT --confirm--> CONFIRMED --receive(部分)--> PARTIAL_RECEIVED --receive(补齐)--> RECEIVED --close--> CLOSED`

- 不提供通用 setStatus；Controller 无该入口。
- `confirm` 前置：至少 1 行、supplier ACTIVE、每行 sku ACTIVE、qty>0、unitPrice>0、fxRate 合法；非 DRAFT → 400。
- `close` 仅 `RECEIVED` → `CLOSED`；其余状态 400。
- 状态迁移一律 `SELECT ... FOR UPDATE` 锁订单行，避免并发覆盖；`UPDATE ... WHERE status='DRAFT'` 再作守卫。

### 2.3 数量守恒（契约 §4.4，Codex 重点）

事务步骤：锁 `pur_order`（真实站点 + 串行化同单收货）→ 状态 ∈ {CONFIRMED, PARTIAL_RECEIVED} → 逐行 `SELECT ... FOR UPDATE` → `qty_received + qty <= qty_ordered`（超收 400，中文含「行 id=/订单量/已收/可收余量/本次」）→ `UPDATE ... WHERE qty_received+?<=qty_ordered` → 写 `noon_m3_receipt` → 重算状态（全部行收齐 RECEIVED，否则 PARTIAL_RECEIVED）→ action=RECEIVE 审计。

- 同一请求内重复 itemId 直接 400，防止绕过校验。
- 任意时刻 `0 <= qty_received <= qty_ordered`：应用锁 + DB CHECK 双保险（真库 IT 已验）。
- 失败（超收/越权/状态非法）整批回滚，`qty_received`、收货明细、审计均不落库。

### 2.4 成本口径

- 采购单 `total_amount = Σ(qty_ordered × unit_price × fx_rate)`，只含采购价，不含头程/关税/包材；落 `DECIMAL(18,4)`，统一 4 位 HALF_UP。
- 收货 `unit_cost_cny = unit_price × fx_rate`，同口径 4 位。
- `fxRate`：CNY 必须显式 1（null 或其他值 400），外币 > 0；行币种必须等于订单币种。
- 外币示例：10 × 7.2 × 5 = 360.0000；收货单价 72.0000（单测/IT 均有断言）。

### 2.5 历史价

- 报价按 `effective_from` 版本化：`(sku_id,supplier_id,effective_from)` 唯一；不物理删除旧行。
- 区间重叠校验：同组合 `[from,to)` 相交即 400（`to` 空视为无穷远）。
- `effectivePrice(skuId,supplierId,at)` 取 `effective_from <= at AND (effective_to IS NULL OR effective_to > at)` 中 `effective_from` 最大一条，右开区间（`effective_to` 当天不生效）。
- 采购单单价为提交快照：改价后旧单 `unit_price`/`total_amount` 不变（测试已断言）。

### 2.6 审计

- `PurchasingAudit` 与 M2 同构，写 `noon_m3_audit_log`，与业务写入同事务；action 仅 INSERT/UPDATE/STATUS/RECEIVE；entity 仅 SUPPLIER/QUOTATION/PUR_ORDER/RECEIPT。
- RECEIPT 审计 before/after 含订单状态、`receivedTotalQty`、逐行 qtyOrdered/qtyReceived、本次收货行（itemId/qty/unitCostCny），before 为收货前、after 为收货后。
- operator 取 `SecurityUtils.getLoginUser()`；取不到降级 `operator_id=NULL`、`operator_name=UNKNOWN`（仅无安全上下文时）。

## 3. 测试命令与输出原文

### 3.1 全项目离线 Maven（本机）

```bash
cd /Users/dream/Work/noonERP/yx-erp
MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 test
```

关键原文（`/tmp/doubao-m3-full-test.log`）：

```
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.082 s -- in com.yx.noon.purchasing.PurchasingServiceTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.563 s -- in com.yx.noon.reversal.ReversalServiceTest
[INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.739 s -- in com.yx.noon.intake.IntakeServiceTest
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.397 s -- in com.yx.noon.fifo.FifoConsumptionServiceTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.386 s -- in com.yx.noon.masterdata.MasterDataServiceTest
[INFO] Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
[INFO] yxerp-noon ......................................... SUCCESS [  5.511 s]
[INFO] BUILD SUCCESS
```

- framework：29/29（0 failures / 0 errors）
- yxerp-noon：120/120（既有 114 + 新增 6，0 failures / 0 errors）
- BUILD SUCCESS，exit=0。

### 3.2 隔离 MySQL 真库 IT

命令（WorkBuddy 的 `scripts/verify-m3-runtime.py` 自建临时 MySQL，仅 127.0.0.1）：

```bash
cd /Users/dream/Work/noonERP
python3 scripts/verify-m3-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
```

输出原文：

```
MySQL JUnit: tests=3 errors=0 failures=0 skipped=0
maven_exit=0
```

Surefire 原文：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.121 s -- in com.yx.noon.purchasing.MySqlPurchasingIT
```

三个 IT 用例：

1. `migrationIsIdempotentAndRealConstraintsHold`：迁移连跑两次；6 张表存在；直写 `qty_received>qty_ordered` 被 CHECK 拒；receipt `qty=0` 被拒；audit `action=DELETE` 被拒；非法 `contact_json` 被 JSON 拒；真实唯一键冲突经应用转 400；审计 JSON 列落库。
2. `auditPersistsAndBusinessFailureRollsBack`：收货审计含 before/after qty 与收货行、operator=登录人；超收后 `qty_received`、收货明细、审计行数均不变；补齐后 RECEIVED 再落一条审计；报价版本化不物理删除。
3. `concurrentReceiptCannotOverReceive`：两线程同时各收 6/10，只 1 个成功，另一个 400「可收余量」；`qty_received=6`、收货明细仅 1 行；再收 4 转 RECEIVED。

### 3.3 H2 单测覆盖对照（契约 §7）

| 要求 | 覆盖位置 |
|---|---|
| 命中/未命中 | 供应商/报价/采购单/收货四组 list |
| total 为筛选后真实数 | 每组 `truncated.total` 断言 |
| offset 翻页无重复无遗漏 | 供应商 + 采购单 `assertPageWalk` |
| 空授权零行 | 供应商/报价/采购单/收货（用户 3） |
| 引用完整性负例 | 孤儿报价、孤儿采购单行、停用 sku/supplier |
| 唯一键冲突 | supplier_no、order_no（报价版本冲突先行被区间重叠拦截，见未决 1） |
| 状态非法跳转 | confirm 重复 / 非 DRAFT 改单 / CONFIRMED 关单 / CLOSED 收货 |
| 数量守恒超收 400 | 单测 + 真库 CHECK + 并发 IT |
| 成本口径 | CNY fx=1、CNY 缺失/非 1 报错、USD 7.2 换算、收货 unit_cost_cny |
| 报价历史价 | 旧单快照不变、effectivePrice 区间（含右开边界） |
| 管理员与普通用户站点隔离 | 用户 1/2/3/4 四类（含 AE+SA 双站） |

## 4. 未决项（如实标注）

1. **报价唯一键与区间重叠的顺序**：同一 `(sku,supplier,effective_from)` 必然同时与旧版本区间相交，因此新增/修改会先返回「报价区间与已有记录重叠」（仍是 400）。DB 唯一键只在并发插入等重叠校验看不到的场景兜底。若 Codex 要求该场景固定报「已存在报价」，需契约明确优先级。
2. **新增价格版本必须先关闭旧版本**：旧行 `effective_to` 为空（长期有效）时，直接插入新 `effective_from` 会 400。前端改价流程应是「先 update 旧行 `effectiveTo` = 新版本起始日，再 save 新版本」。请 WorkBuddy 按此实现页面，否则 E2E 改价会失败。
3. **采购单 update 会重建明细行**：`id` 会变化（契约允许整体替换）。E2E/浏览器脚本不能跨 update 复用 itemId，收货前需重新 GET 单据。
4. **金额精度**：`total_amount` / `unit_cost_cny` 落库 4 位 HALF_UP（DECIMAL(18,4) 列宽决定）。若报价/单价小数超过 4 位，合计会有 ≤0.00005/行的舍入差；当前契约未要求更高精度。
5. **供应商/报价 get 的空授权语义**：列表空授权零行（契约明示），详情采用 `AccessDeniedException`（与 M2 SKU 口径一致）。如需详情也返回 null，需契约确认。
6. **实库 IT 的授权使用内存桩**：真库 IT 聚焦 DDL/CHECK/审计/并发，不依赖若依 `sys_user` 表结构；真实登录与站点授权留给 WorkBuddy 的 HTTP E2E / 浏览器验证。
7. 本模块不生成 `inv_batch`、不写四态台账、不摊头程费用、无审批流、不做结算校验，与契约 §0 一致。

## 5. 对 WorkBuddy 的协作请求

1. **前端改价流程**：按未决 2 实现「先关旧版本 effectiveTo，再新增版本」，避免 400 重叠。
2. **收货页面/脚本**：收货前重新 GET 采购单拿最新 itemId（update 后 id 会变）；`/receipt/list` 用 `orderId` 精确过滤，站点由后端按订单真实站点过滤。
3. **`verify-m3-runtime.py`**：已兼容现有注入方式（`NOON_MYSQL_TEST_PORT` + `NOON_MYSQL_TEST_PASSWORD`，IT 自建并在结束后 drop 一次性库）；也支持直接注入完整 `NOON_MYSQL_TEST_URL`（此时库由脚本负责隔离）。
4. **菜单权限**：收货写操作 Controller 使用 `noon:purchasing:order:receive`，台账用 `noon:purchasing:receipt:list`；请确认菜单 SQL 已给普通操作员分配 `order:receive`（否则站点内普通用户无法收货，只有超管可收）。
5. **E2E 断言建议**：跨站收货应断言 403 而非零行（`receive` 先 requireSite），而 `/receipt/list?siteCode=SA` 对仅 AE 用户同样是 403（`siteCode` 先 requireSite）；不传 siteCode 时才是「按授权过滤零行」。
6. **交 Codex 复核点**：状态机、数量守恒（含并发）、成本口径（CNY fx=1 与外币换算）、站点授权隔离、审计同事务回滚。

---

# v2 整改段（契约 §9.1 收货幂等 / §9.2 报价并发）· 2026-11-22 · 豆包

基线：Codex「M3 关键流程初审」两个**必须改**项——① 收货响应丢失重试会虚增数量；② 报价「先查后插」并发可产生重叠价。权威口径 `contract.md` §9，v1 其余条款不变。本段只做后端内核整改，入口/前端/E2E/迁移 SQL 仍归 WorkBuddy。

## 6.1 改动文件（全部在我 owner 范围内，零越界）

| 文件 | 改动 |
|---|---|
| `ReceiptInput.java` | 改为 `record ReceiptInput(Long orderId, List<ReceiptLineInput> lines, String requestId)`，注明幂等键规则 |
| `ReceiptResult.java` | 增加第 5 个分量 `boolean replayed`（首次 false，命中幂等 true） |
| `ReceiptService.java` | 新增幂等请求头流程（占位 PENDING / DuplicateKey 判重 / DONE 重放 / 提交前写回 result_json）；requestId 校验（8–64、`[A-Za-z0-9_-]`）；收官 `markDone`；时间序列化用 `SimpleModule` |
| `QuotationService.java` | `save`/`update` 在 `validate()` 前加组合锁（try INSERT + `SELECT ... FOR UPDATE`）；update 改组合时按 `(skuId,supplierId)` 升序锁 old+new |
| `PurchasingServiceTest.java` | H2 补建 2 张同构表；全部 `ReceiptInput` 调用点补 requestId；新增 2 个用例 |
| `MySqlPurchasingIT.java` | 表数断言 6→8、`clean()` 清两张新表；`ReceiptInput` 调用点补 requestId（并发超收用例每线程独立键）；新增 3 个用例 |

未改：`yx-erp/sql/noon_m3_purchasing.sql`（两张新表 WorkBuddy 已加，字段与契约一致，我只读取对齐）、4 个 Controller、前端、`scripts/`、`contract.md`、`agent-collaboration.md`、`development-modules.md`；未 git add/commit/push、未连生产、未部署。

## 6.2 实现要点

### 收货幂等（§9.1）

1. 入参校验 → `INSERT noon_m3_receipt_request(request_id,order_id,operator_id,'PENDING')`（operator 取 `guard.requireCurrentUserId()`，为 NULL 时允许）。
2. 抛 `DuplicateKeyException` → `SELECT ... FOR UPDATE` 读该行（**当前读**，避免 RR 快照看不到并发事务刚提交的 DONE）：订单不一致 → 400「requestId 已用于其他采购单」；`DONE` → `ObjectMapper` 反序列化 `result_json` 为 `ReceiptResult`，置 `replayed=true` 返回，**不写业务/审计**；`PENDING` → 400「同一收货请求正在处理中，请稍后重试」。
3. 新请求继续 v1 流程（`lockOrder` → `requireSite(订单真实 site_code)` → 状态 → 逐行 `FOR UPDATE` → 守恒 → 明细 → 状态 → 审计）。
4. 提交前 `UPDATE ... SET status='DONE', result_json=<首次完整结果 replayed=false>`；任何异常整事务回滚，PENDING 行一并消失，同 requestId 可重试。
5. 顺序符合契约：幂等判重在前，订单真实站点授权在所有业务写入前；requestId 非法/明细非法在写任何行之前 400（测试已断言幂等表行数不变）。

### 报价并发（§9.2）

- `save`：`requireAdministrator` → id 基本校验 → `lockCombination(sku,supplier)`（try INSERT 捕 `DuplicateKeyException` 忽略 → `SELECT sku_id ... FOR UPDATE` 持锁到提交）→ `validate`（含 `requireNoOverlap`）→ INSERT。
- `update`：`lockExisting(id)` 拿 old 组合 → 锁 old+new 两个组合（去重后按 `(skuId,supplierId)` 升序，防交叉死锁）→ `validate` → UPDATE。
- `uk_sku_sup_eff` 与 `requireNoOverlap` 原样保留作第二道防线；锁行随事务回滚（校验失败的组合不留残留，测试已断言）。

## 6.3 测试执行结果（全部真跑，非注释）

### 全项目离线 Maven（framework + yxerp-noon）

```bash
cd /Users/dream/Work/noonERP/yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
```

```
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0        # framework
[INFO] Tests run: 8,  ... -- in com.yx.noon.purchasing.PurchasingServiceTest
[INFO] Tests run: 122, Failures: 0, Errors: 0, Skipped: 0       # yxerp-noon
[INFO] BUILD SUCCESS
```

- framework **29/29 PASS**（0 失败/错误/跳过）
- yxerp-noon **122/122 PASS**（v1 120 + 新增 2 个 H2 用例）
- 全 reactor `-DskipTests compile`（含 `yxerp-admin` 4 个 Controller）**exit=0**，Controller 无需改签名（Jackson 直接绑定 `requestId`）。

### 隔离 MySQL 8.4 真库 IT

```bash
cd /Users/dream/Work/noonERP
python3 scripts/verify-m3-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
```

```
MySQL JUnit: tests=6 errors=0 failures=0 skipped=0
maven_exit=0
证据目录：/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m3-bhm6ku3c
```

**6/6 PASS**。首次运行时 `failedReceiptRollsBackRequestAndSameRequestIdCanRetry` 有 1 条我方断言写错（把「明细行数=1」写成 4），实现无缺陷；修正断言后复跑全绿。

### 逐用例结果

| 用例（H2） | 内容 | 结果 |
|---|---|---|
| `receiptIdempotencyReplayValidationAndCrossOrder` | ① 同 requestId 两次：第二次 `replayed=true`、返回首次行 id/状态/累计数，`qty_received` 与 `noon_m3_receipt` 行数不变、审计数不变、result_json 含 `"replayed":false`；② 不同 requestId 两次真实收货 3+2 累加到 5、明细 +1；③ requestId 非法 8 种（null/空/空白/short/65 位/中文/含空格/中英混）统一 400「收货请求缺少有效的 requestId」且幂等表零新增；④ 复用到另一订单 400「requestId 已用于其他采购单」，另一单据 qty 与状态不变 | **PASS** |
| `quotationCombinationLockCoversSaveAndUpdate` | save 建锁行；update 改组合后 old/new 锁行均存在；旧组合可新增不重叠版本（回读到新 id）；新组合仍被 requireNoOverlap 拦 400「重叠」；锁行已存在的组合再 save（DuplicateKey 忽略路径）成功；非法日期失败后新组合锁行 0（随事务回滚） | **PASS** |
| 原有 6 个用例（供应商/报价历史价/采购单/收货守恒/台账授权/审计回滚） | 全部改带 requestId 后行为不变 | **PASS**（6/6） |

| 用例（隔离 MySQL 8.4） | 内容 | 结果 |
|---|---|---|
| `concurrentSameRequestIdReceiptBooksOnce` | 两线程同 requestId 并发各收 3/10：恰好 1 个 `FRESH`，另一个 `REPLAYED` 或明确 400；`qty_received=3`、`noon_m3_receipt` 仅 1 行、请求行收敛 DONE、状态 PARTIAL_RECEIVED | **PASS** |
| `concurrentOverlappingQuotationOnlyOneSucceeds` | 两线程同 `(sku,supplier)`、不同 `effective_from` 且区间重叠（`[01-01,06-01)` vs `[03-01,∞)`）：恰好 1 成功 1 失败，失败文案含「重叠」，该组合最终仅 1 行报价 | **PASS** |
| `failedReceiptRollsBackRequestAndSameRequestIdCanRetry` | 首次超收（收 5 > 订 4）业务失败：请求行 0、qty 0、明细 0；同 requestId 重试成功（`replayed=false`、qty 4、明细 1、请求行 DONE）；再发一次 `replayed=true` 且不增明细/不增 qty | **PASS** |
| 原 3 个用例（迁移幂等与 CHECK/JSON、审计与回滚、并发超收） | 表数断言更新为 8 张（含 `noon_m3_receipt_request`/`noon_m3_quotation_lock`） | **PASS**（3/3） |

## 6.4 遗留问题与协作请求（只描述，未改对方文件）

1. **E2E / 前端已对接（核对结论，非我改动）**：`scripts/verify-m3-e2e.py` 与 `order/index.vue` 已带 `requestId`（打开弹窗生成、失败保留、成功后换新），并断言 `replayed=true` 重放不增产；与本次后端 400 文案一致，无需再改签名。
2. **`jackson-datatype-jsr310` 不在 yxerp-noon 编译类路径**：pom 不属我 owner，`ReceiptResult` 里的 `LocalDateTime` 改用 `SimpleModule` 显式 ISO 读写（无新依赖）。若后续想统一时间序列化，由 WorkBuddy 或 owner 在 `yxerp-noon/pom.xml` 加 jsr310 后再简化，属可选项。
3. **幂等表无保留策略**：`noon_m3_receipt_request` 一次性追加、不清理，长期会增长（每行仅一个键+一份结果）。当前未定义 TTL/归档，建议 M7 运维或后续契约明确（不阻塞验收）。
4. **重放结果的时点语义**：重放返回首次提交时的快照（状态/累计数/行 id），若之后又有别的收货请求入账，重放仍显示旧值——与契约「原样返回首次完整结果」一致，前端提示「未重复入账」即可，不建议改。
5. **契约未变，无需停下改契约**：实现与 §9.1/§9.2 一致；两张新表 DDL 以 WorkBuddy 的 `noon_m3_purchasing.sql` 为准（H2 同构列名一致，JSON→text、去 CHECK、保留主键/唯一键）。
6. **对 WorkBuddy 的配合点（无需改代码）**：Controller 的 `@RequestBody ReceiptInput` 已能绑定第三个分量；异常映射沿用 `IllegalArgumentException → 400`（幂等三种文案都在此通道）；`POST /receipt/receive` 请求体必须带 `requestId`，其余脚本/页面如需模拟「真实第二次收货」必须换新键，否则会命中重放。

---

# v2.1 整改段（契约 §9.6 授权顺序 / 请求内容绑定 / 结果查询）· 2026-11-22 · 豆包

基线：Codex「M3 v2 复核：重放权限与请求身份」三个**必须核实**的静态问题——① `claimRequest` 重放后立即 return、`guard.requireSite` 在其后，越权可读结果；② 只绑 orderId、不绑明细内容，同键换数量误报成功；③ 页面重开生成新键，响应不明时可能再次入账。权威口径 `contract.md` §9.6；v1/v2 其余条款不变。本轮只改后端内核与测试，入口 `/result`、前端 pending 存储、迁移 SQL 的 `content_fingerprint` 列归 WorkBuddy。

## 7.1 改动文件（全部在我 owner 范围内，零越界）

| 文件 | 改动 |
|---|---|
| `ReceiptService.java` | ① `receive` 顺序改为「入参校验 → operatorId → `lockOrder` + `guard.requireSite(真实站点)` → 内容指纹 → DONE 重放 → 状态校验 → `claimRequest` → 入账」；② 新增 `contentFingerprint(orderId, lines)`（规范化串 + SHA-256 64 位小写十六进制）；③ 幂等行读写全部带 `content_fingerprint`，`requireSameRequest` 先判订单身份再比指纹；④ 新增只读 `public ReceiptResult resultOf(String requestId)`（按幂等行 order_id 取**订单真实 site_code** 再 `requireSite`，之后才返回；replayed 恒 true；只读不写表） |
| `PurchasingServiceTest.java` | H2 建表补 `content_fingerprint char(64) not null`；新增 5 个用例（授权顺序、内容绑定、`resultOf`、失败回滚同键重试、指纹固定值） |
| `MySqlPurchasingIT.java` | 站点授权桩改为可切换的内存授权表（`grant(userId, sites...)`，`@BeforeEach` 重置为 1=AE+SA / 2=AE），供「撤销授权后重放 403」；新增 3 个用例；`confirmedOrder` 增加 site 重载以便造 SA 单；表数/清理不变（仍是原 8 张） |

未改：`yx-erp/sql/noon_m3_purchasing.sql`（WorkBuddy 已加 `content_fingerprint CHAR(64) NOT NULL`，我只读取对齐）、4 个 Controller（`/result` 已存在且签名匹配）、前端、`scripts/`、`contract.md`、`agent-collaboration.md`、`development-modules.md`；未 git add/commit/push、未连生产、未部署。

## 7.2 实现要点

### ① 授权顺序（越权必堵，§9.6 ①）

`receive` 现在的执行次序（同一 `@Transactional`）：

1. `orderId` / `requestId` / 明细校验；
2. `operatorId = guard.requireCurrentUserId()`；
3. **`lockOrder(orderId)` + `guard.requireSite(head.siteCode())`**（订单真实站点，先于任何幂等判定）；
4. 计算内容指纹 → 若该 requestId 已 DONE 且内容一致则重放返回；否则状态校验（CONFIRMED/PARTIAL_RECEIVED）；
5. `claimRequest`（INSERT PENDING 带指纹；唯一键冲突走 `replayOf`：当前读 + 同单 + 同指纹 + DONE 才重放）；
6. 数量守恒 / 写明细 / 重算状态 / 审计 / `markDone`。

因此 viewer（零站点）或 opAE（持 SA 单的合法 requestId+orderId）在第 3 步即 403，**读不到任何重放结果**；授权被撤销后同一键重放同样 403。`resultOf` 复用同一判定：幂等行 → `order_id` → `pur_order.site_code` → `requireSite`，不存在/非法键返回 null，无权 403。

### ② 请求内容绑定（§9.6 ②）

- 规范化串：`orderId + "|" + 按 itemId 数值升序的 "itemId:qty"`，逗号连接，UTF-8，SHA-256 → 64 位小写十六进制（`Character.forDigit` 手工拼 hex，无额外依赖）。
- 契约示例 `12|3:60,7:40` 的固定值 `8d50b685…afdf2ba` 已写入单测，防止规范化串/排序漂移；数组提交顺序颠倒不影响指纹，数量在两行间互换则指纹不同。
- 首次 INSERT PENDING 写指纹；`replayDone`/`replayOf`/`resultOf` 都先比对已存指纹，不一致 400「requestId 对应的收货内容与原请求不一致」，不返回成功、不入账、不写审计。同键绑定另一订单仍报「requestId 已用于其他采购单」（订单身份先于指纹，与 §9.1 一致）。

### ③ 与契约字面顺序的一处偏差（如实写明，未改契约）

§9.6 ① 的字面序列是「状态校验 → `claimRequest`」。若严格把状态校验放在所有重放分支之前，则**首次收货恰好收满、订单转为 RECEIVED 后**，客户端因响应丢失重发同一键会得到 400「不可收货」而不是 `replayed=true`，与 §9.1「DONE 原样重放首次结果」直接冲突，也会推翻既有回归（真库 `failedReceiptRollsBackRequestAndSameRequestIdCanRetry` 断言收满后同键仍 `replayed=true`）。

本轮按 §9.6 ② 明示的优先级「站点授权 > 内容指纹 > 状态/结果」落地：授权在最前；内容指纹次之；**DONE 重放在状态校验之前，状态校验只约束「首次入账」路径**。这样越权、伪造内容都被先挡住，同时保住 §9.1 的响应丢失重放语义。契约与迁移 SQL 均未改动，此处理解请 Codex / WorkBuddy 确认；若确认必须字面顺序，需同步修订 §9.1 的 DONE 重放承诺与上述回归断言。

## 7.3 测试执行结果（全部真跑）

### 全项目离线 Maven

```bash
cd /Users/dream/Work/noonERP/yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
```

```
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0      # framework
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0      # PurchasingServiceTest
[INFO] Tests run: 127, Failures: 0, Errors: 0, Skipped: 0     # yxerp-noon（122 → 127，+5）
[INFO] BUILD SUCCESS
```

全 reactor `-DskipTests compile`（含 `yxerp-admin` 4 个 Controller）**exit=0**。

### 隔离 MySQL 8.4 真库 IT

```bash
cd /Users/dream/Work/noonERP
python3 scripts/verify-m3-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
```

```
MySQL JUnit: tests=9 errors=0 failures=0 skipped=0
maven_exit=0
证据目录（最终代码复跑）：/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m3-gfc62c4z
前一次运行（同一套用例）：/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m3-5lv2u5pk
```

**9/9 PASS**（既有 6 + 新增 3）。H2 surefire 报告在 `yx-erp/yxerp-noon/target/surefire-reports/`。

### 逐用例结果（H2）

| 用例 | 覆盖 | 结果 |
|---|---|---|
| `receiptReplayRequiresOrderSiteAuthorization` | viewer（零站点）持 admin 的合法 requestId+orderId 重放 → 403、`resultOf` → 403；opAE 持 SA 单键重放/查询 → 403；opAE 对自己 AE 单重放 → `replayed=true`；删除 `sys_user_site` 撤销 AE 授权后同一键重放/查询 → 403；全程 qty、明细行数、审计数、幂等行状态不变 | **PASS** |
| `receiptReplayRejectsChangedContentFingerprint` | 60 件收满后同键改 50 → 400「内容不一致」（优先于状态闸门）、qty 仍 60、状态仍 RECEIVED、审计不变；部分收货 10 → 同键 20 → 400 且不累加；双行订单数组顺序颠倒 → 指纹一致命中重放；数量在两行间互换 → 400 | **PASS** |
| `receiptResultOfIsReadOnlyAndSiteAuthorized` | admin 查 DONE → `replayed=true`、orderId/状态/累计数/行 id 与首次一致；qty/明细/审计/幂等状态不变（只读）；不存在键、null、空串、`short` 均返回 null；opAE 查 SA 单键 403、查 AE 单键成功；viewer 查 403 | **PASS** |
| `receiptFailedAttemptRollsBackAndSameRequestIdRetries` | 同键首次超收 5/4 → 400、PENDING 行（含指纹）回滚、qty 0、明细 0；同键重试 4 成功（`replayed=false`、RECEIVED）；同键再发 → `replayed=true`、不增 qty/明细（订单已 RECEIVED 仍重放） | **PASS** |
| `contentFingerprintMatchesContractCanonicalForm` | `12\|3:60,7:40` 的 SHA-256 固定值 `8d50b685d6b4fa09322bfee789a7292bb59db1d79284b86294b1e5d68afdf2ba`，输入顺序无关 | **PASS** |
| 既有 8 个用例（供应商/报价/采购单/收货守恒/台账授权/审计回滚/v2 幂等/v2 组合锁） | 全部保持通过，无行为回退 | **PASS**（8/8） |

### 逐用例结果（隔离 MySQL 8.4）

| 用例 | 覆盖 | 结果 |
|---|---|---|
| `receiptReplayAfterGrantRevokedIsForbidden` | 授权桩切换：opAE 有 AE 授权时重放成功；撤销后重放 403、`resultOf` 403；跨站（opAE 持 SA 单键）重放/查询 403；越权后两张单 qty、明细行数、幂等行状态均不变 | **PASS** |
| `receiptContentFingerprintConflictRejectedOnRealDb` | 60 件收满后同键 50 → 400「内容不一致」，qty 仍 60、明细 1 行；落库指纹等于算法结果、长度 64、`[0-9a-f]{64}`；绕过 Service 直插缺指纹列被 DB `NOT NULL` 拒绝 | **PASS** |
| `resultOfReturnsDoneAndNullOnRealDb` | DONE → `replayed=true`、状态/累计数/行 id 一致；qty、明细、审计不变（只读）；不存在键与非法格式 → null | **PASS** |
| 既有 6 个用例（迁移幂等与 CHECK/JSON、审计与回滚、并发超收、并发同键只入账一次、并发重叠报价、失败回滚同键重试） | 全部保持通过 | **PASS**（6/6） |

## 7.4 遗留问题与协作点（只描述，未改对方文件）

1. **§9.6 字面顺序 vs §9.1 重放语义**：见 7.2 ③。本轮取「授权 > 指纹 > DONE 重放 > 状态」，请 Codex/WorkBuddy 确认口径；若要求严格字面顺序，须同步改 §9.1 与既有回归断言（我不改契约）。
2. **`resultOf` 的 PENDING 分支**：设计上 PENDING 与 `markDone` 同事务，外部读不到已提交的 PENDING；代码仍对 PENDING 返回 null（防御性），对应 §9.6 ③「null → 复用该键重试」。
3. **前端 pending 存储未在我侧**：`m3:receipt:pending:<orderId>` 的写入/清除、重开弹窗先调 `GET /receipt/result`、`DONE → replayed=true` 提示、`null → 复用键`、`403 → 不提交`，均在 WorkBuddy 的 `order/index.vue` + `receipt.js`；本轮只保证后端语义与 403/400 文案。Controller `/result` 已存在并与 `resultOf(String)` 签名一致（`AjaxResult.success(null)` 即 null）。
4. **异常映射依赖**：`AccessDeniedException → 403`、`IllegalArgumentException → 400`（内容不一致、已用于其他采购单、处理中、不可收货）都走 `ReceiptController` 既有 handler，无需新增。
5. **保留策略不变**：`noon_m3_receipt_request` 仍无 TTL/清理（v2 遗留，不阻塞）。
6. **未做的验证**：HTTP E2E 与真实浏览器（重开弹窗走 result 查询命中 DONE 不重复入账、opAE 跨站重放 403 的实际响应码与页面提示）归 WorkBuddy 复跑；本轮未连生产、未部署、未 git add/commit/push、未碰密钥。
7. **迁移脚本对「已跑过 v2 的旧库」不增量（需 WorkBuddy 决定，我不改 SQL）**：`noon_m3_purchasing.sql` 里 `noon_m3_receipt_request` 是 `CREATE TABLE IF NOT EXISTS`，表已存在时新加的 `content_fingerprint` 列**不会被自动补上**，旧库升级后 `receive` 的 INSERT 会因缺列报 SQL 错。新建库（真库 IT 每次新建库、E2E 重建库）不受影响，故本轮验证全绿掩盖了这一点。建议二选一：① 追加幂等 `ALTER TABLE noon_m3_receipt_request ADD COLUMN IF NOT EXISTS content_fingerprint CHAR(64) NULL`，旧行按结果快照回填或留 NULL（后端已把 NULL 视为内容不一致、拒绝放行）；② 明确「幂等表仅限新建库，旧库手工重建」。DDL 字段名/长度需与我这边的 H2 建表保持一致。
