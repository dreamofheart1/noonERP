# M5 订单与退货 · 编排闭环 + A/B/C 分级处置（后端内核）· 豆包报告

- 契约：`docs/verification/m5-sales-return/contract.md`（v1，唯一权威，未修改）
- 范围：只新建 `com.yx.noon.sales` 包 + 测试 + 本报告 + runtime 脚本；**未改** Controller / 前端 / SQL / 菜单 / 契约 / E2E / 既有 `intake`・`fifo`・`reversal`・`inventory`・`transfer`・`asn`・`stocktake`・`shipment` 任何类
- 状态：H2 单测 **24/24 绿**（SalesFlowServiceTest 10 + ReturnDispositionServiceTest 14）；`yxerp-noon -am` 全量 **222/222 绿**（基线 198，新增 24，exit 0）；隔离 MySQL 8.4 `MySqlSalesIT` **5/5 绿**（maven_exit=0）；`yxerp-admin -am compile` exit 0
- 未连生产、未部署、未推送、未碰密钥；未提交

---

## 1. 交付清单

生产源码（`yx-erp/yxerp-noon/src/main/java/com/yx/noon/sales/`）：

| 文件 | 说明 |
|---|---|
| `SalesFlowService.java` | 编排主服务：`importOrder` / `deliverOrder` / `importReturn` / `reverseReturn` / `disposeReturn`（委托处置）/ `resultOf` / 列表与详情；事件登记、动作幂等、订单行补建 |
| `ReturnDispositionService.java` | A/B/C 分级处置核心：红冲前置校验、A 建 `RETURN_RESTOCK` 批次、B/C 全额 `RETURN_LOSS` + `qty_unsellable`、赔偿/退款只登记 |
| `SalesIdempotency.java` | 包内动作幂等（`noon_m5_sales_request`）：claim / markDone / replayDone / release / load |
| `SalesRules.java` | 包内校验与工具：requestId 格式、UTC+8 业务时刻、`period()`、money 归一、指纹编码、SHA-256、Boolean/Number 兼容取整 |
| `SalesResult.java` | 统一返回体（见 §2.2） |
| `SalesFlowException.java` | 隔离结果异常（`action/requestId/reasonCode/reviewNo`）→ 期望 409 |
| `ReturnDispositionException.java` | 处置业务拒绝（`salReturnId/grade/reasonCode`）→ 400 |
| `OrderImportInput.java` / `DeliverInput.java` / `ReturnImportInput.java` / `ReturnReverseInput.java` / `DisposeInput.java` | HTTP 入参 |
| `SalesOrderRow.java` / `SalesOrderItemRow.java` / `SalesOrderDetail.java` / `SalesReturnRow.java` / `SalesReturnFlowRow.java` / `SalesReturnDetail.java` | 只读视图 |

测试与脚本：

- `yx-erp/yxerp-noon/src/test/java/com/yx/noon/sales/`：`SalesTestSchema`（H2 最小 DDL）、`SalesTestConfig`（真 Spring 事务 + 真 `SiteAuthGuard` + 真三个内部服务）、`SalesFlowServiceTest`（10）、`ReturnDispositionServiceTest`（14）、`MySqlSalesIT`（5，opt-in）
- `scripts/verify-m5-sales-runtime.py`（隔离 mysqld + 随机端口 + 一次性库，结束 drop）

复用未改：`SiteAuthGuard`、`InventoryAudit`（`noon_m4_inventory_audit`，`biz_type='SALES_FLOW'/'RETURN_DISPOSE'`）、`PageResult`、`JdbcTemplate`、`IntakeService`、`FifoConsumptionService`、`ReversalService`。

---

## 2. 精确接入签名

### 2.1 服务方法（Controller 以此为准）

```java
// com.yx.noon.sales.SalesFlowService
public SalesResult importOrder(OrderImportInput in);            // 授权→幂等→ORDER 事件→IntakeService.postOrder→DONE
public SalesResult deliverOrder(DeliverInput in);                // 授权→幂等→SALE_DELIVERY 事件→postDelivery→DONE
public SalesResult importReturn(ReturnImportInput in);           // 授权→幂等→RETURN 事件→postReturn→DONE
public SalesResult reverseReturn(ReturnReverseInput in);         // 授权→幂等→RETURN_REVERSAL 事件→postReversal→DONE
public SalesResult disposeReturn(DisposeInput in);               // 直接委托 ReturnDispositionService.dispose
public SalesResult resultOf(String requestId, String action);     // 先授权，再回读；PENDING → 400「处理中」；未知 → 400
public PageResult<SalesOrderRow> listOrders(String siteCode, int offset, int limit, String orderNo, String status);
public SalesOrderDetail getOrder(Long id);                       // 不存在 → null；跨站 → 403
public PageResult<SalesReturnRow> listReturns(String siteCode, int offset, int limit, String orderNo, String grade);
public SalesReturnDetail getReturn(Long id);

// com.yx.noon.sales.ReturnDispositionService
public SalesResult dispose(DisposeInput in);
```

构造器：`SalesFlowService(JdbcTemplate, SiteAuthGuard, InventoryAudit, IntakeService, FifoConsumptionService, ReversalService, ReturnDispositionService)`；`ReturnDispositionService(JdbcTemplate, SiteAuthGuard, InventoryAudit)`。

### 2.2 入参与返回体字段

```java
record OrderImportInput(String requestId, String siteCode, String orderNo, Long storeId, Long skuId,
                        String partnerSku, BigDecimal grossAmount, Integer qty, String currency, String sourceChannel)
record DeliverInput(String requestId, String siteCode, Long salOrderItemId, Integer qty)
record ReturnImportInput(String requestId, String siteCode, String orderNo, String sourceReturnNo,
                         Long salOrderItemId, Integer qty)
record ReturnReverseInput(String requestId, String siteCode, Long salReturnId, Long originalConsumptionId, Integer qty)
record DisposeInput(String requestId, String siteCode, Long salReturnId, String grade,
                    BigDecimal restockCost, BigDecimal compensationAmount, BigDecimal refundAmount, Integer qty)
```

`DisposeInput` 的 `restockCost/compensationAmount/refundAmount/qty` 均可为 null；`refundAmount` 为本次新增可选字段（只登记 `sal_return.refund_amount`，不写收入流水）。

```java
record SalesResult(String requestId, String action, String siteCode, boolean replayed,
                   Long entityId, Long orderId, Long orderItemId, Long returnId, Long reversalId,
                   List<Long> consumptionIds, String grade, String restockBatchNo,
                   BigDecimal returnLossAmount, BigDecimal compensationAmount, String reasonCode)
```

- `importOrder` → `orderId` + `orderItemId` + 事件 `entityId=orderId`
- `deliverOrder` → `consumptionIds`（全部消耗行，按 id 升序；`entityId` 为首个）
- `importReturn` → `returnId`
- `reverseReturn` → `reversalId`
- `disposeReturn` → `returnLossAmount`（A 级为 0）、`restockBatchNo`（A 级批次号，B/C 为 null）、`compensationAmount`

只读行字段：`SalesOrderRow(id,orderNo,siteCode,storeId,partnerSku,skuId,qty,grossAmount,currency,sourceChannel,status,createdAt)`；`SalesOrderItemRow(id,salOrderId,orderNo,skuId,qty,qtyDelivered,qtyReturned,qtyCancelled,unitPrice)`；`SalesReturnRow(id,returnNo,siteCode,orderNo,skuId,qty,grade,status,compensationAmount,refundAmount,returnLossAmount,restockBatchNo,period,originalPeriod,salOrderItemId,createdAt)`；`SalesReturnFlowRow(id,moveType,sign,qty,unitCost,amount,bizEventKey,reversalOfId,batchId,period,crossPeriod,consumedAt)`（红冲 `sign=-1/RETURN_REVERSAL` 与 B/C 级 `sign=+1/RETURN_LOSS` 同列展示）。

### 2.3 Controller 现状与唯一缺口

`yxerp-admin/.../noon/SalesFlowController.java`（WorkBuddy 预置）已 import 上述全部类型并编译通过，HTTP 路径与契约 §3 一致。**唯一缺口：没有 `SalesFlowException → 409` 的 handler**，现有 `IllegalArgumentException → 400` 会先命中（`SalesFlowException` 继承 `IllegalArgumentException`），隔离结果会返回 400 而非 409。补法（WorkBuddy 侧一行）：

```java
@ExceptionHandler(SalesFlowException.class)
public AjaxResult quarantine(SalesFlowException e) {
    return AjaxResult.error(409, e.getMessage()); // 建议 body 另带 reasonCode/reviewNo/requestId
}
```

`ReturnDispositionException` 属业务拒绝，保持 400 即可。

---

## 3. 裁决点 D1–D8

### D1 事件登记是否需要独立幂等
**不需要独立幂等表**：`eventId = <EVENT_TYPE>:<requestId>` 由 requestId 派生（`ORDER_IMPORT→ORDER`、`ORDER_DELIVER→SALE_DELIVERY`、`RETURN_IMPORT→RETURN`、`RETURN_REVERSE→RETURN_REVERSAL`），同动作重放天然同一 eventId；写 `int_webhook_event` 由 `uk_event_id` 兜底，撞键后回读并按（类型、站点、JSON 规范化内容）比对，不一致即 400（同 requestId 换事件内容）。动作级幂等仍由 `noon_m5_sales_request` 承担，两层职责分开：事件表管"报文级不重复"，request 表管"业务结果重放"。

### D2 A 级 `restockCost` 缺省口径
缺省取**原 SALE 消耗层**的 `unit_cost`（不是当前采购层重估价）；多层红冲时取 `红冲金额合计 / 处置 qty` 的 4 位 HALF_UP 加权成本，保证"回补库存金额 = 冲回 COGS 金额"，不凭空产生成本差。入参可覆盖（质检重估场景），覆盖值直接落 `inv_batch.unit_cost`。已测：`disposeGradeADefaultsCostToOriginalConsumedLayer`（100）。

### D3 B/C 级 qty 与红冲 qty 不一致
**严格整单处置**：`qty` 缺省=退货单 `qty`；若入参 `qty ≠ 退货单 qty` → 400 `QTY_PARTIAL_NOT_SUPPORTED`。理由：契约 §9.5 明确未实现"作废/再处置"，分批处置会把退货单打成终态 `DISPOSED` 却仍有未处置数量，形成半边状态。已测：`partialReversalOrPartialQtyCannotBeDisposed`。

### D4 跨期 period 归属与 `cross_period`
`RETURN_LOSS.period` 一律取**退货期**（优先 `sal_return.period`，缺失才取处置时刻所在期），绝不改回原消耗期——红冲已归原期（`ReversalService` 取原消耗 period），这里计损归退货期，与 PRD 跨期案例一致。`cross_period = 任一被红冲原消耗层 period ≠ 退货期 ? 1 : 0`；`sal_return.original_period` 记首层原消耗 period（入参已带则沿用）。已测：`crossPeriodLossBelongsToReturnPeriod`、IT `crossPeriodLossLandsOnReturnPeriodWithRealSchema`。

### D5 `RETURN_LOSS.unit_cost` 取哪一层
取**原 SALE 消耗行**的 `unit_cost`（行级真实成本），不是原采购批次当前成本。多层场景按原消耗层逐行拆分：每行 `amount = 本层 qty × 本层 unit_cost`，各行金额合计严格等于红冲金额合计（不摊平、不取平均）。合并键用 `batchId`（唯一键是 `(biz_event_key, batch_id)`；同批次多次出库消耗时按 originalId 合并会撞键，同批次成本必然相同，按批次合并无损口径）。已测：`multiLayerDisposeSplitsLossByOriginalLayerCost`（5@100 + 1@120 → 500+120=620）。

### D6 `qty_unsellable` 与 `RETURN_LOSS` 是否同事务
**必须同事务**，已实现：`@Transactional(rollbackFor = Exception.class)` 覆盖台账锁行更新、消耗行插入、`sal_return` 回写、幂等 `markDone`、审计写入；任一步失败整体回滚，不会出现"库存隔离了但没计损"或反之。台账 `FOR UPDATE` 锁行（缺失行插入），CHECK ≥ 0。

### D7 多次退货部分红冲后 A/B/C 按什么口径
**必须先完成全部红冲再处置**：`已红冲 qty 合计 ≠ 退货单 qty` → 400 `REVERSAL_INCOMPLETE`（提示"已红冲 X / 退货 qty Y"），不按"剩余未红冲比例"分摊处置。理由：未红冲部分若同时计损，COGS 会双计；且契约未给部分处置的作废/续处置流程。已测同上 `partialReversalOrPartialQtyCannotBeDisposed`，并断言台账 `qty_unsellable=0`、`sal_return` 仍 `OPEN`。

### D8 内部服务 NEEDS_REVIEW 时是否登记事件留痕
**留痕**：事件行保留（`int_webhook_event` 不删），并写 `processed_at` 标记"已处理但被隔离"；动作幂等 `PENDING` 占位在隔离路径显式释放（同 requestId 可修正后重试，不缓存错误结果）。实现方式：`@Transactional(noRollbackFor = SalesFlowException.class)` + 抛异常前 `release`，使隔离记录与事件留痕提交、业务写入全不落库。已测：`importOrderQuarantineIsNotSuccessButKeepsEvidence`（隔离记录 + `reviewNo` `RV:` 前缀 + 事件 `processed_at` + PENDING 行已释放）。

---

## 4. 测试矩阵与数字

### 4.1 H2（真 Spring 事务、真 `SiteAuthGuard`、真内部三服务）

`SalesFlowServiceTest`（10）：

| 用例 | 断言要点 |
|---|---|
| `importOrderPostsOrderAndCreatesItemAndEvent` | 订单头 + 订单行 + 事件 `RECEIVED` + `processed_at` + 审计 |
| `importOrderSameRequestIdReplaysWithoutSideEffects` | 同 requestId 重放零副作用，`replayed=true` |
| `importOrderCrossSiteIsDeniedNotQuarantined` | 跨站 403（`AccessDeniedException`），不产生隔离记录 |
| `importOrderQuarantineIsNotSuccessButKeepsEvidence` | 隔离 → `SalesFlowException`（409 语义）+ `reasonCode` + `reviewNo` + 证据保留 |
| `deliverSplitsFifoLayersAndKeepsCumulativeConservation` | FIFO 5+1 拆批、批次剩余 5→1、台账 11→5→1、`qty_delivered` 6→10、超投 `DELIVERY_EXCEEDS_ORDER` |
| `deliverCrossSiteItemIsDenied` | 跨站订单行 403 |
| `returnAndReversalAccumulateWithoutExceedingOriginalOrReturn` | 两次退货各 ≥1 红冲、超原层 `REVERSAL_EXCEEDS_ORIGINAL`、退货总量超已妥投整体回滚 |
| `returnReplayWithNewRequestIdDoesNotDoubleCountQtyReturned` | 同退货号换 requestId 不二次累计 `qty_returned` |
| `listsAndDetailsAreSiteIsolated` | 列表站点隔离、详情按行真实站点校验 |
| `resultOfReadsBackStoredResultAndRejectsUnknown` | 回读结果 + 未知 action 400 |

`ReturnDispositionServiceTest`（14）：A 级建批次（`B:RTN:AE:` + `RETURN_RESTOCK` + `OPEN` + available 4→5 + unsellable 0 + 无 `RETURN_LOSS` + 回写 `restock_batch_no`）；A 级缺省成本取原层 100；B 级全额 100（**非 70**）+ 键 `RTNDISP:<id>:B` + `reversal_of_id` + available 4 / unsellable 1（4+1=5 守恒）+ 赔偿 30 只登记 + 零 `COMPENSATION` 流水 + 退款 150 只登记 + 只读视图 `getReturn` 红冲行 `sign=-1` 且 SA 站点看不到；C 级独立键；**C-2b L1=−70 流水级验算**（sale 100 / reversal −100 / loss 100 / refund 150 / comp 30 → netRevenue 0 − cogs 100 + comp 30 = −70）；重复同 grade ACK 不二次计损；同 requestId 重放；换 grade 400；未红冲 400；partial 双 reasonCode；赔偿换额 400；跨站 403；跨期归退货期 + `cross_period=1` + `original_period`；多层成本 620 拆分。

### 4.2 隔离 MySQL 8.4（`MySqlSalesIT`，opt-in）

1. `realDdlChecksAndUniqueKeysHold`：真 CHECK/主键（`qty_remaining>qty_in` 拒、`qty=0` 拒、`sign=0` 拒、同 `(request_id,action)` 重复拒）+ **迁移幂等复跑**（`noon_m4_inventory` / `noon_intake_review` / `noon_fifo_review` / `noon_reversal_review` / `noon_m5_sales` 五个 DDL 全量重跑不报错）
2. `endToEndOrderDeliverReturnReverseDisposeOnRealSchema`：5@100 → 导入 5 → 妥投 2 → 退货 2 → 红冲 2 → B 级（赔偿 60 / 退款 300）；SALE 200 / 红冲 −200 / loss 200 / compensation 60（零 `COMPENSATION` 流水）/ available 3 / unsellable 2 / `qty_returned` 2；重复处置 ACK 不二次计损；只读视图在真 `TINYINT(1)` 上数值可读、SA 站点为空
3. `aLevelRestockCreatesRealBatchAndPassesChecks`：A 级真批次过 CHECK（restock 130 / available 5 / unsellable 0）
4. `crossPeriodLossLandsOnReturnPeriodWithRealSchema`：原 period 改 2026-10 → loss 落当期 + `cross_period=1` + `original_period=2026-10`
5. `concurrentSameRequestIdLandsOneOrder`：两线程同 requestId 只落 1 订单 / 1 订单行 / 1 幂等行且 `DONE`

### 4.3 数字与证据

| 项 | 结果 |
|---|---|
| H2 `SalesFlowServiceTest` | 10/10 |
| H2 `ReturnDispositionServiceTest` | 14/14 |
| `-pl yxerp-noon -am test` | **222/222**，BUILD SUCCESS，exit 0（`/tmp/m5-full.log`） |
| `MySqlSalesIT` | tests=5 errors=0 failures=0 skipped=0，maven_exit=0 |
| `yxerp-admin -am compile` | exit 0 |

证据目录（每个含 `mysql-init.log` / `mysql.log` / `mysql-maven.log` / `mysql-data`）：

- 最终成功：`/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m5-sales-jsvtehac`
- 前一轮成功（严格整单口径 + 视图断言前）：`.../T/noon-m5-sales-e5qfo55a`
- 首跑失败留证：`.../T/noon-m5-sales-81n251fi`（`crossPeriodLossLandsOnReturnPeriodWithRealSchema` 因 `TINYINT(1)→Boolean` 抛 ClassCastException；已改 `SalesRules.toInt` 读取并定向查询 `(sign+0)` / `(cross_period+0)`）

---

## 5. 业务假设与自决项

1. **订单头一单一 SKU**：`IntakeService.postOrder` 只写 `sal_order` 不建 `sal_order_item`，编排层同事务补建一行（`platform_item_no=NULL`、`unit_price=gross/qty` 4 位 HALF_UP）。多 SKU 订单需 M9 平台适配层拆单，本批接口口径即"一个 requestId 一条 SKU 行"。
2. **A 级回补可用量**：新批次建好后同时 `inv_stock_ledger.qty_available += qty`（缺失则插入）。不做则批次剩余与台账失真，后续 FIFO 会 `LEDGER_SHORTAGE`。
3. **`qty_returned` 守恒**：`importReturn` 仅在内部服务返回非 `ALREADY_POSTED` 时累计，并以 `qty_returned + qty <= qty_delivered` 条件更新，超限 400（防同退货号换 requestId 二次累计）。
4. **A 级仓**：多层红冲跨仓时，回补批次落首个被红冲原消耗层的仓（同订单行一般同仓发货）。
5. **处置不可逆**：`DISPOSED` 后同 grade 再调 → ACK；换 grade / 换赔偿金额 → 400。符合 §9.5（未实现作废）。
6. **审计**：`biz_type='SALES_FLOW'` 记导入/妥投/退货/红冲；`'RETURN_DISPOSE'` 记 `DISPOSE_A/B/C`，详情含 loss/restockBatch/compensation。

---

## 6. 未实现项（对应契约 §9，均按要求"标注不假定"）

| §9 | 现状 |
|---|---|
| 9.1 分级触发方 | grade 由人工在处置请求显式传；平台自动触发、改 grade 权限未实现 |
| 9.2 A 级成本口径 | 缺省原层成本、允许入参覆盖；质检重估流程未实现 |
| 9.3 A 级是否经待检 | 按 PRD 直接转可用，不经 `qty_unsellable` 待检 |
| 9.4 赔偿实际过账 | 只登记 `compensation_amount`，不写 `COMPENSATION` 平台费流水（M6） |
| 9.5 改级/作废 | 不支持同 returnId 改 grade、无作废/冲正、不支持分批处置（partial → 400） |
| 9.6 退货时效 | `window_deadline` / `appeal_deadline` 只登记不校验 |
| 9.7 不合格后续出库 | B/C 级 `qty_unsellable` 的报废/降级销售未实现 |
| 9.8 平台事件适配 | 事件为隔离环境占位，不臆造真实平台类型/字段（M9） |
| 9.9 通用未验证 | 真实 Noon 平台、旧库升级、跨设备异常重试 |

另：`PageResult` 列表为 offset/limit 分页（Controller 已 clamp 1–200）；`resultOf` 对 PENDING 返回 400「处理中」而非特殊码。

---

## 7. 给 WorkBuddy 的接入清单

1. **补 409 handler**（唯一必须改的 Controller 点）：`SalesFlowException → HTTP 409`，body 建议 `{code:409, msg, reasonCode, reviewNo, requestId, action}`；不改则隔离结果被现有 `IllegalArgumentException→400` 吞掉。
2. **权限点核对**：`noon:sales:order:import|deliver|list`、`noon:sales:return:import|reverse|list`、`noon:sales:dispose:edit`（`noon_m5_sales_menu.sql` 已存在，核一遍字符串与 `@PreAuthorize` 完全一致）。
3. **前端字段对接**：按 §2.2 的 `SalesResult` / 行 record 取字段；处置页展示 `grade / returnLossAmount / restockBatchNo / compensationAmount / refundAmount / period / originalPeriod`，详情页流水用 `SalesReturnFlowRow`（红冲负号与 `RETURN_LOSS` 正号并列显示）。
4. **操作顺序**：退货处置前必须完成全部红冲（未完成 → 400 `REVERSAL_INCOMPLETE`；部分处置 → 400 `QTY_PARTIAL_NOT_SUPPORTED`）；前端应在红冲完成后才放开处置按钮。
5. **HTTP 语义**：跨站 = 403（不产生隔离记录）；内部闸门隔离 = 409（事件留痕 + 可修正后同 requestId 重试）；其余业务拒绝 = 400。
6. **幂等重放**：任何写接口重试都必须复用同一 `requestId`；`resultOf(requestId, action)` 用于超时后回读，`replayed=true` 表示命中已存结果。
7. **联调脚本**：契约 §10 的 `verify-m5-m4-keyflow-e2e.py` 归 WorkBuddy（本包未写 E2E）。
8. **M6**：`compensationAmount` 的单次确认与过账（确保不双计）延到 M6 账单。

---

## 8. 复现命令

```bash
# H2 + 全量
cd yx-erp && /tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test

# admin 编译
cd yx-erp && /tmp/apache-maven-3.9.11/bin/mvn -o -q -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-admin -am compile

# 隔离 MySQL IT
python3 scripts/verify-m5-sales-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
```
