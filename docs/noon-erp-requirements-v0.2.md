# Noon ERP — 需求收敛 v0.2（FBN 单模式 · 可开发版）

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.2 — 基于 Q1~Q14 答复收敛，含数据库表设计初稿 |
| 时间 | 2026-11-12 23:47 (GMT+8) |
| 前置文档 | `docs/noon-erp-requirements-v0.1.md`（业务流程与需求梳理） |
| 本轮新增 | 通过 egolite 浏览器对 Noon 官方开发文档站 + Developer Portal 做一手核查 |
| 结论摘要 | **v0.1 关于「Noon 没有公开 API」的判断已过时，本版予以推翻并修订**；范围按 FBN 单模式大幅收窄 |

---

## 0. 先看这个：本轮最重要的三件事

> **① 平台接口事实变了，v0.1 的两条关键结论要作废。**
> 你给的 `noon-docs.noonpartners.dev` 是一个**完整公开的开发者文档站**（sitemap 共 123 页），并且 `developer.noon.partners` 已登录，账号 `yixin`、项目 `PRJ468049`。文档里有鉴权、限流、错误码、静态沙箱、Java 示例、官方 MCP Server。所以 v0.1 的 F-08「无公开开发者门户 / 无鉴权限流沙箱」和 F-07「商品创建不支持 API」**对当前这版平台已不成立**。详见 §2、§3。

> **② 但你主力跑 FBN，公开 API 恰好缺 FBN 最要命的三块。**
> 全量 sitemap 里**没有 FBN Inbound(ASN) 参考页、没有 FBN 订单 API、没有 Statement/结算 API、没有广告 API**。术语表明确写了「ASN 通过 FBN Inbound API 创建，且必须在预约仓库 slot 前 seal」，却没有对应端点文档 → 判定为「能力存在但未公开」。结论：**商品/价格/库存/Offer/报表/事件可走 API；入仓、订单、账单仍要按 Seller Lab 导出 + 手工登记设计**。详见 §4。

> **③ 范围按你的答复收窄后，MVP 变得很轻。**
> 只做 FBN 一种履约模式 → 利润只需**一套佣金模型**（v0.1 的双引擎暂不实现）；不做刊登、不做广告、不做 VAT 申报、不做审批流、不扩平台、不摊期间费用 → 主干只剩「采购 + FIFO 批次 + FBN 入仓 + 订单/账单导入 + SKU 利润 + 补货建议」。

---

## 1. 决议记录（Q1~Q14 → 落地设计）

| 编号 | 你的答复 | 落地设计 | 对 v0.1 的影响 |
| --- | --- | --- | --- |
| Q1 | **FBN** | MVP 只实现 FBN 佣金结算模型；`fulfillment_mode` 枚举保留 `FBN/GLOBAL/FBP` 但不建另外两套利润引擎 | **双利润引擎降级为单引擎**，工作量显著下降 |
| Q2 | 一个店铺、两个站点 | `md_partner_account`(1) → `md_store`(1) → `md_site`(AE/SA 各 1) → `md_warehouse`(每站 FBN 仓 + 国内/中转仓)。站点与仓用 `site_code` 贯穿所有单据 | 注意：公开资料说「一照一店、两站账号独立数据不互通」，与你说的「一个店铺两站点」口径不同 → 见 R9 |
| Q3 | 已登录 egolite；开发文档站 | **有 Developer Portal 权限，项目 `PRJ468049`**；但登录 Portal ≠ 有 API 调用凭据，仍需 Service Account `.json` → 见 R2 | **推翻 v0.1 的 F-08**；排期不再受「拿不到 spec」阻塞 |
| Q4 | 刊登不重要，后期再说 | 不建 Listing/内容表；**只保留 `md_partner_sku` 映射主数据**（订单回查本地 SKU、利润归因必需） | M2 从 MVP 移出，仅保留映射 |
| Q5 | **FIFO** | 建 `inv_batch`（成本批次）+ `inv_batch_consumption`（批次消耗流水），销售出库按 `received_at ASC, batch_id ASC` 依次扣减 | 库存成本模型改为批次制，取消移动加权 |
| Q6 | 先不考虑 VAT | 不做申报模块；`sys_config.vat_mode = IGNORE`。**但保留 `vat_amount` 空字段与 fee_dict 的 VAT 科目位**（风险见 R7） | M9 的 VAT 归集移出 MVP |
| Q7 | 广告先不做 | 不建广告模块；**但 `fin_statement_line` 必须保留 `AD_SPEND` 行类型**，否则周账单净额对不平 | M11 移出，保留一个科目位 |
| Q8 | 不用摊，单独，部分合并 | **双层利润**：L1 SKU 贡献毛利（售价 − 平台费用 − FIFO COGS）；L2 期间/批次费用池（头程/关税/包材/汇兑/通道费）独立归集，通过 `allocation_policy` 支持「不合并 / 部分合并 / 全合并」三种出报表方式。详见 §6 | 利润表拆两层，`fin_expense_pool` 独立成表 |
| Q9 | 不扩平台 | 只保留 `NoonAdapter` 一层，不引入多平台抽象基类 | 架构更薄 |
| Q10 | 云端服务器、10 人以内、无移动端 | SpringBoot 单 jar + 单库（MySQL 8）+ Nginx/HTTPS + 定时备份；简单 RBAC 即可，Web 端单页/服务端渲染均可 | 不引入注册中心、MQ、移动端 |
| Q11 | 不需要审批流 | 单据保留 `status` 状态机 + `sys_audit_log`；后期加审批只新增 `sys_approval_flow`，不改现有表 | 减少 1 张表 + 一套流程引擎 |
| Q12 | WorkBuddy / Codex 之类 | **理解为「AI 客户端/编码助手作为外挂」**。恰好 Noon 官方 MCP Server 支持 Claude Code / Codex / Cursor，OAuth 授权且读写权限分离 → 外挂可直接用官方 MCP 的只读权限，天然满足「不侵入核心」。首个场景仍建议「周账单差异核查」。**请确认这个理解**（见 §10） | M12 的接入方式具体化 |
| Q13 | 数据在 Noon 后台 | 提供标准导入模板（SKU/供应商/期初库存/历史订单可选）；历史数据只迁主数据 + 期初批次，不迁历史订单 | 迁移范围收窄 |
| Q14 | 选品独立，不进 ERP | ERP 只接收「已开发完成的 SKU」，选品爬取工具保持独立 | 边界明确 |

**默认按以上理解执行，不需要你再逐条确认**；只有 Q12 的理解和 §10 的 3 件事建议回一下。

---

## 2. 一手核查事实（浏览器实读，编号 N-01~N-15）

核查时间 2026-11-12 23:47 GMT+8；来源：`noon-docs.noonpartners.dev`（公开文档站）、`developer.noon.partners`（已登录）。

| 编号 | 事实 | 对开发的意义 |
| --- | --- | --- |
| N-01 | 文档站公开，sitemap 共 **123 页**；有 API Domains / Errors / Rate Limiting / Sandbox / Terminology / MCP / Changelog / API Health 全套 | 接口契约可直接照文档开发，不必等工单给 spec |
| N-02 | Developer Portal 已登录：**账号 `yixin`，项目 `PRJ468049`**；可用 Event Notifications、Request Logs（保留 14 天）、Rate Limit Analytics、Support Cases | 有项目级权限；排障可自助查 request_id |
| N-03 | 生产网关 `https://noon-api-gateway.noon.partners`；静态沙箱 `https://noon-sandbox-api-gateway.noon.partners`（换 host 即可，login 仍走生产，同 schema/同限流/无副作用/不返回 5xx） | 可先接沙箱联调，不污染生产数据 |
| N-04 | 鉴权：Partner Portal → User Access → Add Service Account → 下载 `.json`（`key_id` / `private_key` / `project_code`，**只显示一次**）→ 用 RS256 签 JWT（`sub`=key_id, `iat`, `jti`）→ `POST /identity/public/v1/api/login` body `{token, default_project_code}` → 返回 **session cookie**，后续请求带 cookie；**所有请求必须带 `User-Agent`**。官方 Quick Start 提供 **Java 示例** | 直接对应 Java 单体；凭据文件必须加密存服务端、绝不进 Git |
| N-05 | 限流：**per-project** 固定窗口计数 + burst 双计数，两个计数器独立；`429` 用指数退避。响应头 `X-Request-Id` / `X-Ratelimit-Remaining` / `X-Ratelimit-Burst-Remaining` / `X-Ratelimit-Retry-After`。Login、CreateExport 均为 1500 次/60s | `int_api_log` 记录 `X-Request-Id`；客户端统一做限流+退避+重试 |
| N-06 | 错误统一 envelope：`error{status_id, code, message, fields[{name, descriptions}], doc_url}`；`PERMISSION_DENIED` 需给 service account 配 Project Viewer / Project Owner 角色后重新认证 | 错误码可直接映射成系统告警；上线前先配好角色 |
| N-07 | **公开 API 域与端点**（sitemap 实读）：Catalog 10（SKU 生成/改名/删除/条码映射/批量导入/导入状态）、Content 7（类目/类目属性/UpsertProduct/ProductGroup/GetContent）、Pricing 2（batch get/upsert）、Stock 2（Get/Update）、Offer 1（GetProductOffers）、Global Product & Pricing 3、FBPI 12（订单列表/详情/客户数据/UpdateOrder/CreateShipment/AWB/CancelShipment/Invoice/SandboxOrder）、FBPO 1、Warehouse Platform 1、Reports(impex) 3、Event Notifications 5、Returns 1、Auth/OAuth/API User 各 2 | 见 §4 通道矩阵 |
| N-08 | **缺口**：sitemap 中**无 FBN Inbound / ASN 参考页、无 FBN 订单 API、无 Statement / 结算 / 财务 API、无广告 API** | FBN 入仓/订单/账单按 CSV 通道设计，见 R1 |
| N-09 | 术语表却明确定义：`ASN ... created through the **FBN Inbound API** before shipping inventory to a noon fulfillment center. Must be created and sealed before a warehouse slot can be scheduled.`；API Domain 举例也含 `FBN Inbound` | 能力存在但未公开 → 提 Support Case 索取，别自己臆造端点 |
| N-10 | `GetProductOffers`：一次读**跨国家**的 `live_status` / price / stock / `offer_issues`；Offer = 每国家 + 每 business model 一条；`business_model` ∈ `noon / supermall / Global`，**API 当前仅支持 `noon`**；`is_active`（Pricing 激活）与 `live_status`（前台可见）分离 | **双站点对比可直接用它**；也是「为什么我设了价却不展示」的排查入口 |
| N-11 | `Stock API`：按 `warehouse_code` 跟踪（来自 Seller Lab FBPI 仓库配置）；更新是**绝对值 set 不是增量**；`Reserved stock` 由 noon 自动扣、**不通过 API 设** | 库存对账必须区分「我们推送的可用量」与「noon 的 reserved」；绝不能用增量同步 |
| N-12 | `Reports (impex)`：`GetExportCategoryList` → `CreateExport{export_category_code, params}` → `GetExportStatus`，异步取文件 | 可能是订单/账单的 API 来源，但 `export_category_code` 清单需拿到凭据后实调才知道（R4） |
| N-13 | `Event Notifications`：每 project 最多 **5 个 HTTPS destination**；支持 CreateHttpsDestination / ListDestinations / ListEventTypes / UpdateDestination / GetDestination；失败自动重试，无需轮询 | 订单/退货事件优先走 webhook；destination 数量要规划 |
| N-14 | **官方 MCP Server**：支持 Claude Code / Codex / Cursor；OAuth 登录 `oauth.noon.partners`，授权时勾选 **Read**（`execute_api_read`，绝不改数据）/ **Change**（`execute_api_write`，可改价格库存）；可随时 Revoke（已发 token 最长还能活 1 小时）。部分 API 标注 `Not on MCP`（如 Login、CreateExport） | **Q12 的最优解**：AI 外挂只授 Read，物理上无法写核心数据；WorkBuddy/Codex 可直接接 |
| N-15 | Content API 有 `UpsertProduct` / `UpsertProductGroup` / 类目属性发现；Catalog 有 `SkuGenerate` | 说明「商品创建不支持 API」已过时；但 Q4 不做刊登，本版不用 |

---

## 3. 对 v0.1 的修订对照（数据质量说明）

| v0.1 编号 | 原结论 | 本版处置 |
| --- | --- | --- |
| F-07 | 商品创建不支持 API，必须 Seller Lab | ❌ **作废**。现有 Catalog `SkuGenerate` / Content `UpsertProduct` 等端点（N-15）。本版因 Q4 不做刊登，暂不使用 |
| F-08 | 无公开开发者门户 / 无公开鉴权限流沙箱 / 需工单才给 spec | ❌ **作废**，由 N-01/N-03~N-06 全面取代。文档站公开且完整 |
| C-06 | API 实际可用范围待确认 | ⚠️ **部分解决**：通用域已确认；FBN Inbound / FBN 订单 / Statement 仍缺（N-08/N-09），转 R1 |
| Q3 相关 | 排期受「拿不到 API spec」阻塞 | ✅ **解除**。但改为 R2：需创建 Service Account 凭据 |
| 双利润引擎 | Global 供价模型 + FBN/FBP 佣金模型 | ⏸ **降级**。Q1=仅 FBN，MVP 只做佣金模型；枚举保留，等真跑 Global 再加 |
| 刊登模块 M2 | P0（CSV 半自动） | ⏸ **移出 MVP**（Q4），保留 `md_partner_sku` 映射 |
| 广告 M11 | P1 | ⏸ **移出**（Q7），保留 `AD_SPEND` 科目位 |

> 说明：v0.1 的 F-08 来自 2026-09 的第三方资料，反映的是**当时**的状态；Noon 之后上线了开发者平台。这类平台事实会变，所以本版所有结论都带核查时间与来源，费用/规则仍一律参数化（`sys_config`），不写死在 Java 常量里。

---

## 4. FBN 单模式：业务闭环与数据通道矩阵

```
采购下单 → 到货入库(FIFO批次) → ASN入仓/头程 → FBN在仓库存
   ↑                                              ↓
补货建议 ← SKU利润(L1+L2) ← 周账单对账 ← 订单/退货/妥投
```

| 数据 / 动作 | 通道 | 依据 | MVP |
| --- | --- | --- | --- |
| SKU / Partner SKU 映射 | 一次性导入（Seller Lab 导出） | Q4 不做刊登 | ✅ |
| 商品价格 / 激活 | Pricing API（可选，暂缓） | N-07 | ❌ |
| 自有仓库存推送 | Stock API（UpdateStock / GetStock） | N-11；**FBN 仓是否适用待确认，见 R3** | ⚠️ |
| Offer 健康 / 双站点对比 | `GetProductOffers` API | N-10 | ✅ |
| **FBN 入仓 ASN** | **Seller Lab 手工登记** + 提 Support Case 索取 FBN Inbound API | N-08 / N-09 | ✅（手工） |
| **FBN 订单** | Event Notifications（若事件类型覆盖）+ impex 导出 + Seller Lab CSV | N-08 / N-12 / N-13 | ✅（导入） |
| **周 statement / 月度仓储账单** | **Seller Lab 导出 CSV** | N-08；v0.1 F-10/F-11 | ✅ |
| 退货 | `ListReturnReferences` API + 账单退货行 | N-07 | ✅ |
| 事件订阅 | Event Notifications webhook（≤5 destinations） | N-13 | ✅ |
| AI 外挂取数 | 官方 MCP `execute_api_read` / ERP 只读视图 | N-14 | ✅ |

> **一句话**：API 能搞定「商品/价格/库存/Offer/报表/事件」，搞不定「FBN 入仓、订单、结算单」。所以 `NoonAdapter` 仍保留 API / CSV / Webhook / 手工四通道，不是过度设计，是 FBN 的现实。

---

## 5. FIFO 成本模型（Q5 落地）

**规则**

1. 每次采购到货生成一条 `inv_batch`：`qty_in`、`qty_remaining`、`unit_cost`、`received_at`、`warehouse_id`、`currency`、`fx_rate`。
2. 销售出库（订单确认妥投）时，按 `received_at ASC, batch_id ASC` 依次扣 `qty_remaining`，每扣一批写一条 `inv_batch_consumption`（`batch_id` + `qty` + `unit_cost` + `amount`）。
3. `COGS = SUM(inv_batch_consumption.amount)` 按订单行归集，不做全月加权、不做事后分摊。
4. 退货二次上架：生成一个**新的批次**（成本 = 该退货的补偿后净值或原批次成本，按 `sys_config.return_restock_cost` 配置），插到队尾，不回流原批次。
5. 盘点盈亏：写 `inv_stock_move` 调整单，并按批次追补/冲销 `inv_batch_consumption`。
6. 头程/关税/包材**不进批次成本**（Q8），进 `fin_expense_pool` 作为 L2。

**为什么这样设计**：Q8 明确「不摊、单独、部分合并」，如果把头程摊进 `unit_cost`，就再也无法单独出「不含期间费用的 SKU 贡献毛利」。FIFO 管的是**采购成本**，L2 管的是**期间/批次费用**，两者必须物理分离。

---

## 6. 双层利润模型（Q8 落地）

| 层 | 口径 | 公式 | 出报表 |
| --- | --- | --- | --- |
| **L1 · SKU 贡献毛利** | 只看能直接归属到 SKU 的 | `含税售价 − 类目佣金 − FBN 出库费 − 仓储费(月度账单按 SKU 体积/件数归集) − 退货管理费与净损耗 − FIFO COGS` | SKU / Partner SKU / 站点 / 品类 / 负责人 |
| **L2 · 期间与批次费用池** | `fin_expense_pool` 独立归集 | `头程运费 + 关税/清关 + 包材/贴标 + 汇兑损益 + 收款通道费 + 广告扣款` | 按期间 / 按 ASN 批次 / 按站点单独出 |
| **合并视图** | 由 `allocation_policy` 控制 | `NONE` = 只看 L1；`PARTIAL` = 只合并你勾选的科目；`FULL` = L1 − 全部 L2 | 三种口径可切换、可并存，互不覆盖 |

- 费用字典 `fin_fee_dict` **版本化**：`fee_code / site_code / category_code / fulfillment_mode / rate_type / value / effective_from / effective_to`，费率变动留历史，`fin_profit_snapshot` 带 `formula_version`，支持**全量重算**。
- `AD_SPEND` 作为 statement 行类型保留（Q7），在 L2 里可勾选合并，否则周账单净额对不平。
- ⚠️ **VAT 风险（R7）**：Noon 前台售价含 VAT（UAE 5% / KSA 15%），而所有费率报价**不含 VAT**。Q6 决定不做 VAT，则 L1 会系统性高估毛利。本版按 `vat_mode=IGNORE` 实现，但**保留 `vat_amount` 字段与 VAT 科目位**，随时可一键启用，不改表结构。

---

## 7. 数据库表设计初稿

完整可执行 DDL 见 **`docs/noon-erp-schema-v0.2.sql`**（MySQL 8 / utf8mb4，含注释与索引，共 **44 张表 + 4 个 AI 只读视图**，已做静态校验：括号平衡、无重名表、全部建表语句完整）。此处只列分组与关键设计。

### A. 主数据与配置（9 张）

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `md_partner_account` | `project_code`(PRJ468049), `credential_ref`, `base_url`, `env`, `status` | 凭据只存引用路径，私钥不入库 |
| `md_store` | `store_id`, `partner_id`, `name_en/ar` | Q2：1 个店铺 |
| `md_site` | `site_code`(AE/SA), `currency`(AED/SAR), `vat_rate`, `timezone` | Q2：2 个站点；跨站点对比先做汇率归一 |
| `md_warehouse` | `warehouse_code`, `site_code`, `type`(LOCAL/TRANSIT/FBN/3PL), `fulfillment_mode` | 仓绑定站点+模式，创建后不改 |
| `md_sku` | `sku_code`, `spu_id`, `name_en/ar`, `category_code`, `hs_code`, `origin_country`, `brand` | 双语字段保留（后期刊登要用） |
| `md_partner_sku` | `partner_sku`, `sku_id`, `site_code`, `business_model`, `offer_code` | **唯一必需的商品表**（订单回查/利润归因） |
| `md_supplier` / `md_sku_supplier` | 报价、MOQ、交期 | 采购用 |
| `sys_config` | `config_key`, `config_value`, `effective_from` | **C-01~C-07 全部落这里**：vat_mode / payout_threshold / return_window_days / cost_method=FIFO / return_restock_cost / allocation_policy |

### B. 采购 · FIFO 批次 · 库存（12 张）

`pur_order` / `pur_order_item` → **`inv_batch`** → **`inv_batch_consumption`** → `inv_stock_ledger` / `inv_stock_move` / `inv_inbound(_item)` / `inv_transfer(_item)` / `inv_stocktake(_item)`。

- `inv_stock_ledger` 四态：`qty_available` / `qty_in_transit` / `qty_reserved` / `qty_unsellable`（N-11 的 reserved 单独记，不与推送量混淆）。
- `inv_stock_move` 每条带 `ref_doc_type` + `ref_doc_no` + `operator` + `created_at` + `raw_payload`（原始字段不删减）。

### C. 头程与 FBN 入仓（3 张）

- `log_asn`（`asn_no`, `site_code`, `warehouse_code`, `status = DRAFT/SEALED/SLOT_BOOKED/IN_TRANSIT/RECEIVED`, `sealed_at`, `slot_date`, `carrier`, `tracking_no`）—— 对应 N-09 的「seal 后才能约 slot」。
- `log_asn_item`（`qty_shipped`, `qty_received`, `diff_qty`, `batch_id`）—— 收货差异回写。
- `log_shipment_cost`（`fee_type = FREIGHT/DUTY/CUSTOMS/PACKAGING/LABEL`, `allocation_flag`）—— **只登记，不摊进批次成本**（Q8）。

### D. 订单 · 退货 · 结算 · 利润（10 张）

| 表 | 关键点 |
| --- | --- |
| `sal_order(_item)` | `source_channel = API/EXPORT/MANUAL`，`payment_type = COD/PAID`，`raw_payload`，`delivered_at` |
| `sal_return` | `grade A/B/C`, `compensation_amount`, `window_deadline`, `appeal_deadline` |
| `fin_statement` | `type = WEEKLY/STORAGE`, `generated_at`(周三), `payout_date`, **`dispute_deadline = generated_at + 15d`**, `raw_payload` |
| `fin_statement_line` | `line_type = SALE/REFERRAL_FEE/FBN_OUTBOUND/STORAGE/RETURN_FEE/AD_SPEND/COMPENSATION/OTHER`, `raw_json` |
| `fin_fee_dict` | 版本化费率（§6） |
| `fin_payout` | 回款流水 |
| `fin_expense_pool` | L2 费用池 + `allocation_policy` |
| `fin_profit_snapshot` | L1/L2 两套数 + `formula_version` + `recalculated_at` |
| `fx_rate` | 日汇率 |

### E. 补货（1 张）

`rp_replenish_suggestion`：`avg_daily_sales_7/30`、`days_of_cover`、`qty_in_transit`、`lead_time_days`、`safety_stock`、`event_factor`（斋月/Yellow Friday 系数）、`suggested_qty`、`source = AI/MANUAL`、`status = DRAFT/CONFIRMED/CONVERTED`、`converted_doc_no`。**AI 只写 DRAFT，人工确认才转采购单/调拨单/ASN**。

### F. 集成 · 日志 · 权限 · AI 外挂（9 张）

- `int_sync_task`（endpoint / direction / retry_count / next_run_at）、`int_webhook_event`（**幂等键 `event_id`** + payload_raw + 重试）、`int_api_log`（**`X-Request-Id`** + http_status + error_code + 全量 request/response + latency）、`int_export_job`（impex 三步：export_code / export_category_code / status / file_url）。
- `sys_user` / `sys_role` / `sys_user_role`（10 人以内，简单 RBAC）、`sys_audit_log`（`log_no` + 操作人 + before/after + 时间戳）。
- **`ai_suggestion`**（`suggestion_no`, `scene`, `source_agent`, `payload_json`, `status = PENDING/APPROVED/REJECTED`, `reviewed_by/at`, `converted_doc_no`）+ 一组 `v_ai_*` 只读视图。

> 设计约束落实：单据全部有 `status` 状态机但**无审批表**（Q11，后期新增不改现有表）；所有导入留 `raw_payload` / `raw_json` 原始字段不删减；日志带编号 + 时间戳（你的既有要求）。

---

## 8. MVP 收敛范围与完成判定

### ✅ 做（P0）

1. 主数据 + `sys_config` + 简单 RBAC
2. 采购单 → 到货 → **FIFO 批次** → 库存四态台账/流水
3. FBN ASN 登记 + 收货差异 + 头程费用登记（不摊）
4. 订单/退货导入（CSV + webhook 预留）+ Offer 健康 API 读取（双站点对比）
5. 周 statement + 月度仓储账单导入、逐行对账、**15 天争议倒计时**
6. **L1 SKU 贡献毛利 + L2 费用池**，三种合并口径可切换，公式可配可重算
7. 补货建议（双站点、大促系数），建议单转采购/调拨/ASN
8. `NoonAdapter`：JWT/cookie/User-Agent、限流退避、429 重试、webhook 幂等、全量留痕
9. AI 外挂：只读视图 + `ai_suggestion`（可接官方 MCP Read）

### ❌ 不做（本版）

刊登、广告模块、VAT 申报、审批流、多平台、移动端、Global/FBP 利润引擎、供应商绩效、绩效提成。

### 完成判定

> 一个 SKU 从「采购下单」→「FIFO 批次入库」→「ASN 入 FBN 仓」→「订单/账单导入」→「算出 L1 贡献毛利与 L1+L2 净利」→「生成补货建议」全链路跑通，且**不再依赖 Excel 手工台账**；周账单能在 15 天争议期内出差异清单。

---

## 9. 架构（按 Q9/Q10/Q12 收敛）

```
WorkBuddy / Codex / Claude Code
   └─ Noon 官方 MCP（只授 Read）+ ERP 只读视图 v_ai_*
        └─ 只写 ai_suggestion(DRAFT) ──人工确认──> 核心系统落单
                                                    
云端单台 · SpringBoot 单 jar · MySQL 8 单库
   业务模块 → NoonAdapter → [ API | CSV | Webhook | 手工 ]
                 ↓
      noon-api-gateway.noon.partners（沙箱先联调）
```

- 单体、无注册中心、无 MQ（本地任务表 + 定时调度足够 10 人规模）。
- 凭据 `.json` 存服务端加密文件，**不入库、不进 Git**；所有请求带 `User-Agent`。
- 官方 MCP 只授 **Read your seller data**，物理上杜绝 AI 改库存/价格（N-14），比我们自己做权限更稳。

---

## 10. 风险与待办（编号 R，按优先级）

| 编号 | 事项 | 建议动作 |
| --- | --- | --- |
| **R1** | **FBN Inbound(ASN) / FBN 订单 / Statement 无公开端点**（N-08/N-09） | 在 Developer Portal 提 Support Case 索取 FBN Inbound API 与订单/账单导出能力；**在拿到前按 Seller Lab CSV 设计，不要臆造端点** |
| **R2** | 登录 Developer Portal ≠ 有 API 凭据 | 去 Partner Portal → User Access → Add Service Account，下载 `.json`，配 Project Viewer/Owner 角色（N-04/N-06），再用沙箱跑通 Login + whoami |
| **R3** | Stock API 描述是「自有仓库 / FBPI `warehouse_code`」，是否适用于 **FBN 仓**未确认 | 用 `ListWarehouses` + `GetStock` 实读一次你的仓再定 |
| **R4** | `GetExportCategoryList` 返回的 `export_category_code` 清单未知（页面是动态组件） | 拿到凭据后实调一次，确认能否导出订单/账单 |
| **R5** | Event Types 清单未知（同样是动态组件） | 调 `ListEventTypes`，确认是否含订单/退货/库存事件 |
| **R6** | **Seller Lab 后台字段本轮未核到** | 本轮浏览器会话被用户侧结束，系统要求不重试不接管。需你回复「continue」后我再补核：店铺/站点结构、FBN 仓编码、Statements & Invoices 导出字段、真实费率表 |
| **R7** | 不做 VAT 会让 L1 毛利系统性虚高（UAE 5% / KSA 15%，费率报价不含 VAT） | 已知悉并接受；字段/科目位保留，随时可启用 |
| **R8** | Q8「部分合并」的具体科目组合未定 | 先实现 `NONE/PARTIAL/FULL` 三档，`PARTIAL` 由界面勾选科目，不写死规则 |
| **R9** | Q2「一个店铺两站点」与公开资料「一照一店、两站账号独立数据不互通」口径冲突 | 模型做成 account/store/site 三层可配置，凭据可 1 对 1 也可 1 对多；等 R6 核完再定死 |
| **Q12 待确认** | 我把「WorkBuddy / Codex 之类」理解成 **AI 客户端作为外挂**，而非某个具体业务场景 | 若你其实想指定「AI 第一个解决的业务问题」，建议仍选**周账单差异核查**（只读、最痛、最可量化） |

### 建议你下一步只需回三件事

1. **Q12 的理解对不对**（AI 客户端 / 还是业务场景）。
2. **R2 的 Service Account 凭据**是否已经下载过，还是我按「未创建」规划。
3. **R6 是否要继续**：回「continue」我就把 Seller Lab 后台的店铺/仓/账单/费率字段补齐，再出接口字段映射表。

---

## 11. 来源

**一手（本轮浏览器实读，2026-11-12 23:47 GMT+8）**

- `https://noon-docs.noonpartners.dev/` — 首页服务域与端点计数
- `/docs/getting-started/quick-start` — BASE_URL、JWT 登录、User-Agent、Java 示例
- `/docs/overview/api-domains` — 15 个 API 域
- `/docs/overview/rate-limiting` / `errors` / `error-handling` / `sandbox` / `terminology` — 限流、错误 envelope、沙箱、FBN Inbound/ASN 术语
- `/docs/mcp/overview` — 官方 MCP、Claude Code/Codex/Cursor、Read/Write 权限
- `/docs/developer-portal/overview` — Request Logs 14 天、Rate Limit Analytics、Support Cases
- `/docs/stock/stock-intro` / `/docs/offer/offer-intro` — 绝对值更新、Reserved stock、Offer 跨国家读取
- `/docs/api-reference/impex/*` — CreateExport / GetExportCategoryList / GetExportStatus
- `/sitemap.xml` — 全量 123 页，用于判定 FBN/订单/结算端点缺失
- `https://developer.noon.partners/?project=PRJ468049` — 已登录，账号 yixin

**前版**：`docs/noon-erp-requirements-v0.1.md` §9 所列万墨与 Noon 资料（结算周期、15 天争议、费率结构等仍沿用）。

**核查纪律**：本版凡与 v0.1 冲突处已在 §3 显式作废并说明原因；未核到的（R6）如实标注，不用推测补齐；所有平台规则继续参数化，不写死。
