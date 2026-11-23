# M8 · SKU 真图与映射数量分布交付验证

日期：2026-11-23  
范围：SKU 管理与 Partner SKU 映射两个列表的真实首图；映射行展开查看站点数量分布

---

## 一、SKU 真实首图

### 数据来源（已用真实账号验证）

| 候选 | 结果 |
|---|---|
| Offer API `GET /offer/v1/product/{partner_sku}` | 返回 title/brand/offers，**无图片字段** |
| 商品目录导出 CSV | 表头有 `sku_child/family_code`，**无图片列** |
| FBN 台账导出 CSV | 无图片列 |
| `POST /content/v1/product/content/get` | ✅ 返回 `images[]{url,sort,visibility,review_status}` |

关键点：Content API 入参是 `sku_parent`，实测为 Noon SKU **去掉 `-数字` 变体后缀**。例如
`Z210B3A25FEF6EAAF129BZ-1 → Z210B3A25FEF6EAAF129BZ` 返回 200 并含 6 张图；
直接用 partner_sku（NM000003）或带后缀的 Noon SKU 都返回 400 `Invalid sku_parents`。

老式 `N` 开头 SKU（如 `N38692941A`、`N22490913A`）在 Content API 无记录（400），
这类商品**保持无图**，页面显示"暂无图片"，不伪造、不猜测。

### 实现

- `NoonApiClient.getPrimaryImage(noonSku)`：去 `-数字` 后缀 → 调 Content API → 取
  `visibility=VISIBLE` 且 `review_status=VALID` 中 `sort` 最小的 URL；无记录/无图/异常返回 null。
- `NoonOfferInfo` 增加 `imageUrl`；同步时随 Offer 一起回读。
- 新增列 `md_sku.image_url` 与 `md_partner_sku.image_url`，迁移脚本 `yx-erp/sql/noon_sku_image.sql`。
  - SKU 主数据存一份（跨站共享）；映射表存该站点对应的一份，避免站点差异被覆盖。
  - 平台无图时 `image_url=COALESCE(?, image_url)`，不清空历史真实图。
- `SkuRow`/`PartnerSkuRow` 及各自 Service 读模型扩展 `imageUrl`，经 list 接口返回。
- 两个列表新增"商品图"列：有图显示 56×56 缩略图（可点开大图），无图/加载失败显示"暂无图片"。

### 验证

```text
真库同步：49 清单 / 49 Offer / 0 失败；SKU 更新 43（有图），映射更新 49
图片覆盖：49 个同步商品中 43 个有真实 CDN 图，6 个 N 类旧 SKU 无图
图片样例：https://z.nooncdn.com/p/pzsku/Z210B3A25FEF6EAAF129BZ/45/_/1772884000/0817ce12-...jpg
CDN 直连：HTTP 200 / image/jpeg / 105557 bytes
接口返回：SKU 与映射 list 均含 imageUrl；YXSS000195（N 类）为 null → 页面显示暂无图片
单测：NoonSkuSyncTest 3/3（含无图不清空、有图回写）
前端构建：通过
```

---

## 二、Partner SKU 映射行展开数量分布

### 归属粒度核实（先核实、再实现）

系统内三处数量来源都只记录 `sku_id + site_code`，**不记录 md_partner_sku.id**：

| 来源 | 关键列 |
|---|---|
| 采购 | `pur_order_item.sku_id` + `pur_order.site_code` |
| ASN | `log_asn_item.sku_id` + `log_asn.site_code` |
| 库存台账 | `inv_stock_ledger.sku_id` + `warehouse_id`（仓库带 site_code / is_fbn） |

结论：同一站点多条 Partner SKU 映射到同一系统 SKU 时，**无法归属到单条映射**。
接口统一返回 `scopeNote` 明确标注"站点 + 系统 SKU 汇总，非本映射独有"，并给出
`sharedMappingCount`，**不臆造分摊**。

### 阶段口径（互斥，避免同批货重复计算）

| 阶段 | 真实来源 | 排除 |
|---|---|---|
| 采购中 | `pur_order_item.qty_ordered − qty_received` | 排除 status DRAFT/CLOSED |
| 头程在途 | `log_asn_item.qty_shipped − qty_received` | 仅 ASN SEALED；DRAFT 未发运、RECEIVED 已收完 |
| FBN 仓可用 | `inv_stock_ledger.qty_available` | is_fbn=1，系统内部台账 |
| 本地仓可用 | 同上 | is_fbn=0，不计入 FBN 阶段 |

**Noon 官方仓实时库存未接入**，作为 `pendingStages` 明确显示"待接入"，不以 0 冒充。
每阶段还返回 `docs[]`（采购单号 / ASN 号 / 仓库名 + 数量），可追溯到具体单据或仓库。

### 实现

- `PartnerSkuQuantityService.breakdown(mappingId)`：读映射真实站点 → `SiteAuthGuard.requireSite`
  服务端闸门 → 按 sku_id + site_code 查四阶段 → 返回 `PartnerSkuQuantityBreakdown`。
- 新增 `GET /noon/masterdata/partner-sku/{id}/quantity-breakdown`（复用 list 权限）。
- 前端映射列表加 `type="expand"` 展开行：口径提示 + 四阶段卡片（数量/来源/单据）+ 待接入说明；
  展开时才请求并缓存，列表刷新时重新加载。

### 验证

- 单测 `PartnerSkuQuantityTest` 3/3：
  - 共享映射（同站两条指向 SKU-A）`sharedMappingCount=2`，scopeNote 含"汇总/非本映射独有"；
  - 采购中 7（10−3，DRAFT/RECEIVED 不计）、在途 3（4−1）、FBN 6、本地 2；
  - SA 映射只见 SA 仓 5，不串 AE；仅 SA 授权用户访问 AE 映射抛 403；
  - `NOON_FBN_LIVE` 待接入原因含"未接入"。
- 真库接口 `GET .../partner-sku/2/quantity-breakdown` 返回 200，字段完整；
  该 AE 商品当前无采购/库存单据，四阶段均为 0（**有可靠来源、当前无记录**），
  同时明确列出"待接入"阶段，与"假称为零"区分。
- 全模块测试 241 用例 0 失败；前端构建通过。

---

## 三、边界

- 首图覆盖依赖 Noon Content API；无 content 的老 SKU 显示暂无图片，属正常业务状态。
- 数量分布为系统内部事件口径，不代表 Noon 平台实时库存；平台实时库存仍待接入。
- 展开数量按站点+系统 SKU 汇总，不可视为单条映射独有；如未来采购/库存能记录 partner_sku，
  需迁移到更细粒度后再调整标注。
- 未连接生产、未部署、未推送。
