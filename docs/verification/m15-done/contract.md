# M15 · Seller Lab 只读查询接入 ERP 交付报告

日期：2026-11-24  
范围：**FBN 库存 + Sales 销售明细**（ASN 按用户 00:55 指示暂缓，未接入）。  
约束：严格只读平台，不覆盖 ERP 账目、不过账、不修改平台数据、不输出凭据/客户明细。

> 实施说明：原计划由豆包集中实施，但其环境沙箱创建失败（`sbox_error=416001`）且提权被拒，未改动任何代码；改由 WorkBuddy 自行实施并逐项验证。

---

## 一、交付内容

### 1. 统一限速接入所有实际请求路径

`NoonApiClient` 是全部 Noon HTTP 调用的收敛点，三处实际请求均已接入同一个 `NoonRequestThrottle` bean：

| 方法 | 位置 | 处理 |
|---|---|---|
| `login()` | 登录请求 | throttle 包装；Cookie 头在 throttle 内从真实响应读取 |
| `call()` | Offer/导出等 API | throttle 包装；401 重登放在 throttle **外层**，避免持锁重入死锁 |
| `download()` | 导出文件下载 | throttle 包装 |

`NoonThrottleConfig` 提供**进程内单例**（3s 间隔、429/503 最多 3 次有界退避）；`NoonSellerLabClient` 复用同一个实例，分页与两站串行，不并发绕过。多实例部署不共享，需外部限流——已在代码注释与页面标注。

### 2. FBN 库存接入（简单数量对比）

- `NoonSellerLabClient.listFbnInventory`：站点头 `Country-Code` + `X-Locale`，嵌套 `pagination` 翻页。
- `noon_fbn_inventory_snapshot` 表，唯一键 `(site, partner_sku, warehouse_code, captured_at)`；`captured_at` 按天归档保证幂等，`synced_at` 记真实同步时刻。
- `compare` 接口并列官方可售/不可售/数量与系统 `qty_available/qty_unsellable/qty_in_transit`；
  **系统无台账记录时显示"未同步"+说明，不以 0 冒充，不认定丢货**。

### 3. Sales 销售明细接入

- `NoonSellerLabClient.listSales`：`country_code`、`from_date/to_date`、`page/per_page`。
- `noon_sales_item` 表，唯一键 `(site_code, item_nr)`；重复同步 upsert 更新状态/时间，**不重复入账**。
- `item_nr` 作列表编号（用户决定），保留"仅为平台明细标识、非整单号"说明；无 `order_nr` 字段。
- 平台无图/无客户字段的处理：不伪造、不存储敏感信息。

### 4. 接口与页面

- Controller：`/noon/sellerlab/fbn-inventory/sync`、`/compare`、`/sales/sync`、`/sales/list`（权限复用现有点）。
- Quartz bean `noonSellerLabTask`（`syncFbnAe/FbnSa`、`syncSalesAe/Sa('from','to')`），**默认不自动高频执行**，由用户在系统管理启用并在白名单内。
- 菜单 3900 段：`FBN库存对比`、`Sales销售明细`；两页顶部标注非公开接口、单进程限速、未同步/失败不等于零。
- **ASN 页面/服务/表/任务均未创建**。

---

## 二、验证结果（真库 + 平台只读）

| 项 | 结果 |
|---|---|
| 全模块单测 | 通过（新增 SellerLab 3 项 + 限速 4 项） |
| 前端 build | 通过 |
| AE FBN 同步 | `platformRows=19, writtenRows=19` |
| SA FBN 同步 | `platformRows=21, writtenRows=21`（两页 20+1，与 M14 一致） |
| FBN 重复同步幂等 | 真库 AE 19 / SA 21 行，`synced_at` 更新，无新增行 |
| 库存对比 | 官方数量有值、系统无台账 → 显示"未同步"+说明，差异不算丢货 |
| 非法站点 `XX` | 400 `仅支持 AE/SA`（平台会回退 SA，ERP 侧拒绝） |
| Sales 同步 | `platformRows=40, writtenRows=40` |
| Sales 重复同步 | 首次计数曾显示 80（MySQL upsert 影响行数口径 bug），**已修正为 40**；列表 total 恒 40 |
| Sales 列表 | 40 条，`item_nr` 唯一，状态 Delivered 等 |
| 平台数据 | 未创建/修改/删除任何平台数据 |

### 过程中修复的三个真实 bug

1. `login()` 用 throttle 后 Cookie 头丢失 → 在 throttle 内的真实响应里读取 header。
2. `captured_at` 用同步时刻导致每次同步新增行 → 改为按天归档，`synced_at` 记真实时刻。
3. MySQL `ON DUPLICATE KEY UPDATE` 返回 1(insert)/2(update)，被当行数累加导致重复同步计数翻倍 → 归一为"影响行数>0 记 1 行"。

---

## 三、局限

- Seller Lab 为**非公开内部接口**，正式支持、授权条款、SLA 未确认；未接入生产、未部署、未推送。
- 3 秒限速仅进程内生效；多实例需外部共享限流。
- Sales 页结果随时间变化（M12），虽按 `(site,item_nr)` 幂等，但"按页遍历全量"仍不可假设稳定；`maxPages` 默认 5，需按需调整并配合去重补扫。
- 库存对比仅为简单数量并列；口径（平台 saleable vs 系统 available）、仓库映射、时点差异未逐项对齐，不作账务结论。
- ASN 暂缓：M12/M14 的验证结论保留，未实现任何 ASN 接入代码。

请 Codex 审核。
