# M7 · Noon 平台 SKU 同步（AE）契约与验证报告

日期：2026-11-23  
范围：Noon Partner API → 系统 SKU 管理（md_sku）→ Partner SKU 映射（md_partner_sku）  
站点：仅 UAE（AE），SA 明确不开放

---

## 1. 数据源结论（已用真实账号验证）

| 候选来源 | 接口/导出 | 结果 | 结论 |
|---|---|---|---|
| Offer API | `GET /offer/v1/product/{partner_sku}` | 49 个真实 partner_sku 全部 200，返回 title/brand/AE offer | ✅ 可用，但**必须先知道 partner_sku** |
| FBN 库存台账摘要导出 | `GET /impex/v1/export/category/list` → `POST /impex/v1/export/create` → `POST /impex/v1/export/status` | 导出 270 行，含 `partner_sku/sku/partner_barcode` 三列，49 个唯一 partner_sku | ✅ 选为清单来源 |
| 商品目录导出 | `noon_catalog_catalogexport`，参数 `country=ae&noon_status=ALL` | 任务 COMPLETE，但 CSV 仅表头 0 行 | ❌ 当前账号不适用 |
| FBN catalog items | `GET /fbn/inbound/v1/catalog/items` | 403 only global sellers | ❌ 权限不足 |
| 订单导出 | `noon_noonoms_ordersexport` | 1162 行，但只有 Noon `sku`，无 partner_sku | ⚠ 仅交叉验证账号确有真实商品 |

最终链路：

```text
JWT RS256 登录 → 创建 FBN 台账导出任务 → 轮询 COMPLETE → 下载 CSV
   → 按 (location=AE) 过滤并按 partner_sku 去重 → 逐个调用 Offer API
   → 取 country_code=ae 且 business_model=noon 的 offer
   → 事务内 upsert md_sku + md_partner_sku + 审计
```

---

## 2. 字段映射

| 系统字段 | 来源 | 规则 |
|---|---|---|
| `md_sku.sku_code` | `partner_sku` | 幂等键；>64 字符拒绝并整批回滚 |
| `md_sku.name_en` | Offer `title` | 已存在则回写，未变化不更新 |
| `md_sku.brand` | Offer `brand` | 同上 |
| `md_sku.name_ar/category_code/hs_code/spec_json/status` | 系统原值 | **禁止覆盖**，平台无阿语/类目权威数据 |
| `md_partner_sku.partner_sku` | CSV `partner_sku` | 幂等键之一 |
| `md_partner_sku.sku_id` | upsert 后的 md_sku.id | 外键语义 |
| `md_partner_sku.site_code` | 固定 `AE` | 服务端闸门，忽略客户端其他站点 |
| `md_partner_sku.business_model` | 固定 `noon` | Offer API 当前只支持 noon |
| `md_partner_sku.offer_code` | AE offer `offer_code` | 真实回读 |
| `md_partner_sku.barcode` | CSV `partner_barcode`，缺失退回 Noon `sku` | 不写空串 |
| `md_partner_sku.noon_sku` | Offer `sku` | 新增列，区分平台 SKU 与 partner_sku |
| `md_partner_sku.live_status` | AE offer `live_status` | 真实回读 |
| `md_partner_sku.offer_issues` | AE offer `offer_issues` 原文 JSON | 不改写、不推断 |
| `md_partner_sku.synced_at` | 服务端当前时间 | 每次成功同步写入 |

---

## 3. 代码落点

- `yxerp-noon/com/yx/noon/sync/NoonApiProperties.java`：非密钥连接参数
- `yxerp-noon/com/yx/noon/sync/NoonApiClient.java`：登录、导出、轮询、下载、Offer 回读、CSV 解析
- `yxerp-noon/com/yx/noon/sync/NoonSkuSyncWriter.java`：同一事务 upsert + M2 审计
- `yxerp-noon/com/yx/noon/sync/NoonSkuSyncService.java`：编排与单商品失败隔离
- `yxerp-admin/com/yx/web/controller/noon/NoonSkuSyncController.java`：手动入口 `POST /noon/sync/sku`
- `yxerp-admin/com/yx/web/task/NoonSkuSyncTask.java`：若依定时任务 `noonSkuSyncTask.syncAe()`
- `yxerp-common/Constants.JOB_WHITELIST_STR`：增加 `com.yx.web.task`
- `sql/noon_sku_sync.sql`：新增 `noon_sku` 列（幂等）
- 前端：SKU 管理页与 Partner SKU 页新增「从Noon同步(AE)」按钮；映射页新增 Noon SKU / 上架状态 / 同步时间列

密钥不入库、不入配置、不入日志：`config/secrets/noon_credentials_sensitive.json` 已在 `.gitignore`。

---

## 4. 验证结果（真实数据与真库）

| 验证项 | 结果 |
|---|---|
| Noon 登录 / whoami | 200，账号 `test@p468049.idp.noon.partners` |
| FBN 台账导出 | 270 行，49 个唯一 partner_sku，49/49 Offer API 200 |
| 首次手动同步 | `catalogSkus=49, offersFound=49, skuCreated=49, mappingCreated=49, failed=0` |
| 第二次同步（幂等） | `skuCreated=0, skuUpdated=0, mappingCreated=0, mappingUpdated=49, failed=0` |
| 失败回滚 | 审计快照序列化异常时 49 张写入全部回滚，md_sku 仍为 2 条演示数据 |
| 真库数据 | md_sku 51 条（2 演示 + 49 真实），md_partner_sku 49 条，全部 AE/noon |
| 定时任务 | job_id=100，`noonSkuSyncTask.syncAe()`，cron `0 0 3 * * ?`，状态启用 |
| 定时任务手动触发 | `sys_job_log` 记录成功，耗时 102390ms，`synced_at` 更新 |
| 单测 | `NoonSkuSyncTest` 3/3 通过（CSV 过滤去重、幂等与字段保留、超长回滚） |
| 全模块测试 | `mvn -pl yxerp-noon -am test` 通过 |
| 前端构建 | `npm run build:prod` 通过 |

样例真实数据：

```text
NM000003  → Noon SKU Z210B3A25FEF6EAAF129BZ-1  live=false  offer=edd34a31c256f79e
YXSS000195 → Noon SKU N38692941A                live=true   offer=acfd18e4da71291b
SSM00000103 → Noon SKU Z9A417517D8168E9A8AC4Z-1 live=false  offer=a72bdfe6158a9e3e
```

---

## 5. 已知边界 / 待确认

1. **清单来源覆盖度**：当前 partner_sku 清单来自 FBN 库存台账摘要。若账号有不在 FBN 台账中的商品，本同步不会创建；商品目录导出在 `noon_status=ALL` 下返回空，待确认正确枚举值或 Seller Lab 导出补充。
2. **只同步 AE**：SA 未开放；代码与服务端双重拒绝。
3. **不做刊登/不改平台**：本模块只读 Noon，所有写入均在 ERP 本地。
4. **定时任务依赖凭据文件**：部署机必须提供可读的凭据 JSON；缺失时任务失败并写入若依任务日志。
5. **导出任务限流**：批量导出接口有 60s 窗口限流；并发重复触发可能返回 `Ratelimit exceeded`，前端会显示错误，下次任务重试即可。

---

## 6. 运维说明

- 手动同步：SKU 管理页或 Partner SKU 页点击「从Noon同步(AE)」。
- 定时任务：系统管理 → 定时任务 → `Noon AE商品同步`，目标 `noonSkuSyncTask.syncAe()`，每日 03:00。
- 迁移：新库已写入 schema v0.5 与 `noon_m2_masterdata.sql`；存量库执行 `yx-erp/sql/noon_sku_sync.sql`。
