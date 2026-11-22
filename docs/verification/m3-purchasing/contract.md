# M3 供应商与采购 · 接口与规则契约 v1

状态：已定版（2026-11-22）。基线：`docs/development-modules.md` M3、`docs/noon-erp-prd-v1.0.md` §3.3 / FR-03 / FR-04.1、`docs/noon-erp-schema-v0.5.sql` 第 138–163 / 289–325 行；Codex 2026-11-22「M2 阶段结论与后续推进」要求重点审核状态流转、部分收货数量守恒、历史价格与采购成本口径、站点授权隔离。

本模块为供应商与采购**隔离可运行闭环**，不接真实平台、不连生产、不部署、不推送。

## 0. 范围与边界（先读）

- **做**：供应商档案、SKU 供应商报价（历史价）、采购单（DRAFT/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/CLOSED）、部分收货、采购成本口径（只含采购价 + 显式汇率）、站点授权、审计、前后端页面与验证。
- **不做**（留给后续模块，避免重复计成本）：
  - 不生成 `inv_batch`、不写四态台账 `inv_stock_ledger` / `inv_stock_move`（M4 FR-04.1）。M3 收货只更新 `pur_order_item.qty_received` 与采购单状态。
  - 不写头程/关税/包材费用（Q8：成本物理分离，头程归 L2）。
  - 不做审批流（Q11：采购单无审批）。CLOSED 仅表示「全部到货后人工关单」，不涉及结算金额校验（结算在 M6）。
  - 不做真实 Noon 字段回读、不写平台。
- `source` 允许 `MANUAL / REPLENISH_SUGGESTION / AI_SUGGESTION`；转单来源只记录 `source_ref_no` 字符串，不校验上游单据存在（M8/M10 后续接入）。

## 1. 数据表（沿用 schema v0.5，增量迁移幂等）

新增迁移 `yx-erp/sql/noon_m3_purchasing.sql`（WorkBuddy owner），只含：

1. `md_supplier`（schema 138–146 行逐字段，唯一键 `uk_supplier_no`）。
2. `md_sku_supplier`（schema 148–163 行逐字段，唯一键 `uk_sku_sup_eff(sku_id,supplier_id,effective_from)`、CHECK `moq>=0 AND lead_time_days>=0`）。
3. `pur_order`（schema 292–309 行逐字段，含 `site_code`、`status` 默认 DRAFT、唯一键 `uk_pur_no`）。
4. `pur_order_item`（schema 311–325 行逐字段，含 `qty_received` 默认 0、`fx_rate` 默认 NULL、CHECK `qty_ordered>=0 AND qty_received>=0 AND qty_received<=qty_ordered`）。
5. `noon_m3_audit_log`（新建，字段与 `noon_m2_audit_log` 同构；`entity_type` 允许 SUPPLIER/QUOTATION/PUR_ORDER/RECEIPT；`action` 允许 INSERT/UPDATE/STATUS/RECEIVE）；append-only，与业务写入同事务。

不修改主 schema 文件，不改 M2 表。全部 `CREATE TABLE IF NOT EXISTS`。

## 2. 公共规则（新包 `com.yx.noon.purchasing`，参考 `com.yx.noon.masterdata` 风格）

创建 `PurchasingRules`（包内可见 final 类）：
- `required/optional/requiredId/limit/offset/siteCode/like/duplicate` 与 `MasterDataRules` 同语义；limit 上限 200、默认 20。
- `status(String)`：采购单状态枚举 `DRAFT/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/CLOSED`，非法 400。
- `currency(String)`：去空白转大写，必须匹配 `[A-Z]{3}`，否则 400。
- `positiveMoney(BigDecimal, field)`：非空且 >0，否则 400。
- `fxRate(BigDecimal, currency)`：**CNY 必须显式写 1**（传 null 或其他值报错）；非 CNY 必须 >0；返回原值。成本口径 = `unit_price × fx_rate`（换算到 CNY 的采购价，不含头程/关税/包材）。
- `qty(int, field)`：>0 才合法；`effectiveFrom/effectiveTo` 为 ISO `yyyy-MM-dd`，`effective_to` 非空时必须 > `effective_from`。
- 站点枚举只允许 AE/SA（复用 `SiteAuthGuard.SUPPORTED_SITES`）。

## 3. 权限口径（Codex 关键审核点）

统一走 `SiteAuthGuard`，不接受请求体里的 userId：

| 实体 | 读 | 写 |
|---|---|---|
| 供应商 SUPPLIER | 至少 1 个授权站点即可读全部（无 site_code，全局档案）；空授权零行 | 仅超管 `guard.requireAdministrator()` |
| 报价 QUOTATION | 至少 1 个授权站点即可读全部 | 仅超管 |
| 采购单 PUR_ORDER | 普通用户仅授权站点（site_code IN 授权集合）；超管全站；空授权零行；可按 siteCode 单站过滤（先 requireSite） | 普通用户仅可对自己授权站点的单据 **confirm/receive/close**；**创建/修改/删除仅超管**（避免普通用户跨站建单） |
| 部分收货 RECEIPT | — | 与采购单站点一致，`guard.requireSite(订单真实 site_code)` 后执行；超管全站 |

- `get(id)`：先查记录真实 `site_code`，再 `guard.requireSite(真实站点)`；不能只信请求体。
- 菜单权限仅控制入口可见性，服务端校验不可省。Controller 用 `@PreAuthorize("@ss.hasPermi('noon:purchasing:<seg>:<perm>')")`。

## 4. 接口契约（5 类实体，统一 `/noon/masterdata` 之外的 `/noon/purchasing` 前缀）

所有 `list` 返回 `AjaxResult.success(PageResult)` → `body.data = { rows:[...], total:<long> }`，`offset` 为 DB 行偏移，`total` 为同一授权+筛选条件下的真实总数（COUNT 在 LIMIT/OFFSET 外，与 SELECT 共用 WHERE/参数）。limit 上限 200。

### 4.1 供应商 `/noon/purchasing/supplier`
- `record SupplierInput(String supplierNo, String supplierName, String contactJson, String status)`（contactJson 合法 JSON 或 null）
- `record SupplierRow(long id, String supplierNo, String supplierName, String contactJson, String status)`
- `record SupplierQuery(String supplierNo, String supplierName, String status)`
- `PageResult<SupplierRow> list(String siteCode, int offset, int limit, SupplierQuery q)`（siteCode 仅闸门：非超管需至少1授权站点，传值须 requireSite；供应商无 site_code 不做行过滤）
- `SupplierRow get(long id)` / `long save(SupplierInput in)` / `long update(long id, SupplierInput in)` / `void setStatus(long id, String status)`
- 唯一键 supplier_no 冲突 → 400 中文提示；置 INACTIVE 不影响历史采购单。

### 4.2 报价 `/noon/purchasing/quotation`
- `record QuotationInput(Long skuId, Long supplierId, BigDecimal unitPrice, String currency, Integer moq, Integer leadTimeDays, String effectiveFrom, String effectiveTo)`
- `record QuotationRow(long id, Long skuId, Long supplierId, BigDecimal unitPrice, String currency, Integer moq, Integer leadTimeDays, String effectiveFrom, String effectiveTo)`
- `record QuotationQuery(Long skuId, Long supplierId, String effectiveFrom, String effectiveTo)`
- `PageResult<QuotationRow> list(...)` / `get(long id)` / `long save(...)` / `long update(long id, ...)`
- **历史价口径**：按 `effective_from` 版本化，唯一键冲突 400；不物理删除，改价用新 `effective_from` 插入新行（旧单 unit_price 快照不变）。
- `QuotationRow effectivePrice(Long skuId, Long supplierId, LocalDate at)`：取 `effective_from <= at AND (effective_to IS NULL OR effective_to > at)` 且 `effective_from` 最大的一条；无匹配返回 null 或抛 400（由调用方决定，采购单 save 走抛 400）。
- 引用完整性：sku_id→`md_sku` 存在且 ACTIVE；supplier_id→`md_supplier` 存在且 ACTIVE（新增/修改同校验，同事务）。

### 4.3 采购单 `/noon/purchasing/order`
- `record OrderItemInput(Long skuId, Integer qtyOrdered, BigDecimal unitPrice, String currency, BigDecimal fxRate)`
- `record OrderInput(String orderNo, Long supplierId, String siteCode, String currency, String source, String sourceRefNo, List<OrderItemInput> items)`
- `record OrderItemRow(long id, long purOrderId, long skuId, int qtyOrdered, int qtyReceived, BigDecimal unitPrice, String currency, BigDecimal fxRate)`
- `record OrderRow(long id, String orderNo, Long supplierId, String siteCode, String currency, BigDecimal totalAmount, String status, String source, String sourceRefNo, Long operatorId, List<OrderItemRow> items)`
- `record OrderQuery(String orderNo, Long supplierId, String siteCode, String status, String source)`
- `PageResult<OrderRow> list(String siteCode, int offset, int limit, OrderQuery q)` / `OrderRow get(long id)`
- `long save(OrderInput in)`（仅超管，DRAFT）：
  - site_code 先 `requireSite`（超管用 SUPPORTED_SITES 校验合法）；supplier 存在且 ACTIVE；至少 1 行。
  - 每行：sku 存在且 ACTIVE、`qtyOrdered>0`、`unitPrice>0`、`fxRate` 按币种规则（CNY=1 / 外币>0）；行 `currency` 与订单 `currency` 不一致 → 400。
  - `total_amount = Σ(qty_ordered × unit_price × fx_rate)`（CNY 口径采购成本合计，只含采购价）。
  - 单价来源：前端按 `effectivePrice` 带出，后端**信任提交快照**（历史价口径：旧单不随报价变动），但必须 >0 且校验引用；后端不强制等于当前有效报价（改价中的单据仍可用旧价快照）。
- `long update(long id, OrderInput in)`（仅超管，**仅 DRAFT 可改**，非 DRAFT 报 400）：整体替换明细；不可变字段 `id/orderNo/status/qtyReceived` 不接受客户端修改。
- `void confirm(long id)`（站点内普通用户或超管）：仅 DRAFT→CONFIRMED；前置至少 1 行、供应商仍 ACTIVE、每行有效，否则 400。
- `void close(long id)`：仅 RECEIVED→CLOSED（全部到货）；其他状态 400。
- `setStatus` 不开放任意跳转，只允许上述状态机方法；Controller 不提供通用 setStatus。

### 4.4 部分收货 `/noon/purchasing/receipt`（核心，Codex 重点）
- `record ReceiptLineInput(Long itemId, Integer qty)`（itemId 必须属于该订单，qty>0）
- `record ReceiptInput(Long orderId, List<ReceiptLineInput> lines)`
- `record ReceiptRow(long id, Long orderId, Long orderItemId, int qty, BigDecimal unitCostCny, LocalDateTime receivedAt)`
- `ReceiptResult receive(ReceiptInput in)` → `record ReceiptResult(long orderId, String status, int receivedTotalQty, List<ReceiptRow> lines)`、`record ReceiptRow(...)` 同上。
- 事务内：
  1. `guard.requireSite(订单真实 site_code)`；
  2. 状态必须是 CONFIRMED 或 PARTIAL_RECEIVED，否则 400（DRAFT/RECEIVED/CLOSED 不可收）；
  3. 对每行 `SELECT ... FOR UPDATE` 锁 `pur_order_item`；
  4. 校验 `qty_received + qty <= qty_ordered`（**数量守恒**，超收 400，中文提示含行 id 与可收余量）；
  5. 更新 `qty_received`；写 `noon_m3_receipt` 明细表（新建，字段 id/order_id/order_item_id/qty/unit_cost_cny/received_at/operator_id）；
  6. 重算订单状态：全部行 `qty_received == qty_ordered` → RECEIVED，否则 PARTIAL_RECEIVED；
  7. 审计 action=RECEIVE（before/after JSON 含 qty_received 前后与本次收货行）。
- `unit_cost_cny = item.unit_price × item.fx_rate`（只含采购价）。并发超收由 FOR UPDATE + CHECK 双保险，真库 IT 验证。
- 新增表 `noon_m3_receipt` 也在迁移 SQL 内。
- 只读台账 `PageResult<ReceiptRow> list(String siteCode, int offset, int limit, Long orderId)`：按订单真实站点授权过滤（普通用户仅授权站点、超管全站、空授权零行；传 siteCode 先 requireSite），orderId 非空时精确过滤；与 `receive` 在同一 `ReceiptService`。供前端收货记录页与 E2E 回读使用。

### 4.5 有效价查询供前端带出
- `GET /noon/purchasing/quotation/effective?skuId=&supplierId=&at=` → `AjaxResult.success(QuotationRow 或 null)`；`at` 省略用当天；需授权。

## 5. 引用完整性与状态机汇总（必须测试）

- supplier：supplier_no 唯一；改状态 INACTIVE 不影响旧采购单（旧单快照保留 supplier_id）。
- quotation：(sku_id,supplier_id,effective_from) 唯一；sku/supplier 存在且 ACTIVE；区间重叠检测：`effective_to` 与同组合其他行 `effective_from` 不得冲突（新增/修改时校验，400）。
- order：supplier ACTIVE；行 sku ACTIVE；行 currency == 订单 currency；fxRate 按币种；至少 1 行；仅 DRAFT 可改。
- 状态机：`DRAFT --confirm--> CONFIRMED --receive(部分)--> PARTIAL_RECEIVED --receive(补齐)--> RECEIVED --close--> CLOSED`。非法跳转 400。
- 数量守恒：任意时刻 `0 <= qty_received <= qty_ordered`；累计收货不得超订；RECEIVED 时全行满足等式。
- 成本口径：`total_amount` 与 `receipt.unit_cost_cny` 只含 `unit_price × fx_rate`，不含费用；`fx_rate` CNY 显式 1，缺失报错。

## 6. 文件归属（owner，防同时改同一文件）

| Owner | 文件 |
|---|---|
| 豆包 | `yxerp-noon/src/main/java/com/yx/noon/purchasing/**`（Service、records、Rules、Audit）、`yxerp-noon/src/test/java/com/yx/noon/purchasing/**`（H2 单测 + 真库 IT）、`docs/verification/m3-purchasing/doubao-report.md` |
| WorkBuddy | `yx-erp/sql/noon_m3_purchasing.sql`、`noon_m3_menu.sql`、`yxerp-admin/.../controller/noon/*Controller.java`（4 个）、`yxerp-ui/src/api/noon/purchasing/*.js` + `views/noon/purchasing/**`、`scripts/verify-m3-e2e.py`、`scripts/m3-browser-check.js`、`docs/verification/m3-purchasing/contract.md`、`docs/agent-collaboration.md` |

严禁跨 owner 修改；需对方改动时在报告里写明请求，由 WorkBuddy 协调。

## 7. 验证要求（隔离环境，绝不连生产）

- H2 单测：每类覆盖命中/未命中、分页总数一致、空授权零行、引用完整性负例、唯一键冲突、状态非法跳转、数量守恒超收、成本口径（CNY fx=1 / 外币换算）。
- 隔离 MySQL 8.4 真库 IT：DDL/CHECK、并发超收（FOR UPDATE）、审计落库与回滚。
- 完整若依应用 HTTP E2E：4 类身份（admin / opAE 仅 AE / opAESA / viewer 无站点）真实登录 + 站点隔离 + 状态机 + 部分收货 + 成本口径 + 菜单权限。
- 真实浏览器关键流程证据：采购单 DRAFT→CONFIRMED→部分收货→RECEIVED→CLOSED、跨站拒绝、无权限用户；保留截图与逐项结果。
- 前端 `vite build` 到独立输出目录成功。

## 8. 未覆盖（如实标注）

真实 Noon 平台字段/Offer 回读、头程与费用池、库存批次与四态台账、审批流、结算金额校验、供应商绩效均为后续模块；本模块只交付上述隔离闭环。

---

## 9. Codex M3 初审整改（v2 增量，2026-11-22）

基线：Codex「M3 关键流程初审」两个**必须改**项——① 收货响应丢失重试会虚增数量（同一请求无唯一标识）；② 报价区间「先查后插」并发可产生重叠价。本节只增量覆盖 v1 的 4.2 / 4.4 / 1 / 7，v1 其余条款继续有效。改完回报后再做 M3 验收。

### 9.1 收货幂等（覆盖 4.4）

**目标**：区分「同一请求重试」与「两次真实部分收货」；同一请求无论重试多少次、是否并发，只入账一次；响应丢失重放返回首次结果。

- `ReceiptInput` 改为 `record ReceiptInput(Long orderId, List<ReceiptLineInput> lines, String requestId)`；`requestId` 必填，长度 8–64，仅允许 `[A-Za-z0-9_-]`（前端一次提交动作生成一个 UUID，重试原样复用；真实第二次收货用新 UUID）。字段缺失或格式非法 → 400「收货请求缺少有效的 requestId」。
- 新增幂等请求头表（迁移 SQL 由 WorkBuddy 负责，H2 单测由豆包在测试内建同构表）：
  ```sql
  CREATE TABLE IF NOT EXISTS noon_m3_receipt_request (
    request_id  VARCHAR(64)  NOT NULL COMMENT '客户端幂等键，一次收货提交唯一',
    order_id    BIGINT       NOT NULL,
    operator_id BIGINT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
    result_json JSON         COMMENT 'DONE 时缓存 ReceiptResult，重试原样返回',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (request_id),
    KEY idx_m3_req_order (order_id),
    CONSTRAINT chk_m3_req_status CHECK (status IN ('PENDING','DONE'))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 收货幂等请求头';
  ```
- `receive(ReceiptInput in)` 流程（保持单事务 `@Transactional`）：
  1. 入参常规校验后，先 `INSERT INTO noon_m3_receipt_request(request_id,order_id,operator_id,status) VALUES(?,?,?,'PENDING')`（operatorId 取当前登录人，可为 NULL）。
     - 抛 `DuplicateKeyException` 表示同一 requestId 已处理/处理中：读该行；若 `order_id` 与请求不一致 → 400「requestId 已用于其他采购单」；若 `status='DONE'` → 反序列化 `result_json` 直接返回**首次完整结果**（含原 receipt 行 id、状态、累计数量），**不重复入账、不写审计**；若 `status='PENDING'` → 400「同一收货请求正在处理中，请稍后重试」。
     - 并发两个相同 requestId：唯一键阻塞到首个事务提交，后者读到 `DONE` 命中返回；首个事务失败回滚则 PENDING 行一并回滚，允许同 requestId 重试。
  2. **注意顺序**：先按 requestId 判重（步骤 1），再 `lockOrder` + `guard.requireSite` + 状态校验 + 逐行 `FOR UPDATE` + 数量守恒 + 写 receipt 明细 + 重算状态 + 审计（v1 4.4 步骤 2–7 不变）。
  3. 提交前将本次 `ReceiptResult` 序列化写入同 requestId 行 `status='DONE', result_json=?`。任何异常 → 事务回滚，幂等行与全部业务写入一并回滚。
- `ReceiptResult` 增加 `boolean replayed`（record 第 5 个分量）；命中幂等返回时置 `true`，首次成功置 `false`，供前端提示「该收货请求已处理，未重复入账」。
- 只读台账 `list` 不变；幂等行不对外暴露接口。

### 9.2 报价区间并发（覆盖 4.2 / 5）

**目标**：同一 `(sku_id, supplier_id)` 的新增与修改可靠串行化，杜绝不同 `effective_from` 的并发请求绕过 `requireNoOverlap` 生成交叉区间。

- 新增组合锁表（迁移 SQL WorkBuddy 负责，H2 测试内建同构表）：
  ```sql
  CREATE TABLE IF NOT EXISTS noon_m3_quotation_lock (
    sku_id      BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    PRIMARY KEY (sku_id, supplier_id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 报价组合串行化锁表';
  ```
- `QuotationService.save` 与 `update` 在 `validate()` **之前**先获取组合锁：
  - `try INSERT INTO noon_m3_quotation_lock(sku_id,supplier_id) VALUES(?,?)`（锁行不存在则创建）；捕获 `DuplicateKeyException` 忽略（表示已有锁行）。
  - 随后 `SELECT id FROM noon_m3_quotation_lock WHERE sku_id=? AND supplier_id=? FOR UPDATE`，持有锁到事务提交。之后再执行 `validate`（含 `requireNoOverlap`）和写入。
  - `update` 若修改了 `sku_id`/`supplier_id`，须锁 **old 与 new 两个组合**（按 skuId、supplierId 排序依次加锁，避免死锁）。
- 唯一键 `uk_sku_sup_eff` 与 `requireNoOverlap` 保留不变，作为锁失效时的第二道防线。
- 并发边界必须在隔离 MySQL 8.4 真库 IT 验证：两个线程同时新增同一 sku+supplier、不同 `effective_from` 且区间重叠 → 恰好 1 成功 1 失败；串行执行时第二个返回「区间重叠」400。

### 9.3 API / 前端接入（覆盖 6）

- `POST /noon/purchasing/receipt/receive` 请求体新增 `requestId`；Controller 无需改签名（Jackson 直接绑定 record），但需在异常映射中覆盖幂等 400 文案。
- 前端收货弹窗打开时生成一次 UUID（`crypto.randomUUID()`，降级为时间戳+随机串），存于 `receiveForm.requestId`，提交时透传；提交失败保留同一 requestId 供重试，成功后关闭弹窗，下次打开生成新值。UI 对 `replayed=true` 提示「该请求已处理，未重复入账」。
- 报价页面无需改造：并发冲突沿用后端 400 文案透传。

### 9.4 验证增量（覆盖 7）

- H2 单测：同 requestId 重复收货只入账一次且返回 `replayed=true`；不同 requestId 两次真实收货正常累加；requestId 非法 400；报价 update 改组合的负例。
- 隔离 MySQL IT：并发同 requestId 收货恰好 1 次入账（receipt 行数与 qty_received 守恒）；并发重叠报价恰好 1 成功 1 失败；失败事务回滚后同 requestId 可重试成功。
- HTTP E2E：收货请求均带 requestId；新增「同 requestId 重放不增产」断言；真实浏览器收货流程带 requestId 并能完成状态机。

### 9.5 v2 文件归属（覆盖 6）

| Owner | 文件（增量） |
|---|---|
| 豆包 | `yxerp-noon/src/main/java/com/yx/noon/purchasing/{ReceiptInput,ReceiptService,ReceiptResult,QuotationService}.java`（+内部 helper）、`yxerp-noon/src/test/java/com/yx/noon/purchasing/{PurchasingServiceTest,MySqlPurchasingIT}.java`、`docs/verification/m3-purchasing/doubao-report.md`（追加 v2 段） |
| WorkBuddy | `yx-erp/sql/noon_m3_purchasing.sql`（追加 2 张表）、4 个 Controller、`yxerp-ui/src/api/noon/purchasing/receipt.js` + `views/noon/purchasing/order/index.vue`、`scripts/verify-m3-e2e.py`、`scripts/m3-browser-check.js`、本契约与协作记录 |

严禁跨 owner 修改；H2 测试建表由豆包在自己的测试文件内完成，真库迁移由 WorkBuddy 在 SQL 文件内完成，两边 DDL 必须字段一致。

### 9.6 Codex M3 v2 复核整改（v2.1，2026-11-22）

基线：Codex「M3 v2 复核：重放权限与请求身份」三个静态问题——① `claimRequest` 重放后立即 return、`guard.requireSite` 在其后，越权可读结果；② 只绑 orderId、不绑明细内容，同键换数量误报成功；③ 页面重开生成新键，响应不明时可能再次入账。以下为增量口径，v2 §9.1 其余不变。

**① 授权顺序（重放也必须过站点授权）**
- `receive` 内顺序强制为：`requireRequestId/requireLines` 入参校验 → `operatorId` → **`lockOrder(in.orderId())` + `guard.requireSite(head.siteCode())`（真实站点）** → 计算内容指纹 → `replayDone`（已 DONE 且同内容则原样重放返回，不受状态闸门约束，兼容 §9.1「收满后重发同键仍 replayed=true」）→ **仅首次入账路径才做**状态校验 → `claimRequest` 占位/判重 → 数量守恒/写入/审计/markDone。
- 关键 1：站点授权必须在 `replayDone` / `claimRequest`（含 PENDING/DONE 分支）**之前**完成；无该订单站点权限者即便持有合法 requestId+orderId 也只能得到 403，不能读到任何结果。授权撤销后、跨站（opAE 持 AE 权限访问 SA 单）重放均须 403。
- 关键 2：状态校验只约束首次入账。已 DONE 的同键同内容请求先于状态判定返回，否则「收满转 RECEIVED 后响应丢失重发」会被 400「不可收货」错误拒绝，违背 §9.1 幂等承诺。本条与 §9.1 的「先判重再状态校验」语义一致，§9.1 中「再 lockOrder + requireSite + 状态校验」的措辞以本节授权前移与重放优先规则为准。
- 幂等表新增只读查询 `ReceiptResult resultOf(String requestId)`：先按 requestId 查幂等行取 `order_id`，再按订单真实 `site_code` 走 `requireSite`，之后才返回结果；无权者 403，requestId 不存在返回 null。任何查询路径不得绕过订单真实站点授权。

**② 请求内容绑定（同键不同内容必须拒绝）**
- `noon_m3_receipt_request` 增列 `content_fingerprint CHAR(64) NOT NULL`，存规范化内容指纹：
  - 规范化串 = `orderId + "|" + (lines 按 itemId 数值升序后每项 "itemId:qty" 以逗号连接)`，如 `12|3:60,7:40`；
  - 算法 = SHA-256，输出 64 位小写十六进制。
- 首次 INSERT PENDING 时写入指纹；`replayOf` 比对已存指纹与本次计算值，不一致抛 400「requestId 对应的收货内容与原请求不一致」（不得返回成功、不得入账）。
- 优先级：站点授权 > 内容指纹 > 状态/结果（先授权，再判内容，再谈重放）。

**③ 前端重试与结果查询（响应不明不再重复入账）**
- 收货提交生成 requestId 后即以 `m3:receipt:pending:<orderId>` 为 key 存 `localStorage`（值=requestId）。提交成功（含 replayed）清除该 key；提交失败/响应未知保留该 key。
- 再次打开收货弹窗：若该订单存在 pending key，前端先调 `GET /noon/purchasing/receipt/result?requestId=<key>`：
  - 返回 `DONE`（含 replayed=true）→ 展示「上次收货已成功入账」及结果，清除 pending key，用户如需再次收货须**主动关闭并重开弹窗生成新键**；
  - 返回 null（requestId 不存在/已回滚）→ 复用该 key 重新提交（安全，因为原事务已回滚）；
  - 返回 403 → 提示无权限，不提交。
- `resultOf` 查询接口复用 §① 的授权实现，不得新增越权通道。

**④ 验证增量**
- H2 单测：① 无站点用户（viewer）持有效 requestId+orderId 重放被 403、opAE 跨 SA 单重放 403；② 同 requestId 不同 qty 返回 400、不增产；③ `resultOf` 越权 403、无权用户不可读结果。
- 隔离 MySQL IT：授权撤销后重放 403（用站点授权桩切换）、内容指纹冲突拒绝、`resultOf` DONE/null 两态。
- HTTP E2E：opAE 用 admin 的 requestId 跨站重放 403、同键改 qty 400、浏览器重开弹窗走 result 查询命中 DONE 不重复入账。

**⑤ v2.1 文件归属**：豆包 owner 同 §9.5，另加 `ReceiptService` 的 `resultOf` 与指纹校验；WorkBuddy owner 同 §9.5，迁移 SQL 加 `content_fingerprint` 列、`ReceiptController` 加 `/result` 接口、前端 `receipt.js` + `order/index.vue` 的 pending 存储与重开查询、E2E/浏览器脚本断言。

**⑥ 生成器约定更新（Codex 已写入 `development-modules.md` 第 6 条）**：从 M4 起一般情况必须实际运行若依生成器生成前后端骨架再完善；仅沿用旧骨架/手写不能替代生成；确不适用部分可例外，但须记录具体原因、生成/沿用/手写范围及验证，不因此暂停已授权工作。M3 已交付，按历史事实如实记录（本轮未跑生成器），不追溯重做。
