# M4 第三轮第一批「盘点」v1.2 整改报告（Codex 初审两缺陷）

执行者：豆包（`com.yx.noon.stocktake.*` 后端内核 owner）。日期：2026-11-22。
依据：`docs/verification/m4-stocktake/contract.md` **v1.2** §5 / §6.1 / §6.3 / §12（权威口径）+ 本轮任务书。
结论：Codex「M4 盘点关键流程初审」两条意见（时序错位真缺陷、零成本盘盈进 FIFO）**已整改**。H2 `StocktakeServiceTest` **15/15**、隔离 MySQL 8.4 `MySqlStocktakeIT` **7/7**、全项目离线 `mvn test` **204/204**（framework 29 + yxerp-noon 175）全绿。

本报告取代 `doubao-report.md` 中「apply 实时台账回算差异」「盘盈无批次取 0」两段口径；服务签名、接口路径、幂等三态、授权顺序、FIFO 消耗算法、STK 事件键、迁移 SQL、Controller、前端、E2E 均未改（后者归 WorkBuddy）。

---

## 1. 改动清单

**新增（main，`yx-erp/yxerp-noon/src/main/java/com/yx/noon/stocktake/`）**

| 文件 | 作用 |
|---|---|
| `StaleStocktakeException.java` | 账面快照失配业务异常；message 含 `stocktakeId/stocktakeNo/skuId/snapshotBook/currentBook/请重新盘点` |
| `ZeroCostGainException.java` | 零成本盘盈业务异常；message 含 `stocktakeId/stocktakeNo/skuId/warehouseId/site/gain/原因` 与「无可追溯成本，请先补录采购价或由业务确认估值」 |

两者继承 `IllegalArgumentException`（其父类仍是 `RuntimeException`），使现有 `StocktakeController.invalidInput` 直接映射 400，E2E 可凭异常类名或文案识别；若 WorkBuddy 要 409 语义，只需在 Controller 增加按类处理，本类无需再改。

**修改 `StocktakeService.java`**

1. `apply` 新增**整单闸门**（状态闸门之后、PENDING 占位之后、任何过账写入之前）：逐行 `FOR UPDATE` 锁 `inv_stocktake_item` + 锁 `(sku_id, warehouse_id)` 台账（无行按 0），比对建单快照 `qty_book` 与实时 `nowBook`：
   - 不等 → `StaleStocktakeException` 整单拒绝；
   - 相等且 `qty_actual - qty_book > 0` → 预检 `requireWeightedOpenCost`，无 OPEN 批次或加权成本 ≤ 0 → `ZeroCostGainException` 整单拒绝。
2. 过账差异**改用建单快照**：`qty_diff = qty_actual - qty_book`（不再取实时台账），并**删除原第 247 行 `UPDATE inv_stocktake_item SET qty_book=?, qty_diff=?`**——快照一经写入不再被 apply 覆盖。
3. `weightedOpenCost` → `requireWeightedOpenCost`：无 OPEN 批次 / 加权数量为 0 / 加权 `unit_cost` 4 位后 ≤ 0 → `ZeroCostGainException`；多币种仍 400（v1.1 裁决点 F 保留）。
4. 内部 `StocktakeItem` 增加 `qtyBook` 分量，`stocktakeItems` / `lockItem` 同步 `SELECT qty_book`（行加锁后以库内快照为准）。
5. 类与 `create` 的 javadoc 同步改为快照口径（不再描述"实时回算"）。

**未改**：`StocktakeRules`（格式/指纹/金额规则不变）、全部 public record、`apply` 之外的 `create/resultOf/list/get` 逻辑、迁移 SQL、Controller、前端、E2E、契约、`docs/agent-collaboration.md`。

---

## 2. 实现口径（对应 §6.1 / §6.3）

- **基线唯一**：`qty_actual` 是某时刻的实盘事实，只有同一时刻的账面快照 `qty_book` 能与之配对。建单后真实出库/入库/调拨让实物与账面同步变动，此时旧实盘与新账面已失配，任何"用旧实盘减新账面"的算法都会凭空盘盈/盘亏。
- **拒绝时机**：在 `lockStocktake` 之后、`claimRequest` 写 PENDING 之后、逐行过账之前。`StaleStocktakeException` / `ZeroCostGainException` 为运行时异常，`@Transactional(rollbackFor = Exception.class)` 整单回滚：PENDING 幂等行、审计、任何台账/批次/消耗/流水/状态写入全部消失；同 requestId 重试会再次判定（不缓存错误结果），重盘/补录成本后可正常成功。
- **多行**：逐行检查后立即抛错，因此一行 stale 即整单拒绝，合规行也不得过账（事务回滚验证见用例 4）。
- **零成本**：盘盈不再落 `unit_cost=0` 的 ADJUSTMENT 批次进 FIFO；成本只取 OPEN 批次加权平均，结构上无客户端成本字段。
- **审计**：未新增 `APPLY_REJECT_STALE` 审计。业务校验失败在同一事务内抛异常，即使写审计也会一起回滚；"拒绝发生在任何写入之前"更彻底，故不做（此为可选项的取舍，见 §5 D3）。

---

## 3. 对契约 §12 两点逐条对齐

| Codex 意见 | v1.2 要求 | 本实现 | 测试证据 |
|---|---|---|---|
| 1 时序错位：apply 用旧实盘减实时账面会凭空盘盈/盘亏；行锁只保瞬间并发 | 弃用实时回算；快照为唯一基线；实时 ≠ 快照 → 整单拒绝 `StaleStocktakeException`（含 skuId/快照/当前/请重新盘点），不写任何业务数据；按新账面重盘；原「快照盘亏→实时盘盈」测试反转 | `apply` 闸门 + 差异用 `qty_book`；删除 apply 覆盖 sql；异常类与文案齐备 | H2 1–5、MySQL 2–3（出库拒绝、入库拒绝、重盘成功、多行部分 stale、同键重试仍拒绝） |
| 2 无历史成本时零成本盘盈批次进 FIFO 致 COGS/利润错误 | 无 OPEN 批次或加权成本 ≤ 0 → 整单拒绝 `ZeroCostGainException`，提示补录采购价/业务估值；不接受客户端成本；列为待业务确认限制 | `requireWeightedOpenCost` 三处拒绝；盘盈分支不再有 0 成本路径 | H2 6、MySQL 4；另 `applyGain...` 保留加权成本 15.0000 与多币种拒绝 |

**不变量核对**：§2 服务签名未变（`create/apply/resultOf/list/get` 与全部 record 未动）；接口路径/权限注解未动；幂等三态与指纹未变；授权顺序（授权 > 指纹 > DONE 重放 > 状态闸门 > PENDING 占位 > 快照/成本闸门 > 逐行过账，闸门只新增只读校验）未变；FIFO 消耗算法与 `STK:<itemId>:<countEventId>` 键格式未变。豆包 v1.1 裁决点 C（盘盈零成本取 0）被本整改覆盖，其余 A/B/D–K 不变。

---

## 4. 测试用例清单与结果

### H2 `StocktakeServiceTest`（15/15 PASS）

新增/改写的核心 6 条：

1. `createWritesDraftSnapshotOnly`（保留，注释改为快照即唯一基线）：`qty_book=10 / qty_actual=8 / qty_diff=-2`，建单不动台账/批次/流水/消耗。
2. `applyLossConsumesOpenBatchesFifoAndKeepsConservation`（保留，基线断言改为快照）：`qty_book` 保持 10、`qty_diff=-8`、FIFO 5+3、消耗金额 50/60、守恒 2+8=10。
3. `applyLossRejectedWhenOpenBatchesCannotCoverAndRollsBackEverything`（保留）：批次不足 400、全回滚、同键补齐后成功。
4. **`outboundAfterCreateIsRejectedAsStaleAndWritesNothing`（新，Codex 场景原形）**：建单实盘=账 100 → 台账/批次改 90 → apply 抛 `StaleStocktakeException`，文案含 `skuId/snapshotBook=100/currentBook=90/请重新盘点`；台账仍 90、批次 90、无 ADJUSTMENT、无 GAIN/LOSS 流水、无消耗、单据 DRAFT、`item.qty_book` 仍 100、`qty_diff` 未改、PENDING 占位 0 行；同键重试仍拒绝且零写入。
5. **`inboundAfterCreateIsRejectedAsStale`（新）**：建单实盘 90/快照 100 → 台账 110 → 拒绝（防凭空盘亏 10）；台账仍 110、无消耗/流水、DRAFT。
6. **`recountAgainstNewBookAppliesWithNoGainOrLoss`（新）**：出库到 90 后按新账面重建单（实盘 90/快照 90，差 0）→ apply 成功、台账 90 不变、无盘盈盘亏流水/ADJUSTMENT，旧单保持 DRAFT。
7. **`multiRowPartialStaleRejectsWholeDocument`（新）**：A 可盘亏 10、B 已失配 → 整单拒绝；A 台账/批次仍 100（未部分过账）、B 40、零流水、DRAFT、两行快照均未改。
8. **`gainRejectedWhenNoOpenBatchOrWeightedCostIsZero`（新）**：① 无 OPEN 批次/无台账 → `ZeroCostGainException`（含 skuId/warehouseId/gain=5/提示语），不建台账行、不建批次、无流水、DRAFT；② OPEN 批次成本 0 → 拒绝（gain=2/"加权平均成本为 0"），台账 3 不虚增；补录成本 12 后同键重试成功 → ADJUSTMENT `qty_in=2/unit_cost=12.0000/CNY/fx=1/OPEN`、GAIN +2、不写消耗。
9. `idempotencyReplaysPerActionAndKeepsCreateApplyIsolated` / `concurrentSameApplyRequestIdBooksOnce` / `authorizationIsSiteScopedAndPrecedesReplay` / `stkEventKeySpansBatchesAndDuplicateBatchRetransmitIsBlocked` / `duplicateSkuLinesAreRejectedAtCreateAndApply` / `listAndGetRespectSiteAndPaging`（保留，全部通过，证明闸门未破坏幂等、授权、STK 键与分页）。

**删除**：`applyBaselineUsesLiveLedgerAfterOutboundOnlyBooksTheDifference`（"快照盘亏→实时回算盘盈"错误口径，已按任务要求删除并以 4–7 反转为拒绝/重盘）。

### 隔离 MySQL 8.4 `MySqlStocktakeIT`（7/7 PASS，maven_exit=0）

1. `migrationIsIdempotentAndRealStocktakeChecksHold`（保留）：迁移幂等、真 CHECK、三个唯一键。
2. `createApplyLossGainEndToEndOnRealSchema`（保留）：真 `InboundService` 入库 10@12.34 → 盘亏 3（消耗 3/12.3400/37.0200、流水 -3）→ 盘盈 2（ADJUSTMENT 12.3400）→ 守恒 9+3=10+2。
3. **`outboundAfterCreateRejectedAsStaleThenRecountApplies`（新）**：真实入库后建单（快照 10）→ 真实出库 4（台账/批次 6）→ 拒绝（snapshotBook=10/currentBook=6/请重新盘点）；台账 6、批次 6、无 ADJUSTMENT、无 STOCKTAKE 流水/消耗、快照 10、DRAFT、PENDING 回滚、无 APPLY 审计 → 按账面 6 重盘成功（台账 6 不变、零流水）。
4. **`inboundAfterCreateRejectedAsStale`（新）**：建单差 -3 后补入库 3（账面 13）→ 拒绝；批次 10 未被扣、台账 13、DRAFT。
5. **`zeroCostGainRejectedOnRealSchema`（新）**：无批次/无台账盘盈 5 → `ZeroCostGainException`；不建台账行/批次、无流水、DRAFT、快照差保持 5。
6. `concurrentSameApplyRequestIdAppliesOnce`（保留）：1 FRESH + 1 重放/拒绝，台账/批次/消耗/流水/审计各 1 份、无残留 PENDING。
7. `duplicateStkEventBlockedByRealUniqueKeyAndRollsBack`（保留）：真唯一键拦下 + 整单回滚 + 清理后重试成功。

### 全项目离线测试

`mvn -o test`（settings `/tmp/noon-maven-settings.xml`、repo `/tmp/noon-m2`）：framework **29/29**、yxerp-noon **175/175**（StocktakeServiceTest 15、其余 160 无回归），合计 **204/204**，`BUILD SUCCESS` EXIT=0。

---

## 5. 新裁决点（WorkBuddy 定版 / Codex 复核）

| # | 点 | 本轮选择 | 理由 |
|---|---|---|---|
| D1 | 异常类的父类型 | `StaleStocktakeException` / `ZeroCostGainException` 继承 `IllegalArgumentException` | 父链仍是 `RuntimeException`；不依赖 Controller 改动即被现有 `@ExceptionHandler` 映射 400，E2E 立即可识别；要 409 时 Controller 加按类处理即可 |
| D2 | 闸门位置 | 放在 `claimRequest` 之后、逐行过账之前 | 契约允许（"幂等 claim 之后、逐行过账之前都可"）。保留原幂等语义：并发同键第二个请求仍先命中 PENDING/DONE，不被闸门抢先改变返回；异常整单回滚含 PENDING 占位，不缓存错误结果 |
| D3 | 是否写 `APPLY_REJECT_STALE` 审计 | 不写 | 审计同事务，抛异常必然一起回滚；写它需要独立事务（引入不一致风险）。"任何写入之前拒绝"比留下一条会回滚的审计更干净 |
| D4 | 零成本判定口径 | 无 OPEN 批次 / 加权数量 0 / 加权成本 4 位后 ≤0 三路拒绝 | 与"可追溯成本"定义一致；4 位是落库精度，避免 0.00004 此类不可用成本混入 FIFO |
| D5 | 盘盈加权成本并发 | 仍为普通 `SELECT`（未 `FOR UPDATE`），与 v1.1 一致 | 契约未要求锁批次；误加锁锁范围大且 MySQL 聚合加锁语义不明确。并发入库/调拨可能让加权均值轻微偏移，属既有限制，不扩大本轮改动 |
| D6 | 拒绝后旧单处理 | 保持 `DRAFT`，由业务重建单（作废接口不在本批） | 与 §6.1.4 一致；不引入未定义的作废状态 |

---

## 6. 边界与未覆盖（如实）

- E2E（`scripts/verify-m4-stocktake-e2e.py`）中原「快照盘亏 → 实时盘盈」场景断言仍是错误口径，**须由 WorkBuddy 反转为 400/拒绝 + 重盘 + 零成本拒绝**；Controller 如需 409 亦由 WorkBuddy 加映射。本报告未改这些文件。
- H2 只验逻辑与事务（宽松 DDL），真 CHECK/唯一键/并发仍由 MySQL IT 覆盖；真库 IT 的"真实出库/入库"仅用台账+批次同步更新模拟，未串 `FifoConsumptionService`（其需 `sal_*` 夹具，跨包且非本轮重点）。
- 既有待确认限制不变：盘盈成本取加权平均而非最近采购价、按批次盘点不支持（SKU 级）、无审批一步过账、幂等表无 TTL、跨期不重算；真实 Noon 平台、旧库升级、跨设备异常重试未验证。
- 未连生产、未部署、未推送、未碰密钥。

---

## 7. 复现

```bash
cd /Users/dream/Work/noonERP/yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -o -B -ntp -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am \
  -Dtest=StocktakeServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

cd /Users/dream/Work/noonERP
python3 scripts/verify-m4-stocktake-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
# 本次证据目录：/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m4-stocktake-v8v03x6_
```
