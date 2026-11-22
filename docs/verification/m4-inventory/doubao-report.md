# M4 库存第一轮「采购收货 → 入库 → 四态台账」后端内核交付报告

执行者：豆包（MainAgent）｜日期：2026-11-22｜依据：`contract.md` v1 + 用户本轮正式授权 + `docs/development-modules.md` M4/第 6 条
状态：**后端内核完成，H2 11/11、隔离 MySQL IT 4/4、全项目离线测试 167/167 通过**

## 1. 交付范围（只动了 owner 目录）

新增（均为新包 `com.yx.noon.inventory`，未改任何 M2/M3/fifo/reversal/intake 源码、SQL、Controller、前端、E2E 脚本、契约、协作文档）：

| 文件 | 职责 |
|---|---|
| `InboundInput` / `InboundLineInput` / `InboundResult` | 契约 §3 入参、行、结果 record（行只收 `purOrderItemId + qtyQualified`） |
| `InboundRow` / `InboundItemRow` / `InboundDetail` | 入库单头/行/详情（头无 site_code 列，站点由仓库带出） |
| `BatchRow` / `LedgerRow` | FIFO 批次、四态台账只读行 |
| `InventoryRules`（包内 final 类） | 必填/幂等键格式/分页边界/站点编码/SQL 占位符/UTC+8 时刻/金额 4 位/内容指纹 |
| `InventoryAudit`（`@Component`） | 写 `noon_m4_inventory_audit`（同业务事务，失败一起回滚） |
| `InboundService`（`@Service`） | `confirm` / `resultOf` / `list` / `get` / `listBatches` / `listLedger` |
| `InventoryServiceTest`（H2） | 11 个用例 |
| `MySqlInventoryIT`（opt-in 真库） | 4 个用例，含入库批次被现有 FIFO 消费的端到端 |

## 2. 契约映射（实现口径）

### 2.1 方法签名

```java
@Transactional(rollbackFor = Exception.class)
public InboundResult confirm(InboundInput in)

@Transactional(readOnly = true)
public InboundResult resultOf(String requestId)

public PageResult<InboundRow> list(String siteCode, int offset, int limit)
@Transactional(readOnly = true)
public InboundDetail get(Long id)
public PageResult<BatchRow> listBatches(String siteCode, int offset, int limit, Long skuId)
public PageResult<LedgerRow> listLedger(String siteCode, int offset, int limit)
```

全部与契约 §3 一致（`yxerp-admin/InventoryController.java` 直接调用可编译通过，已验证 `yxerp-admin` 整模块 `compile` 退出码 0，未改该文件）。

### 2.2 confirm 执行顺序（对齐 M3 `ReceiptService` v2.1）

1. 入参：`purOrderId/warehouseId` 正整数、`requestId` 8–64 位 `[A-Za-z0-9_-]`、至少 1 行、`qtyQualified > 0`、同一 `purOrderItemId` 不得重复。
2. **授权经仓库归属**：`SELECT site_code,status FROM md_warehouse WHERE id=?`；不存在 400、非 ACTIVE 400、未绑站点 400；`guard.requireSite(仓库.site_code)` → 403；请求体 `siteCode` 非空时必须与仓库站点一致，否则 400（不参与授权）。
3. `SELECT pur_order ... FOR UPDATE`（锁单串行化同单确认）：不存在 400；`site_code` 与仓库站点不一致 → 400「禁止跨站入库」。
4. **内容指纹**：`SHA-256(purOrderId|warehouseId|按 purOrderItemId 升序的 purOrderItemId:qtyQualified)`，UTF-8，64 位小写十六进制。行序无关；换行/换量必变。
5. 同键判重（授权 > 指纹 > DONE 重放 > 状态闸门）：DONE 且指纹一致 → 反序列化 `result_json` 原样返回 `replayed=true`，**零业务/审计写入**；DONE 但指纹不同 → 400「内容不一致」；PENDING → 400「处理中」；不存在 → `INSERT ... 'PENDING'` 占位，撞 `request_id` 唯一键 → 当前读重判。
6. 状态闸门：`pur_order.status ∈ {RECEIVED, CLOSED}`，否则 400。
7. 逐行（先按 `purOrderItemId` 升序、`FOR UPDATE` 锁行）：校验行属于该单；从 `noon_m3_receipt` 取 `SUM(qty)` 为累计收货量、最近一条 `unit_cost_cny` 为权威成本；SKU 取 `pur_order_item.sku_id`；校验 `0 < qtyQualified <= 累计收货 - 累计已入库`（见 §4-④）。
8. 写入：`inv_inbound`（`IN:`+UUID、CONFIRMED、operator_id）→ 每行 `inv_inbound_item`（`B:IN:`+UUID、`qty=qty_qualified`、权威成本）→ `inv_batch`（OPEN/PURCHASE/parent NULL/split_seq 0/CNY/fx 1/received_at=UTC+8 毫秒）→ 台账累加 → `inv_stock_move`（`IM:`+UUID、INBOUND、+qty、ref=inbound_no）→ 审计摘要。
9. 事务内 `markDone` 写回 `status='DONE' + result_json(replayed=false)`；返回 `InboundResult`。

### 2.3 只读方法

- `resultOf`：`requestId` 非法/不存在/未 DONE 一律 `null`，不写表；存在且 DONE → 按幂等行 `pur_order_id` 取采购单**真实站点** `requireSite`，再返回缓存结果（`replayed=true`）。
- `list/get/listBatches/listLedger`：`siteCode` 传值先 `requireSite`（未授权 403、非 AE/SA 400），不传则用 `guard.allowedSiteCodes()`；**授权集合为空直接返回零行**，SQL 始终带 `site_code IN (...)`，不存在“省略 WHERE 读全表”。`get` 以入库单所属仓库站点判权；`listBatches` 支持 `skuId` 精确过滤（`<=0` 400）。

## 3. 生成 / 沿用 / 手写范围

- **本轮后端 100% 手写 JdbcTemplate，未运行若依生成器**。原因：`confirm` 是「幂等占位 → 采购单/收货行守恒 → 6 张表同一事务写入 → 批次-台账-流水-审计一致」的跨表状态机，生成器的单表 CRUD 骨架（Mapper/XML/Service 模板）无法承载，改造量大于手写；且按第 6 条新规，生成器骨架与原始产物由 WorkBuddy 负责，本轮 `docs/verification/m4-inventory/generated/**` 不在我的 owner。
- **沿用（只读复用，不改源码）**：`SiteAuthGuard`（授权唯一入口）、`masterdata.PageResult`、M3 幂等/指纹/`FOR UPDATE` 当前读范式（`ReceiptService` v2.1）、schema v0.5 的库存表与 M3 收货表语义；真库 IT 只调用 `FifoConsumptionService.postDelivery` 验证消耗，未改其源码。
- **手写新增**：上述 8 个 record + `InventoryRules` + `InventoryAudit` + `InboundService` + 2 个测试类。表结构 DDL 未碰任何 `.sql`；H2 建表在测试文件内，真库直接跑 WorkBuddy 的 `noon_m4_inventory.sql` + M3 迁移 + schema v0.5 抽取。

## 4. 验证结果

### 4.1 H2 `InventoryServiceTest`：11 用例全部通过（连跑 4 次稳定）

真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper + 真 `SiteAuthGuard`；站点授权表用 `noon_wp1_site_auth.sql`，库存表在测试内自建（宽松 DDL，CHECK 由真库 IT 验证，沿用 M3 做法）。

| 用例 | 覆盖 |
|---|---|
| `confirmCreatesInboundBatchLedgerMoveAndAudit` | 两行金额/成本/批次/台账/流水/审计全字段；行序打乱仍按 itemId 升序；DRAFT 单 400 |
| `clientSuppliedCostSkuAndBatchNoAreIgnored` | JSON 夹带 `unitCost=0.01/skuId=9999/batchNo=HACK/qty=100` 反序列化后丢弃；落库用收货成本 12.34、采购行 SKU、`B:IN:` 批次号、数量 3 |
| `authorizationAlwaysFollowsWarehouseOwnership` | 无站点 403、仅 AE 用户 SA 仓库 403、仓库不存在 400、仓库 INACTIVE 400、请求体 siteCode 不一致 400、采购单跨站 400；被拒均零写入 |
| `conservationRejectsOverInboundBadLinesAndMissingReceipt` | 超累计收货 400、qty=0 400、同键重复行 400、空行 400、行不属订单 400、无收货 400、累计已入库后再次入库 400（台账不虚增） |
| `idempotentReplayContentConflictPendingAndNewRequest` | 重放 `replayed=true` 且批次/流水/审计/台账零变化、`result_json` 保留 `replayed:false`；同键换量 400；同键换单 400；PENDING 400；新键二次入库累加台账且单行 |
| `failedConfirmRollsBackEverythingAndSameRequestIdCanRetry` | 失败后 request/inbound/batch/ledger/move/audit 全为 0，PENDING 消失；同键重试成功 |
| `concurrentSameRequestIdBooksOnce` | 双线程同键：仅 1 FRESH，另一个 REPLAYED/明确拒绝；入库单/批次/流水/台账/审计各 1，台账只加 3 |
| `ledgerAccumulatesAndRejectsCrossSiteReuse` | 5+7 累加为 12；台账站点 SA 与 AE 仓库冲突 400 且不新增入库单 |
| `resultOfIsReadOnlySiteAuthorizedAndTwoState` | DONE 返回 replayed=true 且不写表；不存在/null/空/短键 → null；PENDING → null；跨站/无站点 403 |
| `listGetBatchesAndLedgerRespectSiteAuthorizationAndPaging` | 超管 3 单、SA 1 单、非法站点 400；`offset/limit` 翻页无重复无遗漏且 total 不受 LIMIT 影响；AE 用户只见 AE、跨站 403；空授权零行；详情头+行+itemCount；`get` 不存在/null 400 |
| `contentFingerprintMatchesContractCanonicalForm` | 固定值：`12|7|3:60,5:40` → `4b1cf6…f44`（与行序无关） |

### 4.2 隔离 MySQL 8.4.7 `MySqlInventoryIT`：4 用例全部通过

一次一库（`NOON_MYSQL_TEST_PORT` + 随机库名，结束 drop），不连生产。建表：schema v0.5 抽取真实 DDL（`md_sku`/`md_warehouse`/`sal_order`/`sal_order_item`/`inv_batch_consumption`/`int_webhook_event`）+ M3 迁移 + **WorkBuddy 的 M4 迁移** + FIFO 隔离层迁移。

| 用例 | 结果 |
|---|---|
| `migrationIsIdempotentAndRealCheckConstraintsHold` | M4 迁移二次执行成功（7 表齐）；真 CHECK 拒绝：`qty_qualified>qty`、`qty=0`、`qty_remaining>qty_in`、非法批次 status、台账负值、`qty_delta=0`；OPEN/PURCHASE/NULL/0/CNY/1/received_at/站点样例断言通过 |
| `concurrentSameRequestIdCreatesOneBatchAndOneLedgerAccumulation` | 真 InnoDB 双线程同键：1 FRESH + 1 REPLAYED/REJECT，入库单/批次/流水/台账/审计各 1，台账=3 |
| `failedConfirmLeavesNoPartialDataAndSameRequestIdRetries` | 失败后 6 表零行、幂等行消失；同键重试成功并 DONE |
| `inboundBatchesAreConsumedByExistingFifoAndLedgerStaysInSync` | **端到端证据**见 §4.3 |

### 4.3 端到端：M4 入库批次 → 现有 FIFO 出库（真库）

场景：采购单 10 件 @ 12.3400 已收货 → M4 入库 10（批次 1 个，`qty_remaining=10`，台账 `available=10`）→ 真实 `sal_order/sal_order_item`（qty 10）→ `FifoConsumptionService.postDelivery`：

| 步骤 | 断言（全部通过） |
|---|---|
| 妥投 4（事件 1） | `posted=true`；批次 `qty_remaining=6` 且仍 OPEN；台账 `available=6`；`inv_batch_consumption` 1 行：`batch_id` 指向 M4 批次、`qty=4`、`unit_cost=12.3400`（M3 权威成本）、`amount=49.3600`（HALF_UP 4 位）、`move_type='SALE'`、`sign=+1`、`sal_order_item_id` 正确；`sal_order_item.qty_delivered=4` |
| 妥投 6（事件 2） | 批次 `qty_remaining=0` 且 `status='CLOSED'`；台账 `available=0`；消耗 2 行合计 10；`qty_delivered=10`；`noon_fifo_review` 0 行（全链路合法） |

即：**批次字段（OPEN、received_at、同站点、同仓库、PURCHASE、parent NULL、split 0）与 FIFO `openBatches()` 完全对齐，M4 生成的库存可被现有出库链路直接消耗，且批次剩余与台账可用同步扣减。**

### 4.4 全项目离线测试

命令（用户指定）：

```
cd yx-erp && MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o \
  -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
```

结果：`BUILD SUCCESS`，**167 用例，0 失败 0 错误 0 跳过**（framework 29 + yxerp-noon 138；本模块新增 11）。另验证 `-pl yxerp-admin -am compile` 成功（WorkBuddy 的 Controller 调用签名匹配）。

## 5. 未覆盖项（契约 §5，如实标注，不宣称完成）

1. 调拨 `inv_transfer`、头程 `log_shipment_cost`、ASN `log_asn`、盘点 `inv_stocktake`、在途/拆批完整状态机：本轮不建 Java。
2. 拆批血缘字段（`parent_batch_id/split_seq/split_reason/transfer_no/asn_no`）本轮只写 NULL/0；无拆批逻辑。
3. **不合格量不进 `qty_unsellable`**：本轮 `inv_inbound_item.qty = qty_qualified`，`qty_in_transit/qty_reserved/qty_unsellable` 一律不动；短少差额不生成任何库存，待 M5 待检/退货流程。
4. 头程/关税/包材不摊入 `unit_cost`（Q8）；只写采购价与 `currency='CNY'`、`fx_rate=1`。
5. 退货入库、红冲后的负向批次、平台 FBN Inbound 对接均不做（复用现有 Reversal/Intake 不在本轮触发）。
6. 菜单/权限/HTTP E2E/浏览器 UI（WorkBuddy owner）不在本报告范围；后端接口已可被其 Controller 调用。

## 6. 遇到的问题与处理（需 Codex 裁决的偏差，均已按“不越 owner、不改契约”方式落地）

① **契约 §2.7 指纹定义自相矛盾**：§2.7 正文写 `orderId|按行序 skuId:qty:unitCost:warehouseId`，但同节“绑定入库事件”与用户本轮授权写 `SHA-256(purOrderId|仓库id|按 purOrderItemId 升序的 purOrderItemId:qtyQualified)`。**处理**：按后者实现。理由：`InboundLineInput` 只有来源行与合格量，`unitCost` 由服务端权威带出、根本不存在客户端值，把它纳入指纹等于把服务端派生值当成客户端输入；来源行+合格量才是入库事件的唯一身份。建议下一轮由 WorkBuddy 统一契约文字（这是契约文字问题，不是实现问题）。

② **幂等表列名**：契约 §1 写 `order_id`，WorkBuddy 实际迁移 `noon_m4_inventory.sql` 为 `pur_order_id`。**处理**：代码用 `pur_order_id` 以匹配真 DDL（真库 IT 已验证），语义不变；建议契约文字对齐（本人未改 SQL/契约）。

③ **“夹带 unitCost/skuId/batchNo 记审计”在 Service 层不可实现**：`InboundInput` 就不是这三个字段，Jackson 绑定阶段即丢弃，服务端读不到“夹带”行为。**处理**：以 JSON 夹带用例证明“被忽略、成本/SKU/批次号仍由服务端权威生成”；审计按既定字段记录确认事件。建议第二轮在 Controller 收原始 body 或给幂等表加 `raw_payload` 后再补这条审计；本轮不改契约 record。

④ **额外加了一层累计入库守恒（有意增强，防数据错误）**：契约只约束单次 `qtyQualified <= 累计收货量`，若允许不同 `requestId` 反复入库，同一收货量可被无限放大（库存虚增，比“重复请求”更隐蔽）。**处理**：在采购单行锁内校验 `qtyQualified <= 累计收货 - 已入库合格量`（已入库只计 CONFIRMED/CLOSED 入库单），是单次规则的严格加强，不改变对外契约与字段；如 Codex 要求严格照字面单次校验，删除 `InboundService.inboundAlready` 一处即可回退。

⑤ **DRAFT 步骤与 §3 无草稿接口**：§0/§2.8 描述 DRAFT→CONFIRMED，但 §3 只有 `confirm`，用户本轮也明确“写入库单 status='CONFIRMED'”。**处理**：单步确认直接落 CONFIRMED，DRAFT 状态本轮不使用；报告如实说明，未臆造草稿 API。

⑥ **`qty_qualified <= qty` 的 API 级验证不可达**：入参无 `qty`（无不合格量字段），故 `qty=qty_qualified`，该 CHECK 只能由真库 DDL 直接构造非法行验证（IT 已做）。

⑦ **`resultOf` 的“指纹比对”无入参可比**：只读接口只有 `requestId`，指纹一致性在 `confirm` 重放时校验；`resultOf` 对齐 M3 §9.6③ 的“存在且 DONE 才返回”。另外契约写“幂等行 order_id→仓库真实站点”，幂等表未存 warehouse_id，故按幂等行 `pur_order_id` 取采购单站点判权——confirm 时已强制采购单站点 == 仓库站点，二者等价；如第二轮要在 `resultOf` 严格取仓库站点，需在幂等表补 `warehouse_id`（属 WorkBuddy 的 DDL）。

⑧ H2 宽松建表不含 CHECK/JSON（沿用 M3），真约束全部由隔离 MySQL IT 覆盖；MySQL `TINYINT(1)` 经 JDBC 返回 `Boolean`（`sign` 断言已按语义换算），与 M3 IT 注意事项一致。

## 7. 复现

```
# H2 + 全项目离线
cd yx-erp && MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o \
  -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test

# 隔离真库 IT（自行起临时 MySQL 8.4.7 后）
NOON_MYSQL_TEST_PORT=<port> NOON_MYSQL_TEST_PASSWORD=<pw> MAVEN_OPTS=-Xmx2g \
  /tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test \
  -Dtest=MySqlInventoryIT -Dsurefire.failIfNoSpecifiedTests=false
```

无 `NOON_MYSQL_TEST_PORT` / `NOON_MYSQL_TEST_URL` 时 IT 自动 skip（`Assumptions`），不会误连生产。

## 8. v1.1 修订（2026-11-22）：放开 PARTIAL_RECEIVED 分批入库

依据：`contract.md` §9.1（已定版）。回应 Codex「M4 第一轮重点复核」——M3 支持分批收货，首批已收但整单未收齐时不能先入库，与「分批到货先入库」的实际业务不符。

### 8.1 改动文件（均在本人 owner 内）

| 文件 | 改动 |
|---|---|
| `yxerp-noon/src/main/java/com/yx/noon/inventory/InventoryRules.java` | `INBOUNDABLE_ORDER_STATUSES` 改为 `List.of("PARTIAL_RECEIVED","RECEIVED","CLOSED")`，注释同步 §9.1 |
| `yxerp-noon/src/main/java/com/yx/noon/inventory/InboundService.java` | 状态闸门抛错文案改为「仅 PARTIAL_RECEIVED/RECEIVED/CLOSED 可入库」（仍 `IllegalArgumentException` → HTTP 400）；类注释同步。**其余零改动**：授权经仓库归属先于判重、指纹优先级、逐行 `FOR UPDATE` 守恒、成本/SKU/批次号后端权威带出、跨表同事务、幂等 replayed 全部保持 v1 现状 |
| `yxerp-noon/src/test/java/com/yx/noon/inventory/InventoryServiceTest.java` | 新增 H2 用例 `partialReceivedOrderSupportsStagedInboundWithinRowConservation`（11 → 12） |
| `yxerp-noon/src/test/java/com/yx/noon/inventory/MySqlInventoryIT.java` | 新增真库用例 `partialReceivedOrderBooksStagedInboundOnRealSchema`（4 → 5） |
| `docs/verification/m4-inventory/doubao-report.md` | 本节 |

未改契约、迁移 SQL、Controller/前端/E2E/协作文档。

### 8.2 新增用例

**H2 `partialReceivedOrderSupportsStagedInboundWithinRowConservation`**（采购单 `PARTIAL_RECEIVED`；行1 计划 60/累计收货 60，行2 计划 20/累计收货 0）：

1. **首批部分行入库**：只对行1 提交 `qtyQualified=40` → 成功，生成 1 批次（`qty_in=40`）、台账 `qty_available=40`、1 条 INBOUND 流水、入库行 `qty_qualified=40`；采购单仍 `PARTIAL_RECEIVED`（入库不改 M3 状态机）。
2. **同单第二批补齐**：再提交 `qtyQualified=20`（累计 40+20=60 ≤ 累计收货 60）→ 成功，批次合计 60、台账 60、流水 2 条、入库行合计 60。
3. **超累计收货拒绝**：再提交 `qtyQualified=1`（61 > 60）→ 400「可入库=0」，批次数/流水数/台账/入库单数均不变（无部分写入）。
4. **未收货行拒绝**：行2 提交 1 → 400「没有收货记录」。
5. **DONE 重放不受状态闸门影响**：`PARTIAL_RECEIVED` 下用首批同 `requestId` 同内容重放 → `replayed=true`，返回首次 `inboundId`，零新增批次、台账不累加。
6. **状态闸门其余状态**：`CONFIRMED` 单 → 400，文案含「仅 PARTIAL_RECEIVED/RECEIVED/CLOSED 可入库」（DRAFT 拒绝由既有 `confirmCreatesInboundBatchLedgerMoveAndAudit` 覆盖）。

**真库 `partialReceivedOrderBooksStagedInboundOnRealSchema`**（真实 DDL：`noon_m4_inventory.sql` + M3 迁移 + schema v0.5 抽取；采购单 `PARTIAL_RECEIVED`，行1 收货 30 = 两条真 `noon_m3_receipt` 20+10）：

- 分两批 20 + 10 入库：`inv_batch` 两行 `qty_in=20/10`，`inv_stock_ledger.qty_available=30`，`inv_stock_move` 两条 `change_type='INBOUND'` 合计 `qty_delta=30`；入库行/批次通过真 CHECK（`qty_qualified<=qty`、`qty_remaining<=qty_in`）。
- 采购单保持 `PARTIAL_RECEIVED`。
- 再提交 1 件超累计 → 400「可入库=0」，批次/入库单/流水/台账全部不变。
- 首批 `requestId` 重放 → `replayed=true`、同一 `inboundId`、不新增批次。

### 8.3 测试结果

| 范围 | 结果 |
|---|---|
| H2 `InventoryServiceTest` | **12/12**（新增 1） |
| `yxerp-noon` 模块 `mvn test` | **139/139**，0 失败/错误/跳过（原 138） |
| Reactor（`-pl yxerp-noon -am`，含 framework 29） | **168/168**，`BUILD SUCCESS`，EXIT=0 |
| 隔离 MySQL 8.4.7 `MySqlInventoryIT` | **5/5**（新增 1），errors=0 failures=0 skipped=0，maven_exit=0 |

真库证据目录（一次性库，结束 drop，未连生产）：`/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m4-txa1q8ik`。

### 8.4 与契约 §9.1 的一致性

- 状态闸门：`{PARTIAL_RECEIVED, RECEIVED, CLOSED}`，DRAFT/CONFIRMED 仍拒绝；文案逐字对齐。
- 安全性来源不变：仍为「`qtyQualified <= 该行累计收货量 − 该行累计已入库量`」，按 `purOrderItemId` `SELECT ... FOR UPDATE` 锁行串行化；未放宽守恒、未绕过授权。
- 优先级不变：授权（仓库归属 `requireSite`）> 内容指纹 > DONE 重放 > 状态闸门；重放不经过状态闸门。
- 幂等/成本/SKU/批次号/跨表同事务/replayed 语义全部未改。

### 8.5 遗留（需 WorkBuddy 裁决，仅契约文字，非实现问题）

1. 契约 §4 仍写「按 purOrderId 校验存在 RECEIVED/CLOSED 的收货记录」，与 §9.1 口径不一致；实现以 §9.1（逐行累计收货量）为准，建议 WorkBuddy 统一 §4 文字。
2. 契约 §2.2/§2.8 正文仍保留「仅 RECEIVED/CLOSED」，以 §9.1 修订为准；本次未改契约（非本人 owner）。
