# M10 · 已有 FBN 库存报表时点核实（只读）

日期：2026-11-23  
范围：仅使用已取得的 AE FBN 月度报表 `fbn_summary_ae.csv`、已有导出任务状态查询与官方文档；未新建报表任务、未调用平台写接口、未改数据。  
脱敏说明：不含凭据、Cookie、Token 或客户信息。

---

## 结论速览

| 核实项 | 结论 |
|---|---|
| `2026-11-30` 含义 | **会计期间月末标签**，代表"11 月期间"；报表生成于 11-23，是**当期进行中快照**，不是未来数据 |
| 数据生成时间 | 导出任务 `created_at` 为 UTC（实测 2026-11-23T07:28:27Z ≈ 北京 15:28）；本地文件 mtime 15:31。报表**没有单独的"数据截止时间"列**，时点由 `date`（月末标签）表达 |
| 库存状态口径 | `SALEABLE` / `UNSALEABLE` / `transit` 三类；`closing = opening + 12 个变动列` 在 266/270 行成立 |
| 较新可比较报表 | 11-30 就是当前最新一张（11-23 生成）。没有发现更近的、已存在的、且口径可比的现成报表 |
| ASN 编号 | 已有库存报表的 21 列中**无任何 ASN/GRN/单据编号列**；入库/GRN 明细需 Seller Lab UI 导出（写操作），本次未执行，不可得 |

---

## 一、`date` 列与 11-30 的含义

报表共 10 个期间，全部是各月最后一天：

```text
2026-02-28, 03-31, 04-30, 05-31, 06-30, 07-31, 08-31, 09-30, 10-31, 11-30
```

### 证据 1：期间结转链

对同 `(partner_sku, inventory_condition)`，本期 `opening_quantity` 应等于上期 `closing_quantity`：

```text
验证结果：164/168 行成立（4 行例外为首次出现或月中调整）
样例 NM000028/SALEABLE：09-30 closing 23 → 10-31 opening 23 → closing 18 → 11-30 opening 18 → closing 12
样例 YXSS000195/SALEABLE：03-31 20 → 04-30 opening 20 → closing 15 → 05-31 opening 15 …
```

这说明每行是该 SKU 在**该会计期间**的进出存快照，`date` 是期间标签，不是"数据发生日"。

### 证据 2：11-30 行已有真实当期发生

```text
11-30 共 37 行、28 个 partner_sku；
其中 26 行 customer_outbound_quantity 非 0（当期已有出库动销）；
11-30 入库行 0，SALEABLE closing 合计 228
```

当前真实日期 2026-11-23，`11-30` 只是 11 月账期的月末标记；
报表内容截至生成时刻（11-23）已包含当期发生，属于**进行中的期间快照**。
之前 M9 报告标注"晚于当前日期、需确认"的疑点至此澄清，**不是未来数据、无需再向平台追问**。

---

## 二、数据生成时间

### 导出任务时间（只读状态查询）

对已有导出任务执行 `POST /impex/v1/export/status`（仅查状态，不新建任务）：

```text
订单导出 EXP4KIYNBQIN：created_at = 2026-11-23T07:28:27（UTC）
商品导出 EXP47KKL7LQS：created_at = 2026-11-23T07:26:32（UTC）
```

状态响应含 `created_at` / `updated_at`（导出任务自身的生成/完成时刻，UTC），
**没有单独的"报表数据截止时点"字段**。报表数据的时间语义由行内 `date`（期间月末）表达。

本地文件时间佐证：

```text
fbn_summary_ae.csv  修改时间 2026-11-23 15:31:57（= UTC 07:31，与导出任务时间吻合）
noon_orders.csv     修改时间 2026-11-23 15:28:47
```

### 对账时应使用的时间

并列展示时，官方侧应写：**期间标签 `2026-11-30` + 报表生成时间 `2026-11-23 15:31`（北京）**，
系统侧写实际取数时刻。两者时点不同属正常，差异不得直接认定为丢货。

---

## 三、库存状态口径

21 列：

```text
date, location, nfsku, sku, partner_sku, partner_barcode, inventory_condition,
opening_quantity, inbound_quantity, vendor_return_quantity, disposal_quantity,
customer_outbound_quantity, customer_return_quantity, transfer_in_quantity,
transfer_out_quantity, transit_quantity, lost_quantity, found_quantity,
damaged_quantity, other_quantity, closing_quantity
```

| 状态 | 含义 | 系统对应 |
|---|---|---|
| `SALEABLE` | 可售 | `inv_stock_ledger.qty_available` |
| `UNSALEABLE` | 不可售 | `qty_unsellable` |
| `transit` | 在途 | `qty_in_transit`（语义接近，需逐单核对） |

勾稽验证：

```text
closing_quantity = opening_quantity + 12 个变动列之和 → 266/270 行成立
4 行例外集中在 03/05 月的 SSM 系列（月中调整/口径变更），不影响整体口径判断
```

注意：官方报表**没有** `reserved`（锁定）列，系统 `qty_reserved` 无官方对照；
`location` 只有国家码 `AE`，无具体仓库，系统按 `warehouse_id` 分仓，只能按站点汇总比较。

---

## 四、是否有较新且可比的现成报表

- 已取得的 AE 报表最新一张即 `date=2026-11-30`（生成于 11-23 15:31），是**当前最新**。
- 之前试过 `noon_catalog_catalogexport`（AE），任务完成但 CSV 只有表头 0 行，不可比。
- 导出类别中还有 `fbn_inventory_fbninventorydetailreport`（参数 `close_date` + `country`）和
  `fbn_inventoryv2_ledgerdetailedview`（可按 `partner_sku` / `sku` / `transaction_type` 过滤），
  粒度更细，但**尚未生成**，本次按只读约束未创建任务，故没有现成数据可比。

---

## 五、已有入库/GRN 报表能否读到 ASN 编号

| 数据源 | 是否含 ASN 编号 |
|---|---|
| 已下载 `fbn_summary_ae.csv`（21 列） | ❌ 无任何 ASN/GRN/单据编号列 |
| 官方 GRN 报表字段（文档） | ❌ psku_code/sku/qty/cubic_feet/storage_type_code/pbarcode_code/product_fulltype_code/brand_code/cluster_code，无 ASN 编号 |
| Seller Lab 的 ASN 详情页 | ✅ 可查看平台 ASN 编号与状态，但需 UI 操作；导出需 `Export ASN Reports → ASN Scheduled Quantity`（平台写操作） |

**结论：现有已取得的数据无法读取 ASN 编号及明细。**
若要取得，需在 Seller Lab 生成 ASN 报表（写操作）——本次未执行，等待用户明确授权。

---

## 六、对 M9 结论的修正

M9 报告曾写"最新快照标注 2026-11-30，晚于当前日期，须向平台确认该日期含义"。
本次核实确认：这是**会计期间月末标签 + 进行中的当期快照**，不是未来数据，
**该疑点关闭，无需再向平台确认**。其余结论（49 个 SKU 系统无台账记录、口径不一致、不可覆盖系统账）保持不变。

未连接生产、未部署、未推送、未改业务数据。请 Codex 复核。
