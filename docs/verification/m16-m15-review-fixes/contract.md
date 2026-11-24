# M16 · M15 复核修复 + ASN 接入交付

日期：2026-11-24  
范围：修复 Codex 对 M15 的 4 项复核问题；按用户新优先级接入 ASN 只读同步；核查销售数据入口；修复父菜单初始化。  
边界：平台严格只读，不自动关联 ERP 内部 ASN、不生成入库、不过账、不重复扣库存。

---

## 一、四项复核问题修复

### 1. 站点授权固定 AE（已修）

`NoonSellerLabController.syncFbn/syncSales` 由 `guard.requireSite("AE")` 改为
`guard.requireSite(NoonSellerLabClient.requireSite(siteCode))`——按请求站点授权。
compare/listSales 服务层本就按实际站点校验。这样仅 SA 授权的普通用户可以同步 SA，
不会因多余的 AE 检查被拒。白名单仍只接受 AE/SA（平台 `xx` 会回退 SA，ERP 侧拒绝）。

### 2. 库存对比口径误导（已修）

原实现按"站点+SKU"关联台账，多个系统仓会复制官方行，且本地仓参与相减。改为：

- 官方快照按 `partner_sku` 汇总各仓（`SUM` + `COUNT DISTINCT warehouse_code`）；
- 系统侧只取 `is_fbn=1` 的仓库（JOIN `md_warehouse`）按 SKU 汇总，**本地仓不计入**；
- 系统无 FBN 台账记录或 SKU 未维护 → `systemAvailable=null`，差异列显示"不可比较"，
  note 明确"不可比较、差异不代表丢货"，**不做差**。

单测覆盖"同 SKU 有 FBN 仓 8 件 + 本地仓 99 件"，结果只取 8，本地仓未参与。

### 3. Sales 分页截断与日期（已修）

- `NoonSellerLabClient` 增加 `lastSalesTruncated`：达到 `maxPages` 且末页满 20 条时为 true；
- 结果返回 `truncated` 字段，前端提示"已达页数上限，可能只同步了部分，可增大页数继续"；
- 拉取页数 `maxPages` 前端可调（1–100，原固定 5）；
- 列表 `getList()` 传 `fromDate/toDate`，查询与拉取使用同一日期区间，不再误以为无条件全量；
- 后端 `listSales` 支持可选日期参数（`order_ts` 过滤）。

### 4. 空响应/异常分页（已修）

- `send()` 对**空响应体**直接抛异常，不视为空结果；
- FBN：`data.fbn_inventory` 必须是数组且 `pagination.total_pages/page` 存在，否则抛异常；
  返回页码 ≠ 请求页码直接抛异常（停止，避免留下不完整快照）；
- Sales：`hits` 必须是数组且 `total` 存在，否则抛异常；
- ASN：`shipments` 必须是数组，否则抛异常。

合法空结果（结构完整、数组为空）仍正常返回 0 条，与异常明确区分。

---

## 二、ASN 接入（用户新优先级）

按用户"先同步 ASN、再做销售转换"的优先级，新增：

| 组件 | 内容 |
|---|---|
| `NoonSellerLabClient.listAsn` | `GET shipment/list?country_code=&page_size=20&next_token=`，next_token 分页，站点头 |
| `PlatformAsnRow` | asn_nr/status/dst_warehouse_code/expected_qty/qty_received/qty_putaway/updated_at/items 原文 |
| `noon_asn_platform` 表 | 唯一键 `(site_code, platform_asn_nr)`，支持重复 upsert 更新 |
| Controller | `POST /noon/sellerlab/asn/sync`、`GET /asn/list` |
| 定时任务 | `noonSellerLabTask.syncAsnAe()/syncAsnSa()`（默认不自动高频） |
| 前端 | 「Noon官方数据 → 平台ASN快照」页，ERP 内部 ASN 列固定显示"未关联" |

**平台 ASN 编号与 ERP 内部 ASN 编号分字段保存、分开展示**，当前不自动关联、不生成入库、不过账；
无法确认的关系明确标注。

真库验证：AE 22 条、SA 12 条（与 M12 观察一致）；重复同步仍 22/12；列表显示平台编号与仓库；
非法站点 XX 被 400 拒绝。

---

## 三、销售数据入口核查（回答用户疑问）

用户问"点击销售订单没数据，同步的 40 条在哪"。核查当前开发库：

| 项 | 数量 |
|---|---|
| `noon_sales_item`（平台明细快照） | 40 |
| `sal_order`（ERP 正式销售订单表） | **0** |
| `noon_asn_platform`（平台 ASN 快照） | 34 |

**结论**：M15 同步写入的是独立只读快照表，不会自动进入 ERP 销售订单业务表或原"销售订单"页面。
可用入口是左侧菜单 **「Noon官方数据 → Sales销售明细」**（menu 3911），不是「订单退货 → 销售订单」。
这是刻意设计：平台明细 `item_nr` 只是明细标识（非整单号），且无 order_nr，
直接当已入账订单会造成重复扣库存/成本。

后续"平台销售明细转 ERP 正式业务记录"属独立步骤，需关联 item_nr、站点、商品，
按真实履约状态幂等处理，用户明确本次不以其阻塞 ASN 拉取。

---

## 四、父菜单缺失根因与修复

Codex 发现开发库只有 3910/3911 而缺 3900 父菜单。根因：`noon_sellerlab_menu.sql` 最初把
父菜单 CREATE 与 role 授权混在一段，某些环境只执行了后续 INSERT（子菜单被建）而父菜单缺失。
修复：脚本改为**父菜单先建 + 末尾"自愈"INSERT**（当子菜单存在但父菜单缺失时补建），
role 授权移到最后。真库已补 3900/3912，现 3900/3910/3911/3912 四条均存在、启用、可见。

---

## 五、验证汇总

- 全模块单测：**249 用例 0 失败**（新增本地仓不参与对比、FBN 口径、站点白名单覆盖）。
- 前端 build：通过（含新 ASN 页面）。
- 真库端到端：ASN AE22/SA12 + 幂等；compare FBN 口径显示"不可比较"；非法站点拒绝；菜单完整。
- 全程约 3 秒统一限速、平台只读，未修改平台数据，未输出凭据/客户明细。

## 六、未覆盖

- 仅 AE/SA 授权用户的抽测（本次以超管验证接口逻辑，权限分支由单测覆盖站点闸门）。
- Sales 页结果漂移的长期稳定性；FBN 官方与系统仓库码的逐仓映射（当前按站点+SKU 汇总）。
- 非公开接口的正式支持/SLA/授权条款；多实例共享限速。
- 平台销售明细转 ERP 正式订单：未实现（后续步骤）。

未连接生产、未部署、未推送。请 Codex 复核。
