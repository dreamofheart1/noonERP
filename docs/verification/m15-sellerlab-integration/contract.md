# M15 · Seller Lab 三接口接入 ERP 实施契约（交豆包实施）

日期：2026-11-24  
目标：把已验证的 FBN 库存、Sales 查询接入 ERP；把 3 秒限速接入**所有实际 Noon 请求路径**。  
**范围调整（用户 2026-11-24 00:55）：ASN 暂不接入 ERP。ASN 接口验证结论保留在本文档与 M12/M14 中，本次不实现 ASN 服务/表/页面/任务。**  
约束：严格只读平台；不覆盖 ERP 账目、不自动过账；不修改平台数据；不输出凭据/Cookie/客户敏感信息。

---

## 0. 全局硬约束

1. **限速必须收敛到实际请求点**：当前所有 HTTP 调用集中在
   `yxerp-noon/.../sync/NoonApiClient.java` 三处：`call()`、`download()`、`login()`。
   必须让这三个方法都经过同一个 `NoonRequestThrottle` 实例（构造函数注入单例，禁止每请求 new）。
   - 间隔 3s（用户保守约定，非官方阈值）；429/503 有界退避；不无限重试；并发不绕过。
   - 多实例不共享，文档/页面需标注"单进程内限速"。
2. **站点隔离**：AE/SA 由站点头或查询参数决定，服务端闸门 `SiteAuthGuard.requireSite` 必须校验；
   未知 `Country-Code`（如 xx）平台会回退 SA，ERP 侧必须自行白名单限定 AE/SA。
3. **状态与来源**：每条同步数据保留 `source=NOON_SELLERLAB`、`site_code`、平台编号、更新时间；
   失败/未同步必须可见，**空结果或失败不得当作 0**，不得覆盖 ERP 既有账目。
4. **非公开接口**：页面/接口注释明确"非公开内部接口、正式支持未确认、未同步/失败状态"。

---

## 1. 三个接口（已验证参数）

### A. FBN 库存
- `POST https://fbn.noon.partners/_vs/fn/sc-fbn-platform-api-dashboard/api/v5/seller-lab/fbn-inventory`
- 头：`Country-Code: ae|sa`、`X-Locale: en-ae|en-sa`
- Body：`{"inventory_tab_name":"all","filters":{},"pagination":{"page":N,"items_per_page":20}}`
- 验证结果：AE 19 行 1 页（AUH/SHJ）；SA 20+1 行 2 页（RUH/ OTHER），两页去重 21 条。
- 关键字段：`partner_sku, sku, warehouse_code, saleable, non_saleable, qty, title, image` 等。
- **只做简单数量对比**，口径/仓库映射不一致明确标注，不认定为丢货、不覆盖系统账。

### B. ASN（⏸ 暂缓，用户 00:55 明确本次不接入 ERP）
- `GET https://fbn-inbound.noon.partners/_vs/sc/sc-inbound-api-partner/v1/shipment/list?country_code=ae|sa&page_size=20&next_token=...`
- 验证结果：AE total22 / SA total12；next_token 分页两页无重复。
- 关键字段：`asn_nr`（平台编号）、`status`、`country_code`、`dst_warehouse_code`、
  `expected_qty`、`qty_received`、`qty_putaway`、`updated_at`、`items`。
- **本次不实现**：不建 `noon_asn_platform` 表、不写服务/Controller/任务/前端页面。
- 保留结论供后续：平台 ASN 编号与 ERP 系统 ASN 编号必须分字段保存、分开展示，不得混同。

### C. Sales 销售明细
- `POST https://reports.noon.partners/_vs/mp/mp-inventory-health-api-sales-dashboard/sales-dashboard/list`
- Body：`country_code`、`from_date/to_date`、`page`、`per_page`、`filters`
- 字段：`item_nr, partner_sku, sku, status, fulfillment_model, country_code,
  order_timestamp, shipment_timestamp, delivered_timestamp, offer_price, currency_code, awb_nr`；**无 order_nr**。
- 列表用 `item_nr` 作显示编号（用户决定），保留"仅为平台明细标识"说明。
- 幂等键：`(country_code, item_nr)`；重复同步做 upsert，处理状态更新与记录消失；
  按 3s 限速 + 去重/补扫，不假设按页遍历稳定（M12 页结果变化原因未确认）。

---

## 2. 建议落库（新表，幂等 DDL）

- `noon_fbn_inventory_snapshot(site_code, partner_sku, sku, warehouse_code, saleable, non_saleable, qty, source, captured_at, synced_at)`，唯一键 `(site_code, partner_sku, warehouse_code, captured_at)`
- ~~`noon_asn_platform(...)`~~ ⏸ **本次不建表**（ASN 暂缓，用户 00:55）
- `noon_sales_item(site_code, item_nr, partner_sku, sku, status, fulfillment_model, order_ts, shipment_ts, delivered_ts, price, currency, awb_nr, source, synced_at)`，唯一键 `(site_code, item_nr)`
- 同步日志表（可选）：记录每次 trigger/site/成功/失败/条数/耗时，失败可见。

所有表带 `source` 与 `synced_at`，禁止 UPDATE/DELETE 覆盖 ERP 业务账表。

---

## 3. 服务与接口

- `NoonSellerLabClient`：封装三接口访问，统一走 `NoonRequestThrottle`；分页/重试/两站串行。
- `NoonSellerLabSyncService`：分页抓取 → 幂等 upsert → 返回统计（成功/失败/跳过/站点）。
- Controller：手动触发 + 查询列表接口，权限复用现有 noon 模块权限点；
  服务端 `SiteAuthGuard` 校验站点，禁止前端传站点绕过。
- 后台任务：按若依 Quartz 方式注册（bean 名如 `noonSellerLabSyncTask`），默认**不自动高频执行**，
  cron 由用户在系统管理里自行启用；任务内同样受 3s 限速。

---

## 4. 前端

- FBN 库存：展示官方 saleable/non_saleable/qty + 系统 qty_available/qty_unsellable + 差异 + 官方时间；
  口径/映射不一致或系统无记录时明确标注，不显示 0 冒充。
- ⏸ ASN 页面本次不实现（暂缓）。
- Sales：item_nr 作编号，站点/状态/履约模式/下单发运送达时间；标注非公开接口与同步状态。
- 顶部统一提示：非公开内部接口、单进程限速、未同步/失败不等于零。

---

## 5. 验收清单（必须逐项回执）

- [ ] 三个接口所有实际 HTTP 请求都经过同一个 throttle（代码定位到具体行）。
- [ ] 单测：限速间隔、并发不绕过、429/503 有界退避、非限流不重试。
- [ ] AE/SA 站点隔离实测；xx 站点被 ERP 侧拒绝。
- [ ] FBN 两站分页完整、跨页无重复；SA 第 2 页 1 条可见。
- [ ] ⏸ ASN 相关验收本次不适用（暂缓）。
- [ ] Sales 按 (site,item_nr) 幂等；重复同步不重复入账；状态更新生效；失败/空不当零。
- [ ] 不修改平台数据、不输出凭据/Cookie/客户明细。
- [ ] 端到端真库验证 + 单测 + 前端 build；文档与协作回执。
- [ ] 明确标注未覆盖：正式支持/SLA、单进程限速、页结果变化原因。

未授权事项：不连接生产、不部署、不推送（用户负责推送）。
