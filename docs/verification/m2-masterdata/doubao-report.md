# M2 后端内核交付报告（豆包）

执行者：豆包（后端内核 owner）｜日期：2026-11-21（UTC+8）｜依据：`docs/verification/m2-masterdata/contract.md`（public record 修正版）

## 1. 改动文件清单（仅本人归属路径，未触碰他人文件）

| 文件 | 说明 |
|---|---|
| `yx-erp/sql/noon_m2_masterdata.sql` | 新增；6 张 md_ 表（partner_account/store/site/warehouse/sku/partner_sku），逐字段对齐 v0.5 第 44–136 行；`CREATE TABLE IF NOT EXISTS` 幂等；无种子、无菜单 |
| `yx-erp/yxerp-noon/src/main/java/com/yx/noon/masterdata/`（17 个文件） | 5 个 public 入参 record、5 个读模型 record、`MasterDataRules`（包内校验工具）、`MasterDataAudit`（同事务审计）、5 个 Service（JdbcTemplate + 构造注入 SiteAuthGuard/MasterDataAudit） |
| `.../src/test/java/com/yx/noon/masterdata/MasterDataServiceTest.java` | H2(MySQL 模式) + 真 MyBatis 站点 Mapper，11 个用例 |
| `.../src/test/java/com/yx/noon/masterdata/MySqlMasterDataIT.java` | 一次性隔离 MySQL IT（`NOON_MYSQL_TEST_PORT` 开启），3 个用例 |
| 本报告 | `docs/verification/m2-masterdata/doubao-report.md` |

## 2. 契约落点

- **方法**：`list(siteCode, offset, limit)` / `get(id)` / `save(Input)` / `setStatus(id, status)` 齐全；另加 `update(long id, Input)`（原因见未决 P0）。
- **站点**：list 用 `allowedSiteCodes()` 作 IN 绑定，空集合直接返回空列表（零行，不省略 WHERE）；get/写入一律用**记录真实 siteCode** 过 `requireSite`，不信任入参；`site_code` 仅 AE/SA（Service 校验 + 真库 CHECK 双保险）。
- **唯一性**：`DuplicateKeyException` 按 uk 名转中文业务异常（店铺编号 / 该站点已存在 / 仓库编码 / SKU 编码 / Partner SKU 已存在）。
- **引用完整性**：`site.store_id→md_store`、`warehouse.site_code→md_site`、`partner_sku.sku_id→md_sku(ACTIVE)` 均拒绝缺失。
- **不可变/一致**：`fulfillment_mode` 已有值不等则拒绝（传空=沿用）；`warehouse_type=FBN ⇔ is_fbn=true`，其余必须 false。
- **无删除**：只有 ACTIVE/INACTIVE，无 delete 方法；`created_at/updated_at` 交给 DB 默认。
- **Offer 边界**：`md_partner_sku` 只写 partner_sku/sku_id/site/business_model/offer_code/barcode/status；`live_status`、`offer_issues`、`synced_at` 不写入（单测/IT 断言为 NULL）。

## 3. 验证命令与原文输出

环境说明：本机无全局 mvn，临时下载 Maven 3.9.9 至 `/tmp/noon-tools`（未改项目文件、未全局安装）；JDK 21 编译 target 17。

1) 全项目编译
```
cd yx-erp && /tmp/noon-tools/apache-maven-3.9.9/bin/mvn -q -DskipTests compile
EXIT=0
```
2) H2 单测（本切片）
```
mvn -q -pl yxerp-noon -am test -Dtest=MasterDataServiceTest -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in com.yx.noon.masterdata.MasterDataServiceTest
BUILD SUCCESS
```
3) 全项目测试（含 WorkBuddy 已落地的 controller 编译）
```
cd yx-erp && mvn test
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0   (framework)
[INFO] Tests run: 107, Failures: 0, Errors: 0, Skipped: 0  (yxerp-noon，含 masterdata 11)
[INFO] BUILD SUCCESS
```
4) 隔离 MySQL IT：本机未执行（`127.0.0.1:3306` 关闭，无 mysql/docker 客户端），由 WorkBuddy 的 `scripts/verify-m2-runtime.py`（一次性 MySQL 8.4）代跑。
   - 首次实测：`2 tests / 1 failure`，失败点 `MySqlMasterDataIT#migrationIsIdempotentAndRealConstraintsHold` 第 119 行——`site_code='XX'` 已被 DB CHECK 正确拒绝，但 MySQL 8.4 将 CHECK 违反（SQLState HY000 / error 3819）映射为 `UncategorizedSQLException`，测试却断言 `DataIntegrityViolationException`。功能正确、断言类型写窄。
   - 已整改：两处 DB 约束断言（site_code CHECK、非法 spec_json）统一放宽为公共父类 `DataAccessException`，并注明 8.4 的映射差异；IT 已重新 test-compile 通过（EXIT=0）。
   - **复跑全绿（WorkBuddy 回填）**：`MySQL JUnit: tests=2 errors=0 failures=0 skipped=0`、`maven_exit=0`；证据目录 `/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m2-4f7pcu29/`。连续两次迁移的幂等、CHECK、JSON 约束均在真库验证通过。
   - **整改后真库复跑（本次，`scripts/verify-m2-runtime.py` 一次实例）**：`MySQL JUnit: tests=3 errors=0 failures=0 skipped=0`、`maven_exit=0`；证据目录 `/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m2-ghat5h56/`。新增审计表与引用/审计负例均在真库通过。

H2 说明：H2 下 CHECK 常量集求值不稳定（实测报 `Check constraint invalid`），故 H2 用宽松建表（spec_json 文本列、仅唯一键），**CHECK/JSON/表选项由真实 MySQL IT 验证**；Service 侧非法 site_code 另有断言。

## 4. 未决项（需 WorkBuddy 确认或修契约）

1. **P0 契约缺陷**：5 个 `XxxInput` 无 id，而契约写“save: id<=0 新增、id>0 更新”不可实现。当前实现 `save`=新增、`update(long, Input)`=更新；已看到你的控制器用 `/update` 调 `service.update`，**代码已对齐**，但契约文字需同步（否则 Codex 审核会判契约与实现不一致）。
2. **P1 店铺无站点字段**：`StoreInput` 无 siteCode，新增店铺无法 `requireSite`；现策略=新增只要求登录，详情/更新按其既有 md_site 与授权站点求交集，无站点行时仅要求登录。若要“仅超管可建店铺”，请明示。
3. **P1 未定义行为**：`get` 不存在时抛 `IllegalArgumentException`（Controller 统一 400）；若你要返回 null/AjaxResult.success(null)，请改契约我配合。
4. **P2 分页**：`offset<0` 抛 IAE；`limit<1`→20、`>200`→200（契约未定上限）。
5. **P2 PartnerSkuRow**：按契约示例只含写入列，不含 `live_status/offer_issues/synced_at`；若前端要展示 Offer 状态，需契约补 3 个只读字段（且明确仍由后续同步写入）。
6. **P2 引用范围**：只做契约列的三条；未校验 `store.partner_id→md_partner_account`（契约未要求、该表可能为空），也未要求 `partner_sku.site_code` 在 md_site 有行（仅过授权）。若要加，请定级。
7. **P2 site_code 可改（已定级整改，见第 9 条）**：原实现允许改 site_code 但 `sys_user_site` 授权行不跟随，已改为建后不可变。
8. **P2 迁移真库验证**：已在隔离 MySQL 8.4 实测（WorkBuddy 脚本），CHECK 与非法 JSON 均被 DB 正确拒绝；断言异常类型写窄问题已按第 10 条整改并复跑全绿（见第 3 节）。
9. **WorkBuddy 定级**：site_code/store_id 建后不可变，已整改（`SiteService.update` 传不同值拒绝、传 null 沿用；H2 新增 1 用例，noon 104 全绿）。
10. **已修**：`MySqlMasterDataIT` 的 DB 约束断言由 `DataIntegrityViolationException` 放宽为 `DataAccessException`（MySQL 8.4 CHECK 违反 → `UncategorizedSQLException`），site_code 与非法 JSON 两处同改，仅改该 IT 与本报告。

## 5. Codex 审核整改（2026-11-22，豆包）

### 5.1 权限（意见 2、5）

| 实体 | list | get | save/update/setStatus |
|---|---|---|---|
| STORE | 普通用户按授权站点 `EXISTS` 过滤、空授权零行；超管全部（含无站点行），传 siteCode 时按单站过滤 | 超管任意；普通用户要求店铺有≥1 站点行且与授权站点有交集，否则 `AccessDeniedException("没有该店铺的站点访问权限")` | 仅超管（`requireAdministrator`），初始化建店也归超管 |
| SITE / WAREHOUSE / PARTNER_SKU | 授权站点 IN 绑定，空授权零行（不变） | 记录真实 site_code 过 `requireSite`（不变） | `requireSite`（不变，未改超管专属） |
| SKU | 要求至少 1 个授权站点，空授权 `AccessDeniedException`；siteCode 参数忽略 | 同左 | 仅超管 |

初始化路径：超管建店（无站点行）→ 超管建站点（绑定 store）→ 普通用户按站点可见；迁移不造店铺种子。

### 5.2 引用完整性（意见 3）

`store.partner_id→md_partner_account(ACTIVE)`（新增）、`site.store_id→md_store(ACTIVE)`（由“存在”收紧）、
`warehouse.site_code→md_site(ACTIVE)`（收紧）、`partner_sku.sku_id→md_sku(ACTIVE)`（原已有）、
`partner_sku.site_code→md_site(ACTIVE)`（新增）。全部校验与写入同事务，不做跨事务补偿。
并发：更新先 `SELECT ... FOR UPDATE` 取快照，再执行带原值的条件 UPDATE（site_code / store_id /
fulfillment_mode / sku_code 等），影响 0 行即失败，防并发绕过不可变字段；唯一键仍捕获
`DuplicateKeyException` 转中文字段提示。

### 5.3 审计（意见 4，FR-01.4）

`noon_m2_audit_log`（并入 `noon_m2_masterdata.sql`，`IF NOT EXISTS` 幂等）：
`id, entity_type, entity_id, action(INSERT/UPDATE/STATUS), operator_id, operator_name,
before_json, after_json, changed_at`。5 类实体的 save/update/setStatus 均在业务事务内写审计；
before/after 为行快照 JSON（新增 before=NULL，after 含 DB 生成列）；operator 取
`SecurityUtils.getLoginUser()`，取不到时降级 `operator_id=NULL / operator_name=UNKNOWN`（HTTP 入口不会出现）。
失败回滚不落审计（H2 与真库各有负例）；表 append-only，应用只 INSERT/SELECT；不可变字段靠
FOR UPDATE + CAS 双保险，并发改不能绕过。

### 5.4 测试清单与原文

- H2 11 项：店铺权限矩阵、SKU 读/写权限、CRUD 与站点过滤、仓库/映射 CRUD、唯一键转中文、
  引用存在+ACTIVE 负例、fulfillment_mode 不可变与 isFbn 一致、site_code/store_id 不可变、
  跨站越权与空授权、Offer 列不写入/非法 JSON、审计 before/after + 失败回滚。
- 真库 3 项：迁移幂等+CHECK/JSON/审计落库、引用与审计回滚、跨站写拒绝。

```
cd yx-erp && mvn -o -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 test
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0   (framework)
[INFO] Tests run: 107, Failures: 0, Errors: 0, Skipped: 0  (yxerp-noon，含 masterdata 11)
BUILD SUCCESS
```
```
python3 scripts/verify-m2-runtime.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
MySQL JUnit: tests=3 errors=0 failures=0 skipped=0
maven_exit=0
```
证据目录：`/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m2-ghat5h56`

### 5.5 需你处理/未决

1. **契约需你改**（我不碰）：店铺写仅超管、超管 list 含无站点行；SKU 读需至少 1 站点/写仅超管；
   引用校验含 ACTIVE；审计表与“同事务、失败不落库”策略；新增 `update(id, Input)` 已在上版同步。
2. 审计表无外键、无归档策略；M2 量级可接受，后续量增需评估。
3. operator 降级仅理论路径；若将来调度任务直接调 Service，会出现 UNKNOWN，需再定义。
4. H2 仍不验 CHECK/JSON（由真库 IT 负责）；H2 与真库分工未变。

## 6. 边界声明

只写本人归属的 4 个路径；未改 pom、`docs/noon-erp-schema-v0.5.sql`、Controller、菜单 SQL、`docs/agent-collaboration.md`；未读 `config/secrets/`；未连生产/已有库；未 git push。

## 7. 契约 v3 分页/搜索整改（2026-11-22，回应 Codex「页面分页与搜索待修」）

基线 `403241e`。本轮只改**后端 Service 层**，仅落在本人 owner 路径：`com.yx.noon.masterdata`（main）、同名测试包、本报告。未改 Controller、前端、`scripts/`、`contract.md`、协作记录、SQL。

### 7.1 改动文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `.../masterdata/StoreQuery.java` | 新增 | `(String storeNo, String nameEn, String status)` |
| `.../masterdata/SiteQuery.java` | 新增 | `(String siteCode, Long storeId, String status)` |
| `.../masterdata/WarehouseQuery.java` | 新增 | `(String warehouseCode, String siteCode, String status)` |
| `.../masterdata/SkuQuery.java` | 新增 | `(String skuCode, String nameEn, String brand, String status)` |
| `.../masterdata/PartnerSkuQuery.java` | 新增 | `(String partnerSku, Long skuId, String siteCode, String status)` |
| `.../masterdata/PageResult.java` | 新增 | 泛型 `(List<T> rows, long total)`；`empty()/of()` 便捷工厂，rows 拷贝为不可变 |
| `.../masterdata/MasterDataRules.java` | 修改 | 新增 `like()`（`%trim(lower(值))%`，空白→null）、`statusFilter()`（空白不过滤，非空复用 `status()` 校验）、`intersectSites()`（闸门站点 ∩ 条件站点） |
| `.../masterdata/StoreService.java` | 修改 | 新增 4 参 list；3 参委托；授权 EXISTS 与筛选同一 WHERE |
| `.../masterdata/SiteService.java` | 修改 | 同上；入参 siteCode 为闸门，与 `SiteQuery.siteCode` 取交集 |
| `.../masterdata/WarehouseService.java` | 修改 | 同上 |
| `.../masterdata/SkuService.java` | 修改 | 同上；忽略 siteCode 过滤，仍要求 ≥1 授权站点 |
| `.../masterdata/PartnerSkuService.java` | 修改 | 同上（交集站点 + partnerSku 模糊 + skuId 精确） |
| `.../src/test/java/com/yx/noon/masterdata/MasterDataServiceTest.java` | 修改 | 新增 7 个用例（H2），共享既有夹具/风格 |
| 本报告 | 追加 | 本节 |

### 7.2 关键实现说明

1. **统一 WHERE、SELECT 与 COUNT 共用同一条件与参数**：每个 Service 先拼一个 `StringBuilder where` 和 `List<Object> args`，`SELECT COUNT(*) … + where` 用 `args` 计数（在 LIMIT/OFFSET 之外），再复制 `args` 追加 `size/offset` 做 `SELECT … ORDER BY id LIMIT ? OFFSET ?`。占位符与参数顺序严格一致：站点 IN 参数在前，筛选参数在后，分页参数只加在 SELECT 侧。
2. **权限规则与现有实现完全一致**：
   - STORE：超管不传 siteCode 时全量（含无站点行店铺）；普通用户按授权站点 `EXISTS` 过滤；空授权直接 `PageResult.empty()`（total=0）；传 siteCode 先 `requireSite` 再按单站过滤（同旧实现）。
   - SITE / WAREHOUSE / PARTNER_SKU：`MasterDataRules.sites(siteCode, guard)` 作授权闸门（未授权抛 `AccessDeniedException`，空授权零行），查询结果再与 `Query.siteCode` 取交集。
   - SKU：`requireSiteReader()` 保持「至少 1 个授权站点」，空授权抛 `AccessDeniedException`（与 3 参重载一致），siteCode 忽略。
3. **条件语义**：字符串一律 `LOWER(col) LIKE ?`，绑定值为 `%trim(小写值)%`（大小写不敏感、去首尾空白）；数值 `storeId/skuId` 精确 `=?`；`status` 非空时复用 `MasterDataRules.status()` 校验并规范化（非法值抛 `IllegalArgumentException`），空白表示不过滤。
4. **站点条件不重复冲突**：`intersectSites(gate, querySite)` 在两者都有时只保留交集单值——相同站点只绑定一次；不同站点交集为空即零行（不越权、不报错、不把两个站点写成互斥条件）。
5. **旧签名兼容**：5 个 `list(siteCode, offset, limit)` 全部改为 `return list(siteCode, offset, limit, null).rows();`，行为与旧实现等价，既有 11 个用例零改动通过。

### 7.3 新增测试（H2，`MasterDataServiceTest`，11 → 18）

| 用例 | 覆盖 |
|---|---|
| `storeQueryFiltersHitMissAndRealTotal` | 命中/未命中、大小写不敏感、trim、多条件 AND、`total` 不受 LIMIT 截断、status 过滤与非法 status 拒绝 |
| `storePagingWalksWithoutDuplicatesOrGaps` | 7 行×limit2 逐页，顺序与完整列表一致（无重复/遗漏）、每页条数、total 恒定 |
| `storeNonAdminFilterAndTotalRespectSiteAuthorization` | 普通用户跨站过滤：total 只统计授权站点内命中行；未授权店铺命中条件也不计入 total；空授权 total=0 |
| `siteQueryUsesAuthGateAndQuerySiteIntersection` | 条件站点/店铺命中与未命中、total、翻页、入参站点与 Query 站点相同不冲突、仅 AE 用户查 SA 得零行、空授权零行 |
| `warehouseQueryPagingAndCrossSiteScope` | 仓库编码模糊命中/未命中、total 不受 LIMIT 截断、翻页、AE 用户跨站零行、AE+SA 双站点 IN 多占位符 + 分页参数顺序 |
| `skuQueryPagingAndIgnoresSiteCodeFilter` | skuCode/nameEn/brand 命中与未命中、多条件 AND、翻页、total、siteCode 忽略、空授权按既有规则拒绝 |
| `partnerSkuQueryPagingAndSiteScope` | partnerSku 模糊、skuId 精确、Query 站点筛选、条件站点与记录站点交集、翻页、普通用户只统计授权站点、空授权 total=0 |

翻页断言用公共方法 `assertPageWalk(expected, pageSize, page)`：逐页取 id 并与完整列表逐位比对，同时校验每页条数。

### 7.4 测试命令与原文

环境：本机无全局 mvn，用 `/tmp/apache-maven-3.9.11/bin/mvn`；`/tmp/noon-maven-settings.xml` 存在；`/tmp/noon-maven-repo` 不存在，沿用既有离线仓库 `/tmp/noon-m2`（与 Codex 复审同源）。

单类回归：
```
cd /Users/dream/Work/noonERP/yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 \
  -pl yxerp-noon -am test -Dtest=MasterDataServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
```
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.443 s -- in com.yx.noon.masterdata.MasterDataServiceTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
EXIT=0
```

全项目测试（无 `-pl`，含 Controller 所在 admin 模块编译）：
```
cd /Users/dream/Work/noonERP/yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 test
```
```
[INFO] Results:                      (yxerp-framework)
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0

[INFO] -------------------------< com.yx:yxerp-noon >--------------------------
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 -- in com.yx.noon.reversal.ReversalServiceTest
[INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0 -- in com.yx.noon.intake.IntakeServiceTest
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 -- in com.yx.noon.fifo.FifoConsumptionServiceTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- in com.yx.noon.masterdata.MasterDataServiceTest
[INFO] Results:                      (yxerp-noon)
[INFO] Tests run: 114, Failures: 0, Errors: 0, Skipped: 0

[INFO] Reactor Summary for yxerp 3.9.2:
[INFO] yxerp-framework .................................... SUCCESS [  1.567 s]
[INFO] yxerp-noon ......................................... SUCCESS [  3.543 s]
[INFO] yxerp-quartz ....................................... SUCCESS [  0.586 s]
[INFO] yxerp-generator .................................... SUCCESS [  0.041 s]
[INFO] yxerp-admin ........................................ SUCCESS [  0.586 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  7.142 s
EXIT=0
```
完整日志留存 `/tmp/noon-m2-v3-full-test.log`（临时文件，不入库）。

### 7.5 未决项

1. **Controller 已由 WorkBuddy 同步接入（本轮只读确认，未改）**：5 个 Controller 均已 import `PageResult` 并调用 4 参重载（`service.list(siteCode, offset, …, q)`），全项目构建中 `yxerp-admin` `SUCCESS`，说明 record 字段与调用签名一致。**接口口径差异（提请 WorkBuddy 定）**：Controller 把 `limit` 收敛到 `[1,500]`，而 Service 既有上限是 200（`MasterDataRules.MAX_PAGE_SIZE`），每页 >200 时实际最多返回 200 行、`total` 仍正确。若要支持 500/页，需统一上限（改 Service 常量属我方、改 Controller 属 WorkBuddy，需先对齐再改一端）。
2. **SKU 空授权口径**：按「权限规则与现有实现完全一致」保留 `AccessDeniedException`（登录≠业务授权），不是零行；与契约 v3「空授权零行」的文字存在例外，建议契约补一句 SKU 例外（我不改契约）。
3. **LIKE 未转义 `%`/`_`**：按契约写法 `%值%` 实现（参数绑定，无注入风险）；若前端需要严格字面匹配，需定级后改为 `ESCAPE` 方案。
4. **`storeId`/`skuId` 传非正数**抛 `IllegalArgumentException`（与既有 id 校验一致）而非零行；前端应仅在选中值时传参。
5. **真库未在本轮复跑**：新 SQL 仅在 H2(MySQL 模式) 验证；建议 WorkBuddy 在隔离 MySQL 或 e2e 中补五页真库分页/搜索的正反证据（本轮按要求只跑离线 Maven 全项目测试）。
6. **前端页码↔offset 换算**属 WorkBuddy/前端 owner，本轮未改前端。
