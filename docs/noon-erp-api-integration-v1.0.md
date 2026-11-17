# Noon ERP — M0 接口联调实测记录与字段映射

| 项目 | 内容 |
| --- | --- |
| 文档编号 | `INT-NOON-ERP-001` |
| 版本 | v1.0 — **M0 联调实测基线** |
| 实测时间 | 2026-11-13 21:20–21:26 (GMT+8) |
| 环境 | 生产网关 `noon-api-gateway.noon.partners` + 静态沙箱 `noon-sandbox-api-gateway.noon.partners` |
| 项目 | `PRJ468049` |
| 权限 | **Service Account 已由用户设为只读**（2026-11-13 21:18）；本次**仅调用只读端点，未调用任何写端点** |
| 配套文档 | `docs/noon-erp-prd-v1.0.md`（v1.0.2）、`docs/noon-erp-schema-v0.3.sql` |
| 事实纪律 | 完整原始响应字段不删减；**私钥、JWT、cookie 值一律不写入本文档**；未实调的能力标为待验证，不臆造 |
| 探测方式 | Node.js 原生 `crypto` 构造 RS256 JWT + `fetch`，凭据仅在进程内存中使用，脚本置于 `/tmp`，未写入仓库 |

---

## 0. 结论速览

| 项 | 结论 | 状态 |
| --- | --- | --- |
| **R2 凭据与鉴权** | Login / whoami 均 HTTP 200，拿到 `x-request-id`；只读角色可正常登录与读取 | ✅ **关闭** |
| **R3 Stock API 是否适用 FBN 仓** | 实测 5 个仓全是 `directship_v2` / `fbpi`，**没有 FBN 仓类型** → `ListWarehouses` 管的是卖家自持仓，不是 noon FBN 仓；**MVP 不推送库存** | ✅ **关闭** |
| **R4 导出类别码** | 实调拿到 **12 个** `export_category_code`，含 FBN 库存台账、FBN 库存明细、**订单导出（可按 `is_fulfilled_by_noon` 筛）**、item 级交易明细、发票/贷项通知单 | ✅ **关闭** |
| **R5 事件类型** | 仅 **3 种**，`FBPI::ORDER_SYNC` / `FBPO::PO_SYNC` / `RETURNS::REFERENCE_UPDATE`，**无 FBN 订单事件**；已配 destination 为空 | ✅ **关闭** |

### 三个改变设计的发现

1. 🔴 **「Statement 无 API」的判断要改口径**：确实**没有实时 REST 结算端点**，但 impex 异步导出里有 `noon_financeweb_transactionviewreportonitemlevel`（item 级交易）、`noon_financeweb_invoicesandcreditnotes`、`noon_noonoms_ordersexport`（带 `is_fulfilled_by_noon`）→ **FBN 订单与交易明细有可能从「Seller Lab 手工导出」升级为「impex API 自动导出」**。列头与可用性未实调，见 O-07。
2. 🔴 **官方 MCP 只有 11 个只读工具、0 个写工具**：54 个 API Reference 端点中，`GetProductOffers` / `GetExportCategoryList` / impex / whoami / FBPI 订单 / Returns **全部 Not on MCP** → AI 外挂靠 MCP 拿不到 Offer 健康、报表、订单，主力仍必须是 ERP 的 `v_ai_*` 视图。好消息是**平台级零写工具**，只读边界比「只勾 Read」更硬。
3. 🟡 **只读权限会卡住 `CreateExport`**：创建导出任务是写语义且 Not on MCP，只读账号下能否调用**未验证**。若被拒，impex 自动导出通道需单独授权，A-01 的「人工录入 + 账单反推」默认值继续成立。

---

## 1. 联调方法与安全边界

| 项 | 做法 |
| --- | --- |
| JWT | 官方 `APILogin` 页 Python/Go/Java 示例：`header={alg:RS256,typ:JWT}`，`payload={sub:key_id, iat:<整秒>, jti:<UUID>}`，RS256 私钥签名 |
| Login | `POST https://noon-api-gateway.noon.partners/identity/public/v1/api/login`，body `{"token":<JWT>,"default_project_code":"PRJ468049"}` |
| 必带请求头 | `User-Agent: NoonERP-M0-Probe/1.0`（官方文档要求，不带会被拒）、`Accept: application/json`、POST 加 `Content-Type: application/json` |
| 会话 | 显式解析 `Set-Cookie` 并在后续请求带 `Cookie` 头；不持久化、不打印 cookie 值 |
| 调用范围 | Login、whoami、`ListWarehouses`、`GetExportCategoryList`、`ListEventTypes`、`ListDestinations`、沙箱 1 次；**全部为读或登录，无写端点** |
| 未做 | 未调 `CreateExport` / `UpdateStock` / `UpsertProduct` / `UpsertPricing` / `CreateHttpsDestination`；**未用写端点去「试探」权限是否被拒** |

> 只读权限由用户在 Portal 配置，本次以「全程无写调用」验证可用性，**未在真实数据上验证写操作被拒**；该项保留在 UAT-01 步 9，需在受控环境执行。

---

## 2. 鉴权实测（N-21）

| 步 | 端点 | HTTP | `x-request-id` | 关键返回 |
| --- | --- | --- | --- | --- |
| 1 | `POST /identity/public/v1/api/login` | 200 | `req_000_01a1d51282e67efeaf17eec65cedcafa` | `{}`；`Set-Cookie` 两个：`_npsid`、`_nprtnetid` |
| 2 | `GET /identity/v1/whoami` | 200 | `req_000_01a1d5128a357ab4823920e799c17d9e` | `user_code=prd-a0944c4b2e8e801398f7d2bda0@idp.noon.partners`，`username=test@p468049.idp.noon.partners` |
| 3 | 沙箱 `GET /event-notifications/v1/event-type/list` | 200 | `req_000_01a1d512940e7906a7ca287e4feb1159` | `{"event_types":[]}`；额外 `Set-Cookie: ak_bmsc` |

**N-21 结论**：
- 只读角色登录与读取完全正常；`x-request-id` 统一格式 `req_000_<32 hex>`，可直接用于 Support Case 与 `int_api_log.request_id`。
- `whoami.username` 与凭据 `channel_identifier` 完全一致 → 印证 N-19（`test` 是 username，不是环境标识）。
- **生产 login 的 cookie 可跨 host 用于沙箱**（HTTP 200，未 401），A-03「共用一份凭据 + `env` 换 host」实测成立。
- 但官方 `Static Sandbox` 文档明写「**No business validation**：inventory / pricing / **permissions** 校验跳过」「响应是 deterministic mock」→ **沙箱不能用来验证权限，也不能用来拿真实仓编码/类别码/事件类型**；R3/R4/R5 结论一律取自生产网关只读调用。

---

## 3. 端点实测原始响应（字段不删减）

### 3.1 `POST /warehouse-platform/v1/warehouses/list`（R3）

`x-request-id: req_000_01a1d5128c0b70599cceafbe7a958cfb` · HTTP 200 · 请求体 `{}`

```json
{
  "next_token": null,
  "warehouses": [
    { "display_name": "king01",   "fulfillment_system_code": "directship_v2", "is_active": false, "warehouse_code": "W00167913AE" },
    { "display_name": "king02",   "fulfillment_system_code": "directship_v2", "is_active": false, "warehouse_code": "W00167918AE" },
    { "display_name": "king03",   "fulfillment_system_code": "directship_v2", "is_active": true,  "warehouse_code": "W00167921AE" },
    { "display_name": "king",     "fulfillment_system_code": "fbpi",          "is_active": false, "warehouse_code": "W00167923AE" },
    { "display_name": "ZD-UAE01", "fulfillment_system_code": "directship_v2", "is_active": true,  "warehouse_code": "W00168495AE" }
  ]
}
```

### 3.2 `GET /impex/v1/export/category/list`（R4）

`x-request-id: req_000_01a1d5128fac7614870d3d4526eb900e` · HTTP 200 · 共 12 项

```json
{
  "export_categories": [
    { "export_category_code": "noon_catalog_catalogexport",
      "params": { "fields": { "brand_code": {"required": false}, "country": {"required": true}, "family_code": {"required": false}, "noon_status": {"required": true} } } },
    { "export_category_code": "noon_catalog_content_contentrejections", "params": { "fields": null } },
    { "export_category_code": "fbn_inventory_fbninventorydetailreport",
      "params": { "fields": { "close_date": {"required": true}, "country": {"required": true} } } },
    { "export_category_code": "noon_financeweb_invoicesandcreditnotes",
      "params": { "fields": { "contract_order_nrs": {"required": true}, "end_date": {"required": true}, "start_date": {"required": true}, "tax_types": {"required": false}, "transaction_types": {"required": false} } } },
    { "export_category_code": "noon_catalog_reports_productviewsandsalesdata",
      "params": { "fields": { "country": {"required": true}, "from_date": {"required": true}, "lang": {"required": true}, "to_date": {"required": true} } } },
    { "export_category_code": "noon_catalog_globalcatalogexport",
      "params": { "fields": { "country": {"required": true}, "mp_code": {"required": false}, "noon_status": {"required": true} } } },
    { "export_category_code": "noon_financeweb_transactionviewreportonitemlevel",
      "params": { "fields": { "from_date": {"required": true}, "to_date": {"required": true} } } },
    { "export_category_code": "fbn_inventoryv2_ledgerdetailedview",
      "params": { "fields": { "country_code": {"required": false}, "nfsku": {"required": false}, "partner_barcode": {"required": false}, "partner_sku": {"required": false}, "sku": {"required": false}, "transaction_type": {"required": false} } } },
    { "export_category_code": "fbn_inventoryv2_ledgersummaryview",
      "params": { "fields": { "aggregate_by_location": {"required": false}, "aggregate_by_time": {"required": false}, "country_code": {"required": false}, "nfsku": {"required": false}, "partner_barcode": {"required": false}, "partner_sku": {"required": false}, "sku": {"required": false} } } },
    { "export_category_code": "fbn_inventoryv2_aging",
      "params": { "fields": { "country_code": {"required": false}, "partner_barcode": {"required": false}, "partner_sku": {"required": false}, "sku": {"required": false} } } },
    { "export_category_code": "noon_noonoms_ordersexport",
      "params": { "fields": { "from_date": {"required": true}, "is_fulfilled_by_noon": {"required": false}, "to_date": {"required": true} } } },
    { "export_category_code": "namshi_financestatement",
      "params": { "fields": { "from_date": {"required": true}, "to_date": {"required": true} } } }
  ]
}
```

> `namshi_financestatement` 属于 **Namshi** 平台，Q9 已决议不扩平台 → **本 ERP 不使用**，仅如实记录。

### 3.3 `GET /event-notifications/v1/event-type/list`（R5）

`x-request-id: req_000_01a1d512916a741490393881e03c190d` · HTTP 200

```json
{ "event_types": ["FBPI::ORDER_SYNC", "FBPO::PO_SYNC", "RETURNS::REFERENCE_UPDATE"] }
```

### 3.4 `GET /event-notifications/v1/destination/list`

`x-request-id: req_000_01a1d513d98375cf86fdca45c899cb92` · HTTP 200

```json
{ "destinations": [] }
```

> 路径须为 **GET**；误用 `POST` 会得到 `404 NOT_FOUND / Method Not Allowed`，错误 envelope 里带 `log_url`（含 `requestId`），可用于 Portal 查日志。

### 3.5 沙箱跨 host 检查

`x-request-id: req_000_01a1d512940e7906a7ca287e4feb1159` · HTTP 200 → `{"event_types":[]}`（mock 空数组），`Set-Cookie: ak_bmsc`。

---

## 4. R3 结论：仓库清单与库存推送

| `warehouse_code` | `display_name` | `fulfillment_system_code` | `is_active` |
| --- | --- | --- | --- |
| `W00167913AE` | king01 | `directship_v2` | false |
| `W00167918AE` | king02 | `directship_v2` | false |
| `W00167921AE` | king03 | `directship_v2` | true |
| `W00167923AE` | king | `fbpi` | false |
| `W00168495AE` | ZD-UAE01 | `directship_v2` | true |

**N-22**：`next_token=null` 表示已是全量 5 条；编码后缀 `AE`（UAE），**未出现 KSA 仓**；`fulfillment_system_code` 只有 `directship_v2` 与 `fbpi`，**没有任何 FBN/noon 履约仓**。

**结论**：
- `Warehouse Platform` 管理的是**卖家自持仓**（供 FBPI / directship 自发货使用），FBN 是 noon 官方仓，不在此清单内 —— 这与业务常识一致。
- 因此 **`Stock API`（`GetStock` / `UpdateStock`）对 FBN 仓不适用**，PRD §2.5「自有仓库存推送」维持 **❌ 不推送**，FR-04 的四态库存仍以本地台账 + ASN + FBN 库存报表为准。R3 关闭。
- 仓编码可直接作为 `md_warehouse.warehouse_code` 的候选字典值，但**不应拿它当 FBN 仓编码**（O-01 中「FBN 仓编码未核到」的结论不变）。

---

## 5. R4 结论：12 个导出类别与 ERP 用途映射

| # | `export_category_code` | 必填参数 | 对 ERP 的潜在用途 | 本版 |
| --- | --- | --- | --- | --- |
| 1 | `noon_catalog_catalogexport` | `country*`, `noon_status*` | 商品目录基线 / Partner SKU 映射补全 | 后期 |
| 2 | `noon_catalog_content_contentrejections` | — | 内容驳回原因（Q4 不做刊登） | 不用 |
| 3 | `fbn_inventory_fbninventorydetailreport` | `close_date*`, `country*` | **FBN 库存日结明细** → 库存四态核对 | 🔴 候选 |
| 4 | `noon_financeweb_invoicesandcreditnotes` | `contract_order_nrs*`, `start_date*`, `end_date*` | 发票 / 贷项通知单 → 账单核对 | 🔴 候选 |
| 5 | `noon_catalog_reports_productviewsandsalesdata` | `country*`, `from_date*`, `to_date*`, `lang*` | 销量数据 → 补货建议销量输入 | 后期 |
| 6 | `noon_catalog_globalcatalogexport` | `country*`, `noon_status*` | Global 目录（本版不实现 Global） | 不用 |
| 7 | `noon_financeweb_transactionviewreportonitemlevel` | `from_date*`, `to_date*` | **item 级交易明细** → 疑似 statement 行级来源 | 🔴 候选 |
| 8 | `fbn_inventoryv2_ledgerdetailedview` | 全可选（含 `partner_sku`, `nfsku`） | **FBN 库存台账流水** → 出入库溯源 | 🔴 候选 |
| 9 | `fbn_inventoryv2_ledgersummaryview` | 全可选（含 `aggregate_by_location`） | FBN 库存汇总 → 站点/仓在库核对 | 🔴 候选 |
| 10 | `fbn_inventoryv2_aging` | 全可选（含 `partner_sku`） | 库龄 → 滞销与仓储费预估 | 后期 |
| 11 | `noon_noonoms_ordersexport` | `from_date*`, `to_date*`；可选 `is_fulfilled_by_noon` | **FBN 订单导出**（可精确筛 FBN） | 🔴 候选 |
| 12 | `namshi_financestatement` | `from_date*`, `to_date*` | Namshi 财务报表，Q9 不扩平台 | 不用 |

**N-23 结论**：v0.1/v0.2 的「缺 FBN 订单 / Statement 端点」应精确表述为 —— **缺实时 REST 端点，但存在 impex 异步报表导出可覆盖 FBN 订单、FBN 库存流水、item 级交易明细、发票贷项**。这给了 `NoonAdapter` 一个「CSV 通道自动化」的升级路径（编号 `EXPORT_API`），但不改变 MVP 的降级设计：

- 权限：`CreateExport` 是写语义且 Not on MCP，只读账号下**未验证可用**（O-07）。
- 列头：导出文件的实际列名、金额口径、行类型**未实调**（A-01 保持：列头可配置、人工录入 + 账单反推）。
- 因此 **FR-13 导入层先按「文件」设计**，将来接 `CreateExport` 只增加一个文件获取器，不改表、不改解析规则。

---

## 6. R5 结论：事件类型

| `event_type` | 覆盖范围 | 对 FBN ERP | 落表 |
| --- | --- | --- | --- |
| `FBPI::ORDER_SYNC` | FBPI 自发货订单 | ❌ 不适用（本版 FBN） | — |
| `FBPO::PO_SYNC` | 采购单同步 | ❌ 不适用 | — |
| `RETURNS::REFERENCE_UPDATE` | 退货参考号更新 | ✅ 可用于退货跟踪 | `sal_return` / `int_webhook_event` |

`destinations: []` → 当前项目**未配置任何 webhook 接收端**。

**N-24 结论**：**没有 FBN 订单事件**，webhook 通道不能作为 FBN 订单主通道，坐实「FBN 订单走导出/手工」；`RETURNS::REFERENCE_UPDATE` 是唯一对本版有用的事件。PRD §2.5「事件订阅」行应标注为「仅退货辅助」。

---

## 7. N-25：官方 MCP 权限矩阵（54 个 API Reference 端点全量扫描）

扫描方法：sitemap 中全部 `/docs/api-reference/**` 54 页，提取每页的 `**MCP access:**` 标注。

| 分类 | 数量 | 含义 |
| --- | --- | --- |
| **Read**（`execute_api_read` 可调用） | **11** | AI 外挂能读的全部范围 |
| **Change**（`execute_api_change`） | **0** | 平台未暴露任何写工具 → **只读边界是平台级硬保证** |
| **Not on MCP** | **43** | 含登录、whoami、Offer、impex、FBPI 订单、Returns、所有写端点 |

### 7.1 可走 MCP Read 的 11 个端点（完整）

| 方法 | 路径 | 名称 |
| --- | --- | --- |
| POST | `/content/v1/categories/attributes/list` | `ListCategoryAttributes` |
| POST | `/content/v1/categories/list` | `ListCategories` |
| POST | `/content/v1/product/content/get` | `GetContent` |
| POST | `/content/v1/product/group/get` | `GetProductGroup` |
| GET | `/event-notifications/v1/destination/{destination_code}/get` | `GetDestination` |
| GET | `/event-notifications/v1/destination/list` | `ListDestinations` |
| GET | `/event-notifications/v1/event-type/list` | `ListEventTypes` |
| POST | `/pricing/v1/pricing/get` | `BatchGetPricing` |
| POST | `/stock/v1/stock-list` | `GetStock` |
| POST | `/warehouse-platform/v1/warehouses/list` | `ListWarehouses` |
| POST | `/xborder-pricing/v1/transfer-price/get` | `BatchGetTransferPrice` |

### 7.2 与本版强相关、但 **Not on MCP** 的端点

| 端点 | 影响 |
| --- | --- |
| `GET /offer/v1/product/{partner_sku}` (`GetProductOffers`) | FR-02.3 双站点 Offer 健康对比**不能靠 MCP**，须由 ERP 核心用 service account 直连 |
| `GET /impex/v1/export/category/list`、`POST /impex/v1/export/create`、`POST /impex/v1/export/status` | impex 三步全不走 MCP，AI 外挂无法触发导出 |
| `POST /returns/v1/return-references/list` | 退货参考号不在 MCP（但 `RETURNS::REFERENCE_UPDATE` 事件类型在） |
| `GET /identity/v1/whoami`、`POST /identity/public/v1/api/login` | 鉴权不在 MCP，MCP 有自己的 OAuth 通道 |
| `POST /stock/v1/stock-update`、`POST /pricing/v1/pricing/upsert`、`/content/v1/product/upsert` 等全部写端点 | 平台未暴露写工具 → AI 物理上无法改价格/库存/商品 |

> **设计修正**：PRD §8「AI 外挂取数 = 官方 MCP + ERP `v_ai_*`」应改为 —— **ERP `v_ai_*` 只读视图是主力，MCP 只是补充**（能补类目/内容/价格/库存/仓清单/事件类型，补不了 Offer 健康、报表、订单、账单）。首个场景 `STATEMENT_RECON` 本来只读本地视图，不受影响。

---

## 8. 对 PRD 的四处修正（已同步至 v1.0.2）

| # | 原口径 | 修正后 |
| --- | --- | --- |
| 1 | 「Statement / FBN 订单**无 API**」 | 无实时 REST 端点，但 impex 有 4+ 类相关报表导出；`EXPORT_API` 作为 CSV 通道的可选自动化升级，字段与权限待验证 |
| 2 | 「官方 MCP `execute_api_read` 可供 AI 取数」 | 仅 11 个 Read 工具、0 个 Change；Offer/报表/订单/账单**不在 MCP**，主力改为 ERP 只读视图 |
| 3 | 「UAT-01 步 9 用 AI 只读账号尝试写库存/价格」 | 改为「核对 MCP 工具清单中零写工具 + 在受控环境验证写端点返回 403」，**不在生产盲测写操作** |
| 4 | 「Stock API 适用性待确认」 | R3 已关闭：`ListWarehouses` 无 FBN 仓类型 → 不推送库存 |

---

## 9. A-04 只读权限决议：影响与建议

用户于 2026-11-13 21:18 将 Service Account 角色设为**只读**。

| 维度 | 判断 |
| --- | --- |
| MVP 够用吗 | ✅ **够**。本版 API 通道全部是读操作（Offer 回读、仓清单、类别清单、事件类型、退货参考号）；写操作全部走 Seller Lab 人工 / 本地台账，符合 Q4「不做刊登」 |
| 安全性 | ✅ 显著降低误写风险；且 MCP 侧平台零写工具，形成**双层只读** |
| 与 AI 外挂的关系 | ✅ **互不干扰**：AI 走 MCP OAuth 授权通道，不共用 service account 角色 → 以后给 service account 提权也不会放开 AI 的写权限 |
| 后期要写怎么办 | ⚠️ N-16：每个 partner **只能建 1 个 service account**，角色绑在 account 上（最多 5 个 key）→ 刊登/库存回写/调价需**改这个 account 的角色**，不能靠「另建一个可写账号」隔离；若坚持隔离，只能走 MCP OAuth 或申请集成商凭据 |
| 现实卡点 | 🔴 `CreateExport` 为写语义且 Not on MCP，只读下能否调用**未验证**（O-07）；若被拒，§5 的 impex 自动化升级需单独授权或保持手工导出 |
| 凭据本身 | 文件权限仍为 `0644`、默认全 IP 且永不过期（R10）；建议 `chmod 600` + IP 白名单 + 过期时间，本轮**未改动用户个人目录文件** |

**建议（不擅自扩大范围）**：保持只读直到 M2 完成；M3 若要把 FBN 订单/账单导入自动化，再单独评估 `CreateExport` 授权，评估前 A-01 默认值（人工录入 + 账单反推）继续有效。

---

## 10. 接口字段映射（实测部分，补 PRD §7.5）

| 数据源 | 目标表 | 实测字段 | 状态 |
| --- | --- | --- | --- |
| `POST /identity/public/v1/api/login` | `int_api_log` / 会话上下文 | `Set-Cookie: _npsid, _nprtnetid`；`x-request-id` | ✅ 实测 |
| `GET /identity/v1/whoami` | `md_partner_account` | `user_code`, `username` | ✅ 实测 |
| `POST /warehouse-platform/v1/warehouses/list` | `md_warehouse` | `warehouse_code`, `display_name`, `fulfillment_system_code`, `is_active`, `next_token` | ✅ 实测（**仅自持仓**） |
| `GET /impex/v1/export/category/list` | `int_export_job` / `sys_config` | `export_category_code`, `params.fields.<name>.required` | ✅ 实测 |
| `GET /event-notifications/v1/event-type/list` | `int_webhook_event` 枚举字典 | `event_types[]` | ✅ 实测 |
| `GET /event-notifications/v1/destination/list` | `int_webhook_event` 配置核对 | `destinations[]` | ✅ 实测（空） |
| `GET /offer/v1/product/{partner_sku}` | `md_partner_sku` | `partner_sku`, `sku`, `title`, `brand`, `offers[]` | ⚠️ 文档已核，**未实调**（需真实 `partner_sku`，O-08） |
| impex 导出**文件内容** | `sal_order(_item)` / `fin_statement(_line)` / `inv_stock_*` | 列头未知 | ⚠️ 未实调（O-01 / O-07） |
| Seller Lab 订单 / 账单 CSV | 同上 | 列头未知 | ⚠️ A-01：可配置映射 + 账单反推 |

---

## 11. 待验证清单

| 编号 | 事项 | 影响 | 默认处理 |
| --- | --- | --- | --- |
| **O-07** | 只读角色能否调用 `CreateExport`（及 `GetExportStatus`） | 决定 impex 自动化导出通道是否可用 | 未验证前按「不可用」，A-01 手工导出不变 |
| **O-08** | 真实 `partner_sku` 样本，用于 `GetProductOffers` 实调 | FR-02.3 Offer 健康字段落地 | 从 Seller Lab 商品列表取 1 个；不臆造 |
| **O-09** | impex 导出文件的实际列头、金额口径、行类型枚举 | 决定导入解析规则 | 列头做成可配置映射，拿样本再填 |
| **O-10** | KSA 站点仓编码 / 是否需要第二个仓字典 | `md_warehouse` 初始化 | 生产 `ListWarehouses` 仅返回 AE 后缀 5 条，暂按 UAE 建模，KSA 待补 |
| **O-11** | 只读账号写端点返回码（403 / PERMISSION_DENIED 具体 envelope） | UAT-01 步 9 判据 | 受控环境验证，不在生产盲测 |

---

## 12. 给开发的配置建议（不改动已交付 DDL）

`sys_config` 建议新增以下条目（**仅建议，未写入 `schema-v0.3.sql`**，保持已评审基线稳定）：

| `config_key` | 建议值 | 说明 |
| --- | --- | --- |
| `api_user_agent` | `NoonERP/1.0` | 所有请求必带 |
| `api_request_id_header` | `x-request-id` | 写 `int_api_log.request_id` |
| `api_session_cookie_names` | `_npsid,_nprtnetid` | 仅记录名称，值不落日志 |
| `export_category_fbn_inventory` | `fbn_inventory_fbninventorydetailreport` | R4 实测 |
| `export_category_fbn_ledger` | `fbn_inventoryv2_ledgerdetailedview` | R4 实测 |
| `export_category_orders` | `noon_noonoms_ordersexport` | R4 实测 |
| `export_category_finance_item` | `noon_financeweb_transactionviewreportonitemlevel` | R4 实测 |
| `export_category_invoices` | `noon_financeweb_invoicesandcreditnotes` | R4 实测 |
| `event_type_returns` | `RETURNS::REFERENCE_UPDATE` | R5 实测 |
| `mcp_read_tool_count` | `11` | N-25，能力回归检查用 |

---

## 13. 来源

| 来源 | 时间 (GMT+8) | 用途 |
| --- | --- | --- |
| `POST /identity/public/v1/api/login` 等 7 次生产只读实调 | 2026-11-13 21:20 | N-21~N-24、`x-request-id` 原始值 |
| 沙箱 `/event-notifications/v1/event-type/list` | 2026-11-13 21:20 | 跨 host cookie 验证 |
| `/docs/api-reference/authentication/auth-service-api-login.md` | 2026-11-13 21:19 | 官方 Python / Go / Java JWT 示例 |
| `/docs/overview/sandbox.md` | 2026-11-13 21:19 | 静态沙箱口径、权限校验跳过 |
| 54 页 `/docs/api-reference/**` 的 `MCP access` 标注 | 2026-11-13 21:21 | N-25 权限矩阵 |

---

**文档结束 · INT-NOON-ERP-001 v1.0**
