# 采购直发 FBN 闭环契约 v1（owner：豆包后端内核 / WorkBuddy 集成）

> 来源：`docs/agent-collaboration.md` 2026-11-22「优先跑通采购直发 FBN」用户/Codex 指令。
> 术语：FBN = Noon 官方履约仓；本契约中的 FBN 仓是**系统内部记录**，不表示已在 Noon 平台建仓或预约。
> 目标链路：**系统 SKU → 采购单 → 头程费用（关联采购单）→ ASN 登记 → FBN 官方仓实收（产生可用库存）**。
> 硬约束：不伪造国内仓收货/可用库存/供应商交付；成本可追溯、数量守恒、权限隔离、重复请求安全；官方仓确认实收后才产生可用库存；期间/分摊标记不接财务。

## §0 现状与断点（盘点结论）

| 环节 | 现状 | 直发缺口 |
|---|---|---|
| 仓库主数据 | `md_warehouse` 支持 `warehouse_type=FBN`/`is_fbn=1`/站点/状态；dev 库当前只有 2 个 LOCAL 仓 | 需为 AE/SA 建 FBN 官方仓**内部记录**（复用现有仓库管理接口，不新增代码） |
| 头程费用 | `log_shipment_cost` + `log_shipment_cost_po` 支持多选关联采购单（已实现） | 费用是纯登记，无发运状态；直发的"发运/在途"由 ASN seal 承载，费用关联采购单已够用 |
| ASN | `AsnService.create/seal/receive` 三态 DRAFT→SEALED→RECEIVED；强依赖源仓 OPEN 批次 | create 的 `batchNo` 强制本地仓 OPEN 批次（前端 :212 + 后端 `requireCreateSourceBatch` :875-911）；直发无本地批次，无法建单 |
| 成本来源 | FBN 可用库存唯一产生点：`receive()` 子批次 IN_TRANSIT→OPEN + 台账 available+ | 直发无源批次，seal 的 `insertChildBatch` 无法继承成本；需以采购收货成本建直发批次 |

## §1 关键设计决策（已定，勿擅改）

**D1｜成本权威来源沿用 `noon_m3_receipt.unit_cost_cny`**（采购收货记录，= unit_price × fx_rate，只含采购价）。
- 直发不绕开采购收货：采购单须先有收货记录（可部分收货/分批），才有可追溯成本。
- 采购收货**不建本地仓批次、不动自有仓台账**（ReceiptService 现状就是如此，保持）。
- 直发父批次成本来自对应 `pur_order_item` 的收货记录；分批收货 → 多次直发，数量守恒由收货量约束。

**D2｜分批发运通过"一张采购明细可建多个直发 ASN"实现**，不新增独立"发运单"实体。
- 每次 ASN 建单消费指定数量的"已收货未发运"额度；`qtyShipped ≤ 已收 qty − 已建直发 ASN 已发 qty`（防重复/超发）。
- 头程费用通过已有 `poIds` 关联采购单，与 ASN 的关联保持 `asn_id` 静态挂接（不强求三方一体）。

**D3｜直发 ASN 的父批次是"成本载体"，不产生任何可用库存**。
- seal 时在 **FBN 仓**建直发父批次（`status=CLOSED`、`qty_remaining=0`、`source_type='DIRECT_PURCHASE'`、`pur_order_item_id` 带采购来源），随即走现有 `insertChildBatch` 拆成 `IN_TRANSIT` 子批次。
- 父批次不进四态台账（台账只由 seal/receive 的子批次双边变更驱动）；子批次 receive 后才 OPEN 有 remaining，FIFO 出库只认 OPEN+remaining>0，父批次 CLOSED 不参与 → 不会虚增可用库存。

**D4｜receive 完全复用现有逻辑**：子批次 IN_TRANSIT→OPEN、FBN 台账 in_transit−/available+、按行分批收、短少记 ASN_SHORT。直发分支只影响 create/seal 的"源"来源，不改 receive 守恒。

**D5｜站点与授权**：全程 `SiteAuthGuard`；FBN 仓按 code+site 解析且 is_fbn；采购明细/收货/父批次同站校验，禁止跨站。

## §2 数据模型改动

### 2.1 `log_asn` 增列（幂等 ALTER）
- `source_mode VARCHAR(24) NOT NULL DEFAULT 'LOCAL_BATCH' COMMENT 'LOCAL_BATCH/ DIRECT_PURCHASE'`
- CHECK (`source_mode IN ('LOCAL_BATCH','DIRECT_PURCHASE')`)

### 2.2 `log_asn_item` 增列
- `pur_order_item_id BIGINT COMMENT '直发来源采购明细 id；本地批次模式为 NULL'`
- `source_batch_no VARCHAR(64) COMMENT '直发父批次号（seal 时生成）；本地批次模式沿用 batch_no 为源批次号'`
  - 说明：为最小改动，**复用 `batch_no` 字段**承载"直发父批次号"（本地模式它已是源批次号），直发模式在 create 时写入 `DP:<poItemId>:<uuid8>`，seal 时据此建父批次。不新增 `source_batch_no` 列，减少迁移面。

### 2.3 `inv_batch.source_type` 扩枚举（注释/校验）
- 增 `DIRECT_PURCHASE`（直发父批次，CLOSED 成本载体）；现有 `PURCHASE/RETURN_RESTOCK/ADJUSTMENT/TRANSFER_SPLIT` 不变。

### 2.4 幂等表
- 复用 `noon_m4_asn_request`（create/seal/receive 已有 requestId 幂等），不新增幂等表。

## §3 后端改动（yxerp-noon，owner：豆包）

### 3.1 `AsnCreateInput` / `AsnCreateLine`
- 头新增 `sourceMode`（可空，默认 LOCAL_BATCH）。
- 行新增 `purOrderItemId`（直发必填，本地模式忽略）。
- **兼容**：保留旧调用（无 sourceMode/purOrderItemId → 走 LOCAL_BATCH），不破坏 M4/M5 既有测试与前端。

### 3.2 `AsnService.create()`
- 读取 sourceMode；`LOCAL_BATCH` 完全沿用现状（requireCreateLines + requireCreateSourceBatch）。
- `DIRECT_PURCHASE` 分支：
  1. 每行 `purOrderItemId` 必填、正整数；查 `pur_order_item` 存在 + `pur_order.site_code == siteCode`（跨站/不存在 400）。
  2. 查 `noon_m3_receipt` 该 order_item 累计收货量 `qtyReceived` 与权威成本 `unit_cost_cny`（分批收货取最新一笔或按 FIFO，见 §5 待定）；`qtyReceived > 0` 否则 400「采购明细尚未收货，无法直发」。
  3. 防超发：`qtyShipped ≤ qtyReceived − Σ(已建直发 ASN 对该 item 的 qty_shipped)`（查 `log_asn_item JOIN log_asn WHERE source_mode='DIRECT_PURCHASE' AND pur_order_item_id=? AND asn.status IN ('DRAFT','SEALED','RECEIVED')`）。超发 400。
  4. 生成"直发父批次号" `DP:<poItemId>:<uuid8>` 写入 `log_asn_item.batch_no`，同时写 `pur_order_item_id`。
  5. 指纹纳入 sourceMode + 各行 (purOrderItemId, qtyShipped)（本地模式指纹逐字节兼容旧版）。

### 3.3 `AsnService.seal()`
- `LOCAL_BATCH` 沿用现状（源批次 CAS 扣减 + 台账双边 + ASN_OUT）。
- `DIRECT_PURCHASE` 分支（每行）：
  1. 取 `log_asn_item.pur_order_item_id` → 查收货成本 `unit_cost_cny`（权威，同站已校验）。
  2. 在 FBN 仓建**直发父批次**：`batch_no = item.batchNo()`、`source_type='DIRECT_PURCHASE'`、`status='CLOSED'`、`qty_in=qtyShipped`、`qty_remaining=0`、`pur_order_item_id`、`received_at` 取收货时刻、`unit_cost/currency/fx_rate` 取收货记录；**不动四态台账**。
  3. 调 `insertChildBatch(item, 直发父批次, fbn, asnNo, now)` 拆 IN_TRANSIT 子批次（现有方法复用）。
  4. `applyLedger(sku, fbn, 0, +qtyShipped, "ASN 直发封单加 FBN 在途")`（只加在途，无源仓扣减）。
  5. **不写 ASN_OUT**（无源仓出库）；写审计标注 source_mode=DIRECT_PURCHASE。

### 3.4 `AsnService.receive()`
- 完全复用（D4），不改。

### 3.5 测试（owner：豆包）
- H2 `AsnServiceTest` 新增直发用例：建直发 ASN（父批次 CLOSED 不虚增可用）→ seal（FBN in_transit+）→ receive（available+，成本来自 receipt）→ 四态守恒。
- 新增边界：无收货不可直发、超发拒绝、跨站采购明细拒绝、重复 seal/receive 幂等、直发父批次不参与 FIFO。
- `MySqlAsnIT` 同步真库 DDL/迁移/断言。

## §4 前端（owner：WorkBuddy 集成）

- ASN 建单弹窗增加「来源模式」切换：本地仓批次 / 采购直发。
  - 直发模式：行字段由 `batchNo` 改为「采购明细」下拉（真实 `listOrder` → 选单 → 展开明细，显示 SKU/已收/已发/可发），`qtyShipped ≤ 可发` 前端预校验。
  - 本地模式保留原 batchNo 输入（不破坏已有路径）。
- 仓库管理：为 AE/SA 建 FBN 官方仓内部记录（数据初始化，页面加醒目标注「系统内部记录，非 Noon 平台真实仓/预约」）。
- 流程提示：采购单/头程费用/ASN 页顶部文案改为直发主路径说明。

## §5 待确认缺口（集中提出，不阻塞已定部分）

1. **分批收货的直发成本取值**：同一采购明细多次收货（unit_cost 可能不同）时，直发父批次取哪一笔成本？建议按收货时间 FIFO 消费、每次建单锁定对应 receipt 行（需新增 `asn_item.receipt_id` 才能严格绑定）。本契约 v1 暂取该 item 收货记录中**最早未耗尽**的一笔（D1 可追溯），严格绑定留待确认。
2. **直发时"采购收货登记"由谁触发**：直发无国内仓收货动作，采购单 qty_received 由谁登记？现有「采购单收货记录」页面可继续用于"供应商交付确认"（仅记成本/数量，不建本地库存）。若业务要求 FBN 实收后才登记采购收货，则成本来源需后移——**该点需用户/Codex 拍板**，v1 按"先登记采购收货再直发"实现。
3. **Noon 平台自动预约/接口**：未验证，当前 FBN 仓与 ASN 均为人工登记系统内部记录，不声称平台已建仓/已预约。
4. **期间/分摊标记**：按用户指令搁置，不接财务计算。

## §6 验收清单（2026-11-23 已全部验证，详见 e2e-report.md）

- [x] AE/SA FBN 内部仓记录存在（Active、is_fbn=1）——API 建 FBN-AE-OFFICIAL/FBN-SA-OFFICIAL
- [x] 直发 ASN：无本地仓批次即可建单，成本来自采购收货（unit_cost_cny=20）
- [x] seal：FBN 在途+（in_transit=6），无源仓扣减，父批次 CLOSED 不虚增可用（available=0）
- [x] receive：FBN 可用+（available=6），短少记账（ASN_SHORT 20.0000），未实收前 available=0
- [x] 分批发运（多 ASN）不超发（可发4发5拒绝）、防重复请求（seal 重放 replayed=true）
- [x] 跨站/越权被 400（SA 关联 AE 采购明细拒绝）
- [x] 本地仓路径回归不破坏（全量 458 测试全绿，含 AsnServiceTest 17/17）
- [x] MySQL 8.0.35 开发库真库验证关键路径（真实单据，非夹具）

**新增待拍板缺口**（见协作文档 2026-11-23 回执 §四）：分批收货严格 FIFO 绑定（需 asn_item.receipt_id）、多币种采购直发（当前 CNY/fx=1）。
