# Noon ERP · Codex 静态交叉评审核实记录 v1.0

| 项目 | 内容 |
| --- | --- |
| 文档编号 | `REVIEW-NOON-ERP-001` |
| 核实时间 | 2026-11-17 21:45–22:10 (GMT+8) |
| 评审来源 | Codex 静态交叉评审 6 条（用户转述，声明为待核实证据） |
| 核实范围 | `/Users/dream/Work/noonERP/` 下 PRD v1.0.2、schema-v0.3.sql、prototype/index.html、api-integration-v1.0.md、prototype-design-v1.0.md |
| 核实方法 | 逐条定位原文行号，对照实测原始响应与表结构；不默认接受，不扩大范围 |
| 结论 | **6 项全部成立**（第 1 项证据最硬：类别码凭空杜撰 8 个且标「可用」） |

---

## 1. P-34 导出类别码与实测原始响应不符 — ✅ 成立

**评审说法**：原型 P-34 标「12 个类别 M0 实测」，含 `noon_financeweb_statementreport` / `noon_fbn_asn_export` / `noon_returns_returnexport` 等，实测原始响应没有这些码。

**证据**：
- `prototype/index.html:933-946` 表格标题「12 个 export_category_code（M0 实测）」，12 行状态全为「可用」
- `docs/noon-erp-api-integration-v1.0.md:88-116` 保存的完整原始响应，12 个码为：
  `noon_catalog_catalogexport`、`noon_catalog_content_contentrejections`、`fbn_inventory_fbninventorydetailreport`、`noon_financeweb_invoicesandcreditnotes`、`noon_catalog_reports_productviewsandsalesdata`、`noon_catalog_globalcatalogexport`、`noon_financeweb_transactionviewreportonitemlevel`、`fbn_inventoryv2_ledgerdetailedview`、`fbn_inventoryv2_ledgersummaryview`、`fbn_inventoryv2_aging`、`noon_noonoms_ordersexport`、`namshi_financestatement`

**对照差异（原型 → 实测）**：

| 原型写的码 | 实测是否存在 | 实测正确码 |
| --- | --- | --- |
| `noon_financeweb_statementreport` | ❌ | 无（statement 无实时 REST 端点，见 INT §0） |
| `noon_fbn_asn_export` | ❌ | 无（FBN Inbound API 未公开） |
| `noon_returns_returnexport` | ❌ | 无 |
| `invoicesandcreditnotes` | ❌（缺前缀） | `noon_financeweb_invoicesandcreditnotes` |
| `fbn_inventoryv2_agedinventory` | ❌ | `fbn_inventoryv2_aging` |
| `fbn_inventoryv2_stockview` | ❌ | 无（近似为 `fbn_inventoryv2_ledgersummaryview`） |
| `noon_catalog_productexport` | ❌ | `noon_catalog_catalogexport` / `noon_catalog_globalcatalogexport` |
| `namshi_catalog_productexport` / `namshi_orders_export` | ❌ | `namshi_financestatement` |

**成立判定**：原型 12 行中 **8 行类别码不存在于原始响应**；且全部标「可用」，违反 INT §0/O-07「CreateExport 只读下未验证、按不可用处理」。**成立**。

**修订方向**：以原始响应为唯一来源重列 12 项；状态拆三层「类别已发现 / 创建权限已验证 / 文件下载与列头已验证」，第一层为唯一已达状态；`P-34` 的 `导出任务` 示例行里不存在的码一并替换。

---

## 2. 退货成本冲回、退款收入、跨期归属缺失 — ✅ 成立

**证据**：
- `docs/noon-erp-prd-v1.0.md:336` FR-04.2「妥投扣 FIFO」；`:363` FR-06.6「B/C 级损耗写 `inv_batch_consumption(move_type=RETURN_LOSS)`」；`:340` FR-04.6「A 级生成新批次」
- 三处均**没有**「冲回原 SALE 消耗流水」的规则，也没有 `refund_amount` 收入冲减、跨期归属
- `docs/noon-erp-schema-v0.3.sql:224-241` `inv_batch_consumption` 只有 `move_type=SALE/RETURN_LOSS/STOCKTAKE`，无 `reversal_of_consumption_id`、`period`、负债方向字段

**成立判定**：成本 100 售出扣 100，B/C 坏退再写 RETURN_LOSS 100 → COGS 200，重复确认。**成立**。

**修订方向**：新增「冲回 + 全额计损」规则：任何退货先冲回原 SALE 消耗层（负数红冲，指向 `reversal_of_id`），A 级按原价建新批次，B/C 级按**原采购层成本全额**记 RETURN_LOSS；退款冲减 revenue；赔偿只在 COMPENSATION 确认一次。

> **★ C-2b 二次核查补充（用户 2026-11-17 22:02）**：原修订写「B/C 级按**补偿后净损失**记 RETURN_LOSS」会与 `platform_fee.COMPENSATION` 双计赔偿 —— 已修正。统一验算：**成本100 / 售价150 / 全额退款150 / 赔偿30** → revenue=0、platform_fee=−30、cogs_fifo=100−100+100=100 → **L1=−70**（唯一正确）。双计会错成 −40；不红冲会错成 −200。PRD FR-04.6/FR-06.6、DDL v0.4 `sal_return.return_loss_amount` 与 prototype 演示数字均已按 −70 口径同步。

---

## 3. 利润快照无法并存三档与多次重算，L2 到 SKU 未定义 — ✅ 成立

**证据**：
- `docs/noon-erp-prd-v1.0.md:398` FR-08.3 要求三档并存；`:400` FR-08.5 要求保留新旧快照
- `docs/noon-erp-schema-v0.3.sql:550-567` `fin_profit_snapshot` 唯一键 `(period, sku_id, site_code, formula_version)`，**无 allocation_policy、无计算批次号、无输入版本**
- `fin_expense_pool` 按 period/site 归集（`schema:540-548`），PRD FR-08.1 只算 SKU 级 L1，没有说明 SKU 净利如何分摊站点费用池

**成立判定**：同一公式版本切 NONE/PARTIAL/FULL 会互相覆盖；同一版本重算两次也会覆盖；若把整池费用按 SKU 各扣一次则净利被重复扣减。**成立**。

**修订方向**：唯一键加 `allocation_policy` + `calc_batch_no`；新增 `calc_input_version / allocation_basis / allocated_qty_or_revenue / expense_pool_ref`；站点总利润用 `scope_type=SITE, sku_id=0` 行存；SKU 净利默认 NONE 只展示站点总额，PARTIAL/FULL 按可配置权重分配且所有 SKU 分配额合计 = 站点池（守恒校验）。

---

## 4. 订单重复导入被拒阻断状态更新、行级粒度不足 — ✅ 成立

**证据**：
- `docs/noon-erp-prd-v1.0.md:358` FR-06.1「重复导入同一订单被拒」；`:359` FR-06.2「仅 DELIVERED 触发 FIFO 扣减」
- 状态机 `IMPORTED → DELIVERED → RETURNED/CANCELLED` 需要多次导入/事件回写，现有规则一律拒绝
- `docs/noon-erp-schema-v0.3.sql:430-441` `sal_order_item` 只有 `qty / unit_price / cogs_amount`，**无平台行 ID、无 qty_delivered / qty_returned / qty_cancelled、无过账标记**

**成立判定**：先导入后补妥投会被唯一键挡住；同一订单部分妥投/部分退货无法表达。**成立**。

**修订方向**：区分「同一数据快照幂等 ACK」与「同一订单新状态更新」；唯一键改为内容指纹 + 版本，状态更新走状态机且 FIFO 只过账一次；行表加平台行 ID 与部分数量字段。

---

## 5. 单批次单仓无法表达拆批在途，调拨/ASN 过账责任未定 — ✅ 成立

**证据**：
- `docs/noon-erp-schema-v0.3.sql:203-222` `inv_batch` 只有单一 `warehouse_id`，无 parent/split 血缘
- `inv_transfer_item` (`:311-321`) 与 `log_asn_item` (`:368`) 都写 `inv_stock_move`，没有「唯一过账责任」定义
- PRD FR-04.5 调拨、FR-05.1 ASN 都回写库存，但没有说明谁扣 FIFO 批次、谁只动四态台账

**成立判定**：国内 100 件发 40 件（国内 60 / 在途 40 / 目的仓部分收货）无法在单批次单仓模型下表达并保住采购成本血缘；调拨与 ASN 双写可能重复扣减。**成立**。

**修订方向**：`inv_batch` 加 `parent_batch_id / split_seq / split_reason / transfer_no / asn_no` 血缘；明确过账责任矩阵（调拨/ASN 只动台账四态，FIFO COGS 只在销售妥投扣；收货只拆批/转移批次余额），补守恒数字案例。

---

## 6. 原型交互为 toast、详情固定、利润固定；缺联动演示 — ✅ 成立（须按「原型边界」表述）

**证据**：
- `prototype/index.html:1158-1168` 筛选/新建/导入/导出全部 `toast()`；`:1126-1137` `openDrawer(title)` 对任意行返回固定采购状态与固定字段；`:1044-1055` `profitSummaryHTML()` 写死 `l1=78210, pool=7716.58`
- `:1233-1235` 原型说明只说「数据为原型示意值」，没有明确哪些交互是模拟、哪些业务实际未完成

**成立判定**：作为原型可用模拟数据，但确实不能声称业务完成；缺端到端内存联动。**成立**。

**修订方向**：加可见「模拟边界」说明；筛选真实过滤表格数据，详情按点击行取数，利润从内存链计算；完成采购→部分到货→调拨/ASN→妥投→退货→对账→利润的一条内存联动演示；自行做浏览器视觉验收。

---

## 核实结论汇总

| # | 评审项 | 判定 | 处置 |
| --- | --- | --- | --- |
| 1 | 类别码与 O-07 状态 | ✅ 成立 | 以实测原始响应重写，三层状态 |
| 2 | 退货冲回与跨期 | ✅ 成立 | PRD + SQL + 原型补规则与案例 |
| 3 | 利润三档/重算/L2 分配 | ✅ 成立 | 快照加维度、加站点行与分配守恒 |
| 4 | 订单幂等/状态更新/行粒度 | ✅ 成立 | 幂等 vs 状态更新分流、行表补字段 |
| 5 | 拆批在途/过账责任 | ✅ 成立 | 批次血缘 + 过账责任矩阵 + 守恒案例 |
| 6 | 原型模拟边界与联动 | ✅ 成立 | 明确边界 + 真实筛选/详情/利润 + 联动演示 |

> 六项中没有任何一项被判定为「不成立」。但修订时保留既定范围：FBN 单模式、双站点、FIFO 采购成本、头程费用独立归集（Q8）、AI 人工复核闸门；不做生产写操作、不部署、不提交推送。

**文档结束 · REVIEW-NOON-ERP-001 v1.0**
