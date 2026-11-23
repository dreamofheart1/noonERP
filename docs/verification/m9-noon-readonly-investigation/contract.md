# M9 · Noon 只读可行性调查（ASN / FBN 库存对账 / 订单 / FBN 订单接口）

日期：2026-11-23  
范围：严格只读调查。未创建平台 ASN、未生成新报表任务、未修改店铺数据、未改业务代码。  
说明：本报告不含任何凭据、Cookie、Token 或订单客户敏感信息；字段与数量均为脱敏后的结构/计数。

---

## 结论速览

| 调查项 | 结论 |
|---|---|
| 平台 ASN 信息 | **现有 API 读不到**。导出类别清单无 ASN/GRN 类目，FBPI 12 端点无 ASN，事件无 ASN。只能 Seller Lab UI 查看/人工导出，需用户授权后再谈导入 |
| FBN 官方库存对账 | **报表数据可用、当前无法做有意义的差异对比**：官方 49 个商品在系统台账中均无库存记录（未发生入库业务）。口径/时点/仓库粒度也不一致 |
| 订单同步 | **导出通道可行**：已拿到 1162 行真实订单，字段足够；但 60 个订单 SKU 中 11 个在系统无映射，需补商品数据源 |
| FBN 订单 API/事件 | **未发现 FBN 专用订单接口或事件**。FBPI 接口是卖家自履约(FBP)订单，事件实测仅 3 种且无 FBN 订单事件 |

---

## 一、Noon ASN 信息只读可行性

### 官方文档核实

Seller Lab 路径：`Fulfilled by noon → My ASN & Storage`，五个 Tab：
Shipments / Storage / Requests / Product Dimensions / Serialization Service。
Shipments 支持按状态筛选、按创建时间排序、搜索指定 ASN。

平台 ASN 状态（10 种）：

```text
Created → Sealed → Scheduled → Pending → Handed Over → Receiving
→ Putaway in Progress → GRN Completed
另有 Cancelled / Expired（Sealed 60 天未预约、Scheduled 超期 2 天）
```

GRN 获取方式（官方文档）：筛选 `GRN Completed` → View → **Export ASN Reports** → **ASN Scheduled Quantity**。

GRN 报表字段（官方原文列名）：

```text
psku_code, sku, qty, cubic_feet, storage_type_code,
pbarcode_code, product_fulltype_code, brand_code, cluster_code
```

**注意：GRN 报表字段里没有 ASN 编号**。它只能作为"收货数量"明细，
不能独立提供平台 ASN 编号 ↔ 系统 ASN 的映射。

### 账号/API 核实

| 通道 | 结果 |
|---|---|
| `/impex/v1/export/category/list`（实测 11 类） | 无 ASN / Inbound / GRN 导出类别 |
| FBPI 12 个端点 | CreateSandboxOrder / GetFbpiOrder / ListFbpiOrders / UpdateOrder / CreateShipment / CancelShipment / GetShipment / AddShipmentCourierAwbs / GetNoonLogisticsAwbs / UploadInvoice 等，**无 ASN 相关** |
| `GET /event-notifications/v1/event-type/list`（实测） | 仅 `FBPI::ORDER_SYNC`、`FBPO::PO_SYNC`、`RETURNS::REFERENCE_UPDATE`，**无 ASN 事件** |

### 系统侧映射障碍

- `log_asn` 只有 `asn_no`（本地生成，如 `ASN:AE:XXXXXXXX`），**没有平台 ASN 编号字段**；
  `raw_payload` 列存在但当前 ASN 服务未使用。
- 系统内部 FBN 仓码为 `FBN-AE-OFFICIAL`，平台订单导出里的仓库码形如 `W00816026AE`，
  **两边仓码体系不同**，若导入需额外映射。
- 平台 ASN 编号格式尚未取得样本，无法确认与系统单号的对应关系。

### 结论与后续（需授权）

现有凭据/API **无法读取平台 ASN 信息**。可行路径只有：
Seller Lab 人工查看/导出 GRN 报表 → 导入 ERP。但"生成报表"是平台写操作，
本次未执行；如需推进，请先确认是否授权生成报表，并明确需要哪些 ASN 状态区间。
若要做映射，还需新增 `platform_asn_no` 字段与仓码映射表（属实现，不在本次只读范围）。

---

## 二、FBN 官方库存对账与盘点

### 报表可用性（已下载样本）

导出类别 `fbn_inventoryv2_ledgersummaryview`，参数 `country_code=AE`，样本：

```text
270 行；10 个月度快照 2026-02-28 ~ 2026-11-30；location=AE；
49 个唯一 partner_sku；
inventory_condition = SALEABLE(209) / UNSALEABLE(60) / transit(1)；
含 opening/inbound/outbound/.../closing_quantity
```

另有 `fbn_inventory_fbninventorydetailreport`（参数 `close_date` + `country`）与
`fbn_inventoryv2_ledgerdetailedview`（可按 `partner_sku` / `sku` / `transaction_type` 过滤）
理论上更细，本次未生成新报表，未验证字段。

### 口径与可比性

| 维度 | 官方报表 | 系统台账 | 可比性 |
|---|---|---|---|
| 商品键 | `partner_sku` / `sku` | `sku_id`（经 `md_partner_sku` 映射） | 49 个 partner_sku 均能映射到系统 SKU，**映射路径可用** |
| 可用 | `SALEABLE.closing_quantity` | `inv_stock_ledger.qty_available` | 语义接近，可对比 |
| 不可售 | `UNSALEABLE.closing_quantity` | `qty_unsellable` | 语义接近，可对比 |
| 在途 | `transit.closing_quantity` | `qty_in_transit` | 语义接近，但系统含 ASN 发运口径，需逐单核对 |
| 锁定 | 报表无对应列 | `qty_reserved` | **官方无源字段，不可比** |
| 仓库 | `location` 仅为国家码 `AE` | 按 `warehouse_id` 分仓 | **粒度不一致，只能按站点汇总** |
| 时点 | 月末快照 | 实时台账 | **时点不一致**；最新快照标注 `2026-11-30`，晚于当前日期，须向平台确认该日期含义 |

### 简单数量对比的实际结果（用户优先项）

```text
官方报表 2026-11-30 SALEABLE 27 行，closing_quantity 合计 228
系统台账当前仅 3 行，且均为演示 SKU(sku_id=1,2)；
49 个同步商品(sku_id=5..53)在 inv_stock_ledger 中均无任何记录
```

因此**当前无法给出有意义的"官方 vs 系统"差异数值**。
这批商品在系统中还没有发生采购入库/直发收货业务，官方有库存而系统未建账，
属于业务时序差异，**不能认定为丢货**，也不能用官方数量反向覆盖系统账。

### 后续方案（不在本次实现）

按用户优先级，先做简单数量对比：
1. 以 `partner_sku → md_partner_sku → sku_id` 为桥，按 `site + sku` 汇总双方数量；
2. 并列展示官方数量、系统数量、差异、**官方数据日期**与系统取数时间；
3. 对"系统无台账记录"单独标注，差异原因留空待人工确认；
4. 保留历史快照与明细追溯；不自动调整系统账。

---

## 三、Noon 订单同步可行性

### 现有通道（已验证，非本次新建）

导出类别 `noon_noonoms_ordersexport`，参数 `from_date` / `to_date`，已下载样本：

```text
1162 明细行 / 1034 个订单；
字段：id_partner, partner_name, market_place, order_nr,
      market_place_country_code, destination_country_code, item_nr, sku,
      warehouse_code, order_placed_at, min/max_expected_delivery_at,
      delivered_at, item_status, shipment_nr, awb_nr, is_fulfilled_by_noon
```

### 数据特征（脱敏统计）

| 项目 | 结果 |
|---|---|
| 站点覆盖 | AE 691 行 / SA 471 行（按 `market_place_country_code`） |
| FBN 覆盖 | `is_fulfilled_by_noon=true` 1160 行 / false 2 行 |
| 状态 | delivered 1096 / cancelled 38 / undeliverable 18 / shipped 8 / exported 2 |
| 时间跨度 | 2026-03-06 ~ 2026-11-23（UTC） |
| 平台标识 | `order_nr`（订单）+ `item_nr`（明细，形如订单号-序号）唯一定位一行 |
| 履约信息 | `warehouse_code`、`shipment_nr`、`awb_nr` 可用于物流追溯 |
| 分页 | 导出一次性给全量；大量数据可按日期分段，无 next_token 机制 |

### 幂等与去重建议

- 唯一键建议：`(site_code, order_nr, item_nr)`；`item_nr` 已含站点前缀（AEI/SEI），仍建议显式带站点防止跨站混淆。
- 重复导入：按唯一键 upsert；`item_status` 为快照状态，需用"最后更新时间/导入批次"判断新旧，避免旧状态覆盖新状态。
- 客户信息：该导出**不含买家姓名/地址/电话**，无需处理客户敏感数据。

### 关键障碍

订单中出现 60 个唯一 `sku`，其中 **49 个能映射到系统 `md_partner_sku.noon_sku`，11 个未匹配**
（均为 Z 开头且不在 FBN 台账中的商品）。这与 M7 已记录的"同步清单只覆盖 FBN 台账商品"
是同一边界：要把这 11 个商品映射进系统，需要补充商品数据源（目录导出正确参数或 Seller Lab 导出）。

### 结论

订单导出通道**可行且字段充足**；自动获取可用 Impex 导出（生成导出任务属平台写操作，
本次未执行）。尚未实现订单同步，未虚称完成。

---

## 四、FBN 订单 API 与事件核查

### 实测事件类型

`GET /event-notifications/v1/event-type/list` 返回：

```json
{"event_types":["FBPI::ORDER_SYNC","FBPO::PO_SYNC","RETURNS::REFERENCE_UPDATE"]}
```

与用户在 Developer Portal 看到的一致。**没有 FBN 订单事件、没有 ASN 事件、没有库存事件。**

### API 域核查

| 域 | 用途 | 是否 FBN 销售订单 |
|---|---|---|
| FBPI（12 端点） | 卖家自履约订单：`ListFbpiOrders` / `GetFbpiOrder` / UpdateOrder / CreateShipment 等 | ❌ 是 FBP 自履约订单，不是 FBN |
| FBPO（1 端点） | 采购单（B2B2C） | ❌ 是采购侧，不是销售订单 |
| Impex `noon_noonoms_ordersexport` | 全渠道订单导出，含 `is_fulfilled_by_noon` | ✅ 可读取 FBN 订单，但按导出而非 API/Webhook |

FBPI `ListFbpiOrders` 细节：`POST /fbpi/v1/fbpi-orders/list`，必填 `warehouse_code`（integration warehouse），
可选 `created_after/before`，`next_token` 分页、每页 50；明细含 `partner_sku`、`mp_item_nr`、
`mp_status`（仅 confirmed/cancelled）、`integration_status`（acknowledged/out_of_stock/shipped）。
**状态粒度不足以表达 FBN 订单的 delivered/exported/undeliverable 等状态。**

### 结论

不能因为"没有 FBN webhook"就断言不存在其他接口；基于当前官方文档与账号实测：
**未发现 FBN 专用订单 API 或事件**。FBN 订单的可靠来源仍是订单导出中的
`is_fulfilled_by_noon=true` 行。若要实时化，需要平台提供相应接口/事件后才可能实现。

---

## 五、需要用户授权的事项（本次均未执行）

1. 在 Seller Lab 生成 GRN / FBN Received Report（平台写操作），以确定平台 ASN 编号与字段样本。
2. 生成新的 FBN 库存明细/订单导出（平台写操作）以细化仓库与在途口径。
3. 若确认推进实现：新增 `platform_asn_no`、仓码映射、订单幂等表与导入任务。

未连接生产、未部署、未推送、未改业务数据。请 Codex 复核结论与边界表述。
