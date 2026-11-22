# M2 商品与基础资料 — 接口契约（WorkBuddy 定版，2026-11-21）

本文件是 WorkBuddy 与豆包并行开发的**接口契约**。双方按此实现，避免对接不上。任何变更须在此更新并通知对方。

## 技术决策（已核实现有代码）

- 数据访问统一用 **JdbcTemplate**（与现有 `IntakeService`/`FifoConsumptionService`/`ReversalService` 一致），**不用 MyBatis XML**，避免新增 mapper 配置。
- 站点闸门复用 `com.yx.framework.security.site.SiteAuthGuard`：`allowedSiteCodes()` 用于列表过滤，`requireSite(String)` 用于详情/写入校验（超管返回 AE/SA 全集，普通用户仅授权站点，空站点列表必须产出零行）。
- DDL 源：`docs/noon-erp-schema-v0.5.sql` 第 44–136 行（md_partner_account / md_store / md_site / md_warehouse / md_sku / md_partner_sku）。
- 时间：`created_at/updated_at` 由 DB 默认 `CURRENT_TIMESTAMP(3)` 生成，应用不显式传业务时刻（本模块无平台原始时刻字段）。
- 状态：`status` 仅 `ACTIVE`/`INACTIVE`，**不物理删除**（仅置 INACTIVE）。
- 站点范围：仅 `AE`/`SA`，与 `SiteAuthGuard.SUPPORTED_SITES` 一致。
- 隔离验证用一次性 MySQL（脚本 `scripts/verify-*.py` 模式），绝不连生产或已有库；H2 单测覆盖常规路径。
- 全部 DTO 用 Java `record`，包名 `com.yx.noon.masterdata`。

## Service 接口（豆包实现，签名固定）

包：`com.yx.noon.masterdata`

```java
// 统一入参/出参 record（字段名即 DDL 列名 camelCase）
public record StoreInput(String storeNo, Long partnerId, String nameEn, String nameAr,
                  String legalEntity, String status) {}
public record SiteInput(String siteCode, Long storeId, String country, String currency,
                 BigDecimal vatRate, String timezone, String status) {}
public record WarehouseInput(String warehouseCode, String siteCode, String warehouseName,
                      String warehouseType, String fulfillmentMode, Boolean isFbn, String status) {}
public record SkuInput(String skuCode, String spuId, String nameEn, String nameAr, String categoryCode,
                String hsCode, String originCountry, String brand, String specJson, String status) {}
public record PartnerSkuInput(String partnerSku, Long skuId, String siteCode, String businessModel,
                       String offerCode, String barcode, String status) {}

// 5 个 Service 类，4 个统一方法 + update。Input record 不含 id（故 save 语义固定为新增）：
//   list(String siteCode, int offset, int limit) / get(long id) / save(XxxInput in) → 返回新 id
//   update(long id, XxxInput in) → 返回 id（已存在更新）/ setStatus(long id, String status)
// 契约 v1 曾写"save 按 id 分支"，因 Input 无 id 不可实现，v2 更正为 save=新增、update=更新（已与实现对齐）。

// 出参 record：包含 id + 全部列（示例）
public record StoreRow(long id, String storeNo, Long partnerId, String nameEn, String nameAr,
                String legalEntity, String status) {}

// 契约 v3（2026-11-22，Codex 复审分页/搜索意见）：新增查询条件与分页结果 record。
// 条件字段与五个页面一一对应；空/null/空白表示不过滤。status 仅 ACTIVE/INACTIVE，
// 其它值按 MasterDataRules.status 规则拒绝；字符串条件按 LIKE 前后模糊匹配（大小写不敏感），
// 数值条件精确匹配。所有条件与站点授权条件用同一 WHERE 参与 COUNT 与 SELECT，
// count 在 LIMIT/OFFSET 之外统计，保证 total 是「当前授权+筛选条件」下的真实总数。
public record StoreQuery(String storeNo, String nameEn, String status) {}
public record SiteQuery(String siteCode, Long storeId, String status) {}
public record WarehouseQuery(String warehouseCode, String siteCode, String status) {}
public record SkuQuery(String skuCode, String nameEn, String brand, String status) {}
public record PartnerSkuQuery(String partnerSku, Long skuId, String siteCode, String status) {}
public record PageResult<T>(List<T> rows, long total) {}

// 五个 Service 均提供新重载：PageResult<XxxRow> list(String siteCode, int offset, int limit, XxxQuery q)
// 原 list(String siteCode, int offset, int limit) 保留为委托重载（等价于 q=null），兼容既有测试。
```

**校验责任（豆包 Service 内实现，2026-11-22 Codex 审核后定版）**
- **权限矩阵（拍板规则）**：
  - `md_store` 店铺：list 按授权站点 EXISTS 过滤（超管全部含无站点行、普通用户仅授权站点、空授权零行）；get 超管任意，普通用户须店铺有站点行且与授权站点有交集，否则 `AccessDeniedException`；save/update/setStatus **仅超管**（`requireAdministrator`），建店也归超管。
  - `md_sku` SKU：list/get 须至少 1 个授权站点（空授权拒绝，登录≠业务授权）；save/update/setStatus **仅超管**。
  - `md_site` / `md_warehouse` / `md_partner_sku`：保持 `requireSite(siteCode)` 站点闸门，不改超管专属。
- 站点归属：`md_store` 必须通过 `md_site` 关联到站点；`md_warehouse.site_code` / `md_partner_sku.site_code` 必须属于当前用户授权站点；`md_site.site_code` 只允许 AE/SA（DB CHECK + Service 双保险）。
- 唯一性：`uk_store_no / uk_store_site / uk_wh_code / uk_sku_code / uk_partner_sku` 由 DB 唯一键保证；Service 捕获 `DuplicateKeyException` 转成中文业务异常并给出明确字段。
- 引用完整性（新增/修改均校验）：store.partner_id → md_partner_account、site.store_id → md_store、warehouse.site_code → md_site、partner_sku.sku_id → md_sku 且 partner_sku.site_code → md_site，父记录均须存在且 `status='ACTIVE'`；与写入同事务，不做跨事务补偿。
- 不可变字段：`fulfillment_mode` / `site_code` / `store_id` 创建后不可变更（更新时传不同值拒绝，传 null 沿用原值）；并发更新用 `SELECT ... FOR UPDATE` 取快照再校验，防绕过；`is_fbn` 与 warehouse_type=FBN 一致性校验。
- **审计（FR-01.4）**：新增表 `noon_m2_audit_log`（幂等迁移），5 类实体 save/update/setStatus 在业务同事务写 before/after JSON，操作人取真实若依登录人（取不到降级 NULL/UNKNOWN）；业务失败回滚则审计不落库；表 append-only。不用 `@Log`（AOP 不记 before/after 且不同事务）。
- 授权校验：Service 构造注入 `SiteAuthGuard` 与 `MasterDataAudit`；异常统一：参数/业务校验失败抛 `IllegalArgumentException`（中文消息），越权抛 `AccessDeniedException`，Controller 映射到 `AjaxResult.error`（400/403）。

## Controller（WorkBuddy 实现，路径固定）

包：`com.yx.web.controller.noon`，`@RestController`，`AjaxResult` 返回。

| 实体 | 路径 | 方法 |
|---|---|---|
| Store | `/noon/masterdata/store` | GET `/list?siteCode=&offset=&limit=&storeNo=&nameEn=&status=`、GET `/{id}`、POST `/save`、POST `/{id}/update`、POST `/{id}/status?status=` |
| Site | `/noon/masterdata/site` | GET `/list?siteCode=&offset=&limit=&storeId=&status=`、同上（siteCode 同时是站点筛选与授权闸门） |
| Warehouse | `/noon/masterdata/warehouse` | GET `/list?siteCode=&offset=&limit=&warehouseCode=&status=`、同上 |
| SKU | `/noon/masterdata/sku` | GET `/list?siteCode=&offset=&limit=&skuCode=&nameEn=&brand=&status=`、同上（list 也接受 siteCode 但 SKU 为全局主数据，忽略站点过滤） |
| PartnerSku | `/noon/masterdata/partner-sku` | GET `/list?siteCode=&offset=&limit=&partnerSku=&skuId=&status=`、同上 |

**list 响应（v3）**：`AjaxResult.success(PageResult)` → `body.data = { rows: [...], total: <long> }`。
`offset` 仍是数据库行偏移，由前端按 `pageNum` 换算；`total` 必须与 rows 共用同一授权+筛选条件。
`limit` 统一收敛到 `[1,200]`（Controller 与 Service 同口径）。空授权零行的例外：SKU 是跨站共享主数据，
无授权站点时仍按 `AccessDeniedException` 拒绝（403），这是 Codex 首轮整改的既定规则。
字符串条件用 `LOWER(col) LIKE %lower(trim(值))%`，用户输入 `%`/`_` 按模糊通配符语义处理（参数绑定，无注入）。

菜单/权限 SQL：`yx-erp/sql/noon_m2_menu.sql`（WorkBuddy 拥有），权限标识 `noon:masterdata:*`。

## 文件归属（单一 owner）

| 归属 | 路径 |
|---|---|
| 豆包 | `yx-erp/yxerp-noon/src/main/java/com/yx/noon/masterdata/**`、`yx-erp/yxerp-noon/src/test/java/com/yx/noon/masterdata/**`、`yx-erp/sql/noon_m2_masterdata.sql`、`docs/verification/m2-masterdata/doubao-report.md` |
| WorkBuddy | `yx-erp/yxerp-admin/src/main/java/com/yx/web/controller/noon/**`、`yx-erp/sql/noon_m2_menu.sql`、`docs/verification/m2-masterdata/contract.md`、`docs/agent-collaboration.md` |

## 验收标准（M2 整体送 Codex 前必须全中）

1. 全项目 `mvn test` BUILD SUCCESS，无失败/错误/跳过。
2. 一次性隔离 MySQL 实测：迁移脚本在干净库执行成功；唯一性冲突、越权写入、引用缺失被正确拒绝。
3. 授权用户跨站读被过滤为空/拒绝，写入被拒绝；超管可操作 AE/SA。
4. 无物理删除、`fulfillment_mode` 不可变、Offer 字段（`live_status`/`offer_issues`/`synced_at`）本模块不伪造写入。
5. 双方报告与协作记录写回，未决项列清；不连生产、不推送、不碰密钥。
6. 分页/搜索（Codex 2026-11-22 复审）：五个 list 接口均返回真实 total 且搜索条件生效；翻页、改变每页条数、查询重置有自动化测试；隔离环境有真实浏览器操作证据。
