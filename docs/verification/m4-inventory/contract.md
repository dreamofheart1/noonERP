# M4 库存与 FIFO · 第一轮「采购收货→入库→四态台账」契约 v1

日期：2026-11-22。依据：`docs/development-modules.md` M4 行 + Codex「开始 M4 库存开发」授权（见 `docs/agent-collaboration.md`）。

## 0. 第一轮范围（先读，收敛边界）

M4 全量范围（入库批次/四态台账/调拨/头程/ASN/盘点/批次消耗）较大，按流程约定「先交付可运行的小闭环，再由 Codex 集中审核」。**第一轮只做入库闭环**：

- ✅ 做：采购收货单 → 生成入库单（DRAFT）→ 确认入库（CONFIRMED）→ 生成 FIFO 批次 `inv_batch` + 更新四态台账 `inv_stock_ledger` + 写库存流水 `inv_stock_move`。
- ✅ 做：从采购单/收货记录带出待入库明细；入库列表/详情/确认；站点授权；幂等；数量与成本守恒；C-5 拆批与短少的**基础**（见 §5）。
- ✅ 做：复用现有 `FifoConsumptionService` / `ReversalService` / `IntakeService`（出库/红冲/入账），本轮不改它们；只在真库端到端验证「入库批次能被现有 FIFO 出库正确消耗」。
- ❌ 不做（第二轮）：调拨 `inv_transfer`、头程 `log_shipment_cost`/ASN `log_asn`、盘点 `inv_stocktake`、在途/拆批的完整状态机、平台 FBN Inbound 对接。这些表 DDL 已在 schema v0.5，本轮不建 Java/页面，但要确保本轮 DDL 不与之冲突。
- ❌ 不连生产、不部署、不推送、不碰密钥；全部隔离 MySQL 8.4 + H2。

## 1. 数据表（新建隔离迁移 `noon_m4_inventory.sql`，幂等 DDL）

沿用 `docs/noon-erp-schema-v0.5.sql` 中已有定义，**不改字段语义**：`inv_inbound`、`inv_inbound_item`、`inv_batch`、`inv_stock_ledger`、`inv_stock_move`。每张表 `CREATE TABLE IF NOT EXISTS`。

缺口与增量（本轮补）：
- 入库幂等表 `noon_m4_inbound_request`：`request_id VARCHAR(64) PK`、`pur_order_id BIGINT`（注：以真库 DDL 字段名为准，M3 对应字段叫 order_id，本表统一用 pur_order_id）、`content_fingerprint CHAR(64) NOT NULL`、`status VARCHAR(16) PENDING/DONE`、`result_json JSON`、`operator_id BIGINT`、`created_at`。语义完全对齐 M3 `noon_m3_receipt_request`（授权>指纹>状态优先级、DONE 重放不受状态闸门阻挡、PENDING 并发拒绝、异常整事务回滚）。
- 审计表 `noon_m4_inventory_audit`：`id`、`biz_type`、`biz_id`、`action`、`detail`、`operator_id`、`created_at`。
- 菜单 `noon_m4_menu.sql`：`noon:inventory:inbound:{list,query,add,confirm}`、`noon:inventory:ledger:list`、`noon:inventory:batch:list`。

## 2. 核心规则（Codex 重点审核点）

1. **站点授权经仓库归属**：`inv_inbound` / `inv_inbound_item` / `inv_stock_ledger` 表**无 `site_code` 字段**（台账有 site_code，但入库单没有）。授权口径：`guard.requireSite(仓库.site_code)`，仓库来自 `md_warehouse WHERE id=in.warehouseId`，且仓库 `status='ACTIVE'`。无授权/跨站 → 403，不写任何数据。确认/查询/重放/结果查询全部按此口径。禁止用请求体里的 siteCode 自报站点。
2. **数量守恒**：入库单行 `qty_qualified <= qty`（DDL CHECK）。生成批次 `qty_in = qty_qualified`、`qty_remaining = qty_qualified`（**只合格量进可用库存**；不合格量不计入批次，留待 M5 退货/待检处理，本轮如实标注）。分批校验：一个入库单可对同一 SKU 多行，每行生成一个批次（batch_no 唯一）。
3. **成本守恒（防客户端伪造，Codex 重点）**：批次 `unit_cost` **必须由后端从 M3 收货记录 `noon_m3_receipt.unit_cost_cny`（= unit_price × fx_rate）读取**，**禁止信任入参里的 unitCost/skuId/batchNo**——这三者前端一律不传，由后端按 purOrderItemId 权威带出。`currency='CNY'`、`fx_rate=1`（CNY 本币显式写 1）。**不摊头程/关税/包材**（Q8，第二轮 log_shipment_cost 也不摊）。金额 = qty × unit_cost，HALF_UP 4 位。
   - 客户端仅提交：`purOrderItemId` + `qtyQualified`（合格量）+ 仓库；后端校验该收货行属于 purOrderId、SKU/成本/批次号全部服务端生成。若入参夹带 unitCost/skuId/batchNo 字段，因入参 record（§3）结构上不含这些分量，Jackson 绑定阶段即丢弃——这是**结构性阻止**（客户端无法污染权威值），而非运行时审计捕获；H2 单测 `clientSuppliedCostSkuAndBatchNoAreIgnored` 与 E2E「伪造成本未进入批次」验证其被忽略。不在 Controller 补记审计（Controller 同样通过 record 绑定，无法拿到被丢弃字段，补录无意义）。
4. **FIFO 批次可消耗**：新批次 `status='OPEN'`、`received_at`=确认时刻 UTC+8、`source_type='PURCHASE'`、`parent_batch_id=NULL`、`split_seq=0`。字段顺序与 `FifoConsumptionService.openBatches()` 的 `WHERE sku_id=? AND site_code=? AND status='OPEN' AND qty_remaining>0 ORDER BY received_at ASC,id ASC FOR UPDATE` 对齐，确保真库端到端可由现有 FIFO 出库消费。
5. **四态台账**：`inv_stock_ledger(sku_id,warehouse_id)` 唯一键；首次插入 `qty_available=qty_qualified`，已存在则 `qty_available += qty_qualified`（用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先查后插+唯一键兜并发，H2/MySQL 均需通过）；`qty_in_transit`/`qty_reserved`/`qty_unsellable` 本轮不动。带 `site_code` 且校验与仓库站点一致，跨站复用同 (sku,warehouse) 台账须拒绝。
6. **库存流水**：每个入库行写一条 `inv_stock_move`，`change_type='INBOUND'`、`qty_delta=+qty_qualified`、`ref_doc_type='INBOUND'`、`ref_doc_no=inbound_no`、`move_no` 唯一、`operator_id`、时间戳（带编号与时间，不删字段）。
7. **幂等（绑定入库事件）**：`request_id` 由调用方生成（UUID），绑定 `purOrderId|仓库id|内容指纹`；内容指纹 = SHA-256(purOrderId|warehouseId|按 purOrderItemId 升序的 purOrderItemId:qtyQualified)。**注意：skuId/unitCost/batchNo/qty 全部后端权威带出、不进入入参，故不参与指纹**（与 §2.3 防伪造口径一致；早期草稿里出现的 `skuId:qty:unitCost` 指纹已作废，以此处为准）。授权 > 指纹 > DONE 重放 > 状态闸门；重放返回首次 `InboundResult` 并 `replayed=true`，不重复生成批次/台账/流水/审计。PENDING 并发 → 400「处理中」；内容不一致 → 400；异常整事务回滚（PENDING 行一并消失，同键可重试）。`resultOf(requestId)` 只读：按幂等行 pur_order_id→采购单→仓库真实站点 requireSite→才返回，无权 403、不存在 null。
   - **额外守恒（豆包实现增强，Charter 采纳）**：除单次 qtyQualified≤来源收货量外，另加「同一 purOrderItemId 累计已入库量 ≤ 累计收货量」校验，防止不同 requestId 分批入库虚增库存；如需严格按字面单次校验可回退。
8. **状态机**：`inv_inbound.status` DRAFT→CONFIRMED，确认幂等（已 CONFIRMED 且同键直接返回 DONE 结果）。CLOSED 本轮不用。无审批流（Q11）。

## 3. 接口契约（新包 `com.yx.noon.inventory`，风格参考 `purchasing`）

所有 Service 构造注入 `JdbcTemplate` + `SiteAuthGuard`；record 均 `public`；时间 UTC+8（`LocalDateTime.now()`，与现有服务一致）；异常用 `IllegalArgumentException`（400）/ `guard` 抛 403。

```java
public record InboundInput(Long purOrderId, Long warehouseId, String siteCode, List<InboundLineInput> lines, String requestId) {} // siteCode 仅前端带出展示，服务端以仓库归属为准校验一致
public record InboundLineInput(Long purOrderItemId, int qtyQualified) {} // 只提交来源行+合格量；skuId/unitCost/batchNo/qty 全部后端从收货权威带出，禁止信任客户端
public record InboundResult(Long inboundId, String inboundNo, boolean replayed, java.util.List<Long> batchIds) {}
public record InboundRow(Long id, String inboundNo, Long purOrderId, Long warehouseId, String status, String siteCode, Integer itemCount, java.time.LocalDateTime createdAt) {}
public record InboundDetail(InboundRow head, java.util.List<InboundItemRow> items) {}
public record InboundItemRow(Long id, Long purOrderItemId, Long skuId, String batchNo, Integer qty, Integer qtyQualified, java.math.BigDecimal unitCost) {}
public record BatchRow(Long id, String batchNo, Long skuId, Long warehouseId, String siteCode, java.time.LocalDateTime receivedAt, Integer qtyIn, Integer qtyRemaining, java.math.BigDecimal unitCost, String status) {}
public record LedgerRow(Long id, Long skuId, Long warehouseId, String siteCode, Integer qtyAvailable, Integer qtyInTransit, Integer qtyReserved, Integer qtyUnsellable) {}
public PageResult<InboundRow> list(String siteCode, int offset, int limit) {}   // 复用 masterdata.PageResult
```

Service 方法（**豆包 owner**，`yxerp-noon/src/main/java/com/yx/noon/inventory/`）：
- `@Transactional public InboundResult confirm(InboundInput in)`：授权→指纹→幂等判重→校验→写入库单/行/批次/台账/流水/审计→DONE。
- `@Transactional(readOnly=true) public InboundResult resultOf(String requestId)`。
- `public PageResult<InboundRow> list(String siteCode, int offset, int limit)`；`public InboundDetail get(Long id)`（带授权）；`public PageResult<BatchRow> listBatches(String siteCode,int offset,int limit,Long skuId)`；`public PageResult<LedgerRow> listLedger(String siteCode,int offset,int limit)`。
- 辅助 record/枚举/规则类自定，但列入报告。

Controller（**WorkBuddy owner**，`yxerp-admin/.../noon/InventoryController.java`）：
- `GET /noon/inventory/inbound/list?siteCode=&offset=&limit=` → AjaxResult.success(PageResult)
- `GET /noon/inventory/inbound/get?id=` → InboundDetail
- `POST /noon/inventory/inbound/confirm` → InboundResult（body=InboundInput）
- `GET /noon/inventory/inbound/result?requestId=` → InboundResult
- `GET /noon/inventory/batch/list?siteCode=&offset=&limit=&skuId=` → PageResult<BatchRow>
- `GET /noon/inventory/ledger/list?siteCode=&offset=&limit=` → PageResult<LedgerRow>
- 权限注解 `@PreAuthorize("@ss.hasPermi('noon:inventory:...')")`；`IllegalArgumentException`→400、授权异常→403（照 `ReceiptController`）。

前端（**WorkBuddy owner**，按新规**先运行若依生成器**生成骨架再改造）：
- `yxerp-ui/src/api/noon/inventory/{inbound,batch,ledger}.js`
- `yxerp-ui/src/views/noon/inventory/inbound/index.vue`（列表/新增入库/详情/确认，confirm 前生成 requestId、重开先查 result 策略同 M3）、`batch/index.vue`、`ledger/index.vue`（只读）。

## 4. 复用（不重复实现）

- `com.yx.noon.fifo.FifoConsumptionService.postDelivery`、`com.yx.noon.reversal.ReversalService.postReversal`、`com.yx.noon.intake.IntakeService`：本轮不改源码；真库 E2E 验证「M4 入库批次 → 现有 FIFO 出库扣除 → 台账 available 同步减少」。
- `com.yx.noon.masterdata.PageResult`、`WarehouseService`（查仓库归属）、`M3 ReceiptService`（提供收货明细来源：confirm 时权威从 `noon_m3_receipt` 按 purOrderItemId 逐行取累计收货量与成本；采购单状态须 ∈ PARTIAL_RECEIVED/RECEIVED/CLOSED（见 §9.1），来源行无收货记录（receivedQty≤0）直接拒绝，避免无来源入库）。
- `SiteAuthGuard.requireSite/allowedSiteCodes`（yxerp-framework，不改）。

## 5. C-5 拆批与短少（本轮边界，如实标注）

- 本轮只做**原始采购批次**（parent_batch_id=NULL、split_seq=0、source_type=PURCHASE）。
- 拆批血缘字段（parent_batch_id/split_seq/split_reason/transfer_no/asn_no）DDL 已在 schema，本轮不写入；调拨在途/短少拆批属第二轮，**不宣称已完成**。
- 短少：`qty_qualified < qty` 时只有合格量进批次与台账；差额不生成任何库存，需在交付报告中明确「不合格量本轮不进 unsellable，待 M5 待检流程」。

## 6. 文件归属（owner，防同时改同一文件）

| 负责方 | 文件/目录 |
|---|---|
| 豆包 | `yxerp-noon/src/main/java/com/yx/noon/inventory/**`（新建）；`yxerp-noon/src/test/java/com/yx/noon/inventory/**`（H2 单测 + `MySqlInventoryIT`）；`docs/verification/m4-inventory/doubao-report.md` |
| WorkBuddy | `docs/verification/m4-inventory/contract.md`；`yx-erp/sql/noon_m4_inventory.sql` + `noon_m4_menu.sql`；`yxerp-admin/.../noon/InventoryController.java`；`yxerp-ui/src/api/noon/inventory/**` + `src/views/noon/inventory/**`；`scripts/verify-m4-*.py`；生成器原始产物 `docs/verification/m4-inventory/generated/**`；协作文档 |

严禁跨 owner；H2 测试建表由豆包在自己测试文件内完成，真库迁移由 WorkBuddy 在 SQL 文件完成，两边 DDL 必须字段一致。

## 7. 验证要求（隔离环境）

- H2 单测：授权（无站点/跨站/仓库不存在）、守恒（qty_qualified>qty 拒绝）、幂等（重放/并发同键/内容冲突/失败回滚重试）、台账累加（同 sku+仓库二次入库累加）、批次字段对齐 FIFO（received_at/status）、resultOf 两态、流水编号与时间戳。
- 隔离 MySQL IT（`MySqlInventoryIT`）：真 DDL 约束、并发同键仅生成一次批次/台账、真库端到端「入库批次 → 现有 FIFO 出库消费 → 批次 qty_remaining 与台账 available 一致」。
- HTTP E2E：完整应用（MySQL+Redis+若依），入库 confirm + 重放 + 越权 + 列表/详情分页 + batch/ledger 查询；真实浏览器 UI 闭环。
- 全项目离线 `mvn test`、`vite build`；所有结果写入 `docs/verification/m4-inventory/`。

## 8. 生成器要求（M4 起新规）

WorkBuddy 必须**实际运行若依代码生成器**（`/tool/gen/importTable` + `batchGenCode` 或 `genCode`）生成入库单前后端骨架，原始产物留存 `docs/verification/m4-inventory/generated/`；在交付报告中如实写明：生成范围、沿用范围、手写范围、未用生成器的部分及原因。避免覆盖已有代码。

## 9. v1.1 修订（回应 Codex「M4 第一轮重点复核」两条）

日期 2026-11-22。Codex 复核提出两点，均采纳并修订：

### 9.1 放开 PARTIAL_RECEIVED 分批入库（修正 §2.2/§2.8 状态闸门）

**背景**：M3 收货状态机 `DRAFT→CONFIRMED→PARTIAL_RECEIVED→RECEIVED→CLOSED`，支持分批收货。跨境采购分批到货是常态；原 §2.2「仅 RECEIVED/CLOSED 可入库」导致首批已收但不能先入库，与业务不符。

**修订口径**：
- `INBOUNDABLE_ORDER_STATUSES` 由 `List.of("RECEIVED","CLOSED")` 改为 **`List.of("PARTIAL_RECEIVED","RECEIVED","CLOSED")`**；错误文案同步为「仅 PARTIAL_RECEIVED/RECEIVED/CLOSED 可入库」。DRAFT/CONFIRMED（尚无收货）仍拒绝。
- 安全性不依赖整单收齐，而依赖**逐行守恒**（§2.2 增强 + §2.7 额外守恒）：每行 `qtyQualified <= 该行累计收货量 - 该行累计已入库量`，按 `purOrderItemId` 锁行串行化。未收货的行 `receivedQty<=0` 直接拒绝；已收部分可先入，后续收货再入不超累计量。这条守恒在首批 v1 已实现，本轮复用，不放宽、不绕过。
- 授权 > 指纹 > DONE 重放 > 状态闸门的优先级不变（§2.7）；重放不受状态闸门影响，因此状态机内任意时刻重放仍返回首次结果。
- 测试补：`InventoryServiceTest` 加「PARTIAL_RECEIVED 首批部分行入库成功 → 第二批补齐未超累计收货 → 第三批超累计拒绝」；`MySqlInventoryIT` 加真库分批入库用例（真 DDL 下 qty_qualified<=qty 与累计守恒同时成立）。

### 9.2 生成器「实际采用」口径（修正 §8 的模糊表述）

首轮仅导出 inv_inbound 单表 CRUD zip，交付代码未采用其中任何一行——按用户「适合则实际生成骨架并在其上修改」的要求，这不达标。本轮修订：

- **实际采用生成骨架**：对 `inv_batch`、`inv_stock_ledger` 两张**只读台账表**，实际运行生成器导出前后端骨架（`/tool/gen/importTable` + `batchGenCode`），并把生成 Vue 骨架的 `<template>` 结构（el-form inline 查询区 + right-toolbar + el-table + pagination）、`<script setup>` 骨架（data/queryParams/getList/handleQuery/resetQuery + `getList()` 调用）与 api.js 的 `listXxx(query)` 模式**落地到实际使用的 batch/ledger 页面与 api**，仅按只读台账需要删去增删改、改字段与 API 路径。原始生成产物留存 `generated/raw/batch/**`、`generated/raw/ledger/**`，采用对照写入 `generated/ADOPTION.md`。
- **未采用生成骨架的部分及原因（如实）**：
  1. `inv_inbound` 后端骨架（Controller/domain/mapper/service/xml）：单表 CRUD，无法承载 confirm 跨 6 表状态机（批次+台账+流水+幂等+审计）、仓库归属授权、逐行 FOR UPDATE 守恒、内容指纹；豆包手写 JdbcTemplate 内核是业务必需，非偷懒。
  2. `inv_inbound` 前端骨架：标准增删改单表页，与「选采购单→拉收货明细→填合格量→确认入库」的多步业务语义不符；入库页在此基础上手写交互，但保留生成的查询表单/表格/分页结构范式。
  3. 三页权限从 `system:inbound:*` 改为业务 `noon:inventory:*`（菜单注入），生成骨架的权限串不可直接沿用。
- 目标：**有具体生成代码被实际采用并可对照**，不是仅凭导出 zip 视作采用；同时如实说明不适合部分，符合用户「适合则采用、不适合可说明」的口径。
