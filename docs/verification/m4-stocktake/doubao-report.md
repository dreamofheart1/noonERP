# M4 库存与 FIFO · 第三轮第一批「盘点闭环」后端内核交付报告

> **已被 v1.2 覆盖**：本报告 §1/§2 的「apply 实时台账回算差异」与「盘盈无批次取 0」口径是错误业务口径，Codex 初审指出后已整改。现行口径与证据见 `doubao-v12-report.md`；本文件仅保留历史交付记录（裁决点 A/B/D–K 仍有效，C 已被覆盖）。

执行者：豆包（Java 后端内核 owner）。日期：2026-11-22。
依据：`docs/verification/m4-stocktake/contract.md` v1（唯一口径）+ 用户本轮任务书。
结论：**契约 §2/§3 的服务端内核已实现，H2 单测 11/11、隔离 MySQL 8.4 真库 IT 5/5、全模块离线测试 200/200 全绿**。契约留白处 11 条裁决已在下文逐条列出（§4），全部按"不破真 DDL、不虚增库存、不臆造成本、授权先于判重"落地。

---

## 1. 交付文件

新增（main，`yx-erp/yxerp-noon/src/main/java/com/yx/noon/stocktake/`，9 个文件）：

| 文件 | 内容 |
|---|---|
| `StocktakeService.java` | `create` / `apply` / `resultOf` / `list` / `get`；实时台账基线、盘亏 FIFO 逐批消耗、盘盈 ADJUSTMENT 承接成本、动作级幂等、同事务审计 |
| `StocktakeRules.java` | 包私有工具：requestId / countEventId / period / batchNo 校验、建单与应用内容指纹、SHA-256、UTC+8 `now()`、分页、金额 4 位 HALF_UP |
| `StocktakeInput.java` / `StocktakeLineInput.java` | 入参 record（public，**无成本/单价字段**） |
| `StocktakeApplyInput.java` | 应用入参 record（public，只认 stocktakeId + requestId） |
| `StocktakeResult.java` | 结果 record（`stocktakeId / stocktakeNo / status / replayed`，签名逐字对齐契约 §2） |
| `StocktakeRow.java` / `StocktakeItemRow.java` / `StocktakeDetail.java` | 列表 / 明细 / 详情 record（public） |

新增（test，`yx-erp/yxerp-noon/src/test/java/com/yx/noon/stocktake/`）：

- `StocktakeServiceTest.java`：H2（MySQL 模式）+ 真 Spring 事务 + 真 MyBatis 站点 Mapper + 真 `SiteAuthGuard`，**11 个用例**。
- `MySqlStocktakeIT.java`：隔离 MySQL 8.4 真 DDL / 真 CHECK / 真唯一键 / 并发 / 端到端，**5 个用例**（无隔离库时整体 skip）。

本报告：`docs/verification/m4-stocktake/doubao-report.md`。

**未改动**：`StocktakeController`、前端、迁移 SQL（`sql/noon_m4_stocktake.sql`）、菜单 SQL、E2E 脚本、`docs/agent-collaboration.md`、契约文件。未推送。

---

## 2. 实现口径（逐条对契约）

**create（→ DRAFT，不改库存）**：入参非空 → `warehouseId` 正整数 → `requestId` 格式（`[A-Za-z0-9_-]{8,64}`）→ `guard.requireCurrentUserId()` → 读 `md_warehouse`（不存在 400、未绑站点 400）→ **`guard.requireSite(仓库真实 site_code)`** → 仓库必须 `ACTIVE` → 逐行校验（`skuId>0`、`qtyActual>=0`、`countEventId` 1–64 位 `[A-Za-z0-9_-]`、`batchNo` 去空白/≤64、**同一 SKU 不得重复**）→ `period` 校验（空取 UTC+8 业务月，非空必须 `YYYY-MM`）→ **CREATE 内容指纹** → DONE 重放 → PENDING 占位（`stocktake_id=0`）→ 写 `inv_stocktake`（`ST:<SITE>:<UUID8 大写>`）+ `inv_stocktake_item`（`qty_book`=台账快照、`qty_diff`=快照差）→ 审计 CREATE → DONE 回填真实 id + 结果快照。建单**不写**台账 / 流水 / 批次消耗。

**apply（DRAFT → APPLIED，过账）**：入参非空 → `stocktakeId` 正整数 → `requestId` → operatorId → `lockStocktake`（`FOR UPDATE`）→ 读头仓库 → `requireSite(真实站点)` → `ACTIVE` → **APPLY 指纹** → DONE 重放 → 状态闸门（仅 DRAFT）→ PENDING 占位（写真实 id）→ 取明细（`ORDER BY id`）→ 防御性查重 SKU → 逐行：`lockItem`（`FOR UPDATE`）+ `lockLedger`（`FOR UPDATE`，无行按 0）+ 台账站点一致性校验 → **`qty_book = 实时 qty_available`**，`qty_diff = qty_actual - qty_book` 回写 → 差异分支 → 整单 `status='APPLIED'`（CAS `WHERE status='DRAFT'`，0 行则抛错回滚）→ 审计 APPLY → DONE。

**盘亏（§6.2）**：先 `FOR UPDATE` 读 OPEN 且 `qty_remaining>0` 的批次（`ORDER BY received_at,id`），**累计覆盖校验**（不足 400，先于任何写入、不透支）→ 逐批 `qty_remaining -= take`（显式 CAS `WHERE id=? AND qty_remaining=?`，扣到 0 置 `CLOSED`）→ 每批一条 `inv_batch_consumption`（`consumption_no='SC:'+UUID`、`biz_event_key='STK:<itemId>:<countEventId>'`、`source_event_id=countEventId`、`qty=take`、`unit_cost` 取批次、`amount=qty*unit_cost` 4 位、`move_type='STOCKTAKE'`、`sign=1`、`period`=盘点期间、`cross_period=0`；撞唯一键 `(biz_event_key,batch_id)` → 400「盘点事件已过账」整单回滚）→ 台账 `qty_available -= loss` → 一条 `inv_stock_move`（`STOCKTAKE_LOSS`、`qty_delta=-loss`、`batch_id=NULL`（跨批）、`ref_doc_type='STOCKTAKE'`、`ref_doc_no=stocktake_no`、`operator_id`、`created_at`）。

**盘盈（§6.3）**：`weightedOpenCost` 按 `currency` 分组 `SUM(qty_remaining)` / `SUM(qty_remaining*unit_cost)` → 加权平均（8 位中间、4 位 HALF_UP）→ 新建批次 `B:STK:<SITE>:<UUID8>`、`qty_in=qty_remaining=gain`、`unit_cost` 承接、`source_type='ADJUSTMENT'`、`received_at=业务时刻`（FIFO 排队尾）、`status='OPEN'`、`split_seq=0`、其余血缘列 NULL → 台账 `qty_available += gain`（无行则建，撞唯一键重锁累加）→ `inv_stock_move`（`STOCKTAKE_GAIN`、`qty_delta=+gain`、`batch_id=新批次`）→ **不写** `inv_batch_consumption`。

**幂等（§4）**：表 `noon_m4_stocktake_request`（`request_id` PK / `stocktake_id` / `warehouse_id` / `operator_id` / `action` / `content_fingerprint` / `status` / `result_json`）。`claimRequest` INSERT `PENDING`，撞主键走**当前读**（`FOR UPDATE`）判重：动作/仓库/指纹任一不同 → 400；PENDING → 400「正在处理中」；DONE → 原样返回 `replayed=true`（零写入）。异常整事务回滚（PENDING 占位一并消失，同键可安全重试）。指纹：CREATE=`SHA-256("CREATE|warehouseId|按(skuId,batchNo)升序的 skuId:batchNo:qtyActual:countEventId")`，APPLY=`SHA-256("APPLY|stocktakeId")`，**动作进指纹**，CREATE/APPLY 同键互不误伤。

**resultOf（§3.3）**：非法键 → null → 取幂等行 → 用行上 `warehouse_id` 查仓库 → `requireSite`（无权 403）→ 非 DONE → null → 取单据 → 返回 `replayed=true` + **单据当前状态**。不写任何表。

**list / get**：`inv_stocktake` 无 `site_code`，站点取 `md_warehouse.site_code`；list 先 `siteGate`（传 siteCode 先 `requireSite`；空授权零行，不省略 WHERE）再 `total` + `LIMIT/OFFSET`（`id DESC`）；get 以单据仓库真实站点 `requireSite` 后回带明细。审计：`noon_m4_inventory_audit`（`biz_type='STOCKTAKE'`、`biz_id=stocktakeId`、`action=CREATE/APPLY`、`operator_id`），与业务同事务。

---

## 3. 测试结果

| 验证 | 结果 | 说明 |
|---|---|---|
| H2 单测 `StocktakeServiceTest` | **11/11 PASS** | 真 Spring 事务 + 真 MyBatis 站点 Mapper + 真 `SiteAuthGuard`，H2 `MODE=MySQL` |
| 隔离 MySQL 8.4 真库 `MySqlStocktakeIT` | **5/5 PASS**（1.7s） | 真 `docs/noon-erp-schema-v0.5.sql` 抽取 + M3/M4 正式迁移；本机起一次性 mysqld（`NOON_MYSQL_TEST_PORT` 模式，结束 drop 库） |
| `yxerp-noon -am test`（全模块） | **200/200 PASS** | framework 29 + noon 171；无回归 |

**H2 用例（11）**

1. `createWritesDraftSnapshotOnly`：建单只写 DRAFT + 明细快照（`qty_book=10 / qty_actual=8 / qty_diff=-2`），台账/批次/流水/消耗零变化；审计 CREATE 字段；DONE 回填真实 id；`period` 空取业务月、空 `batchNo` 落 NULL。
2. `applyLossConsumesOpenBatchesFifoAndKeepsConservation`：盘亏 8 跨两批（5+3），FIFO 先扣早批、扣到 0 置 CLOSED；同事件键跨批两行；`unit_cost/amount` 4 位（50 / 60）；`sign=1`、`period='2026-07'`、`cross_period=0`；流水 `STOCKTAKE_LOSS -8`；总量守恒（剩余 2 + 消耗 8 = 10，台账=批次剩余）。
3. `applyLossRejectedWhenOpenBatchesCannotCoverAndRollsBackEverything`：批次不足 → 400「可用批次不足以覆盖盘亏」；台账/批次/消耗/流水/状态/幂等占位/APPLY 审计全部回滚；**同键补齐后可安全重试成功**。
4. `applyGainCreatesAdjustmentBatchWithWeightedAverageCost`：盘盈 3 建 `B:STK:AE:<8hex>`、`unit_cost=15.0000`（(5×10+5×20)/10）、CNY/fx=1、OPEN、`batch_id` 指新批；台账 13；不写消耗；**无 OPEN 批次取 0**（新建台账行）；**多币种 400 拒绝**且不虚增库存。
5. `applyBaselineUsesLiveLedgerAfterOutboundOnlyBooksTheDifference`：create（快照 10）→ 出库到 6 → apply 实盘 8 → **实时基线 6、盘盈 2**（不是快照的盘亏 2），无 STOCKTAKE_LOSS；反向（补入库到 7、实盘 4）→ 盘亏 3 只按实时差额。
6. `idempotencyReplaysPerActionAndKeepsCreateApplyIsolated`：CREATE 同键同内容 `replayed=true` 零写入、改内容 400；APPLY 重放零过账；**CREATE 键用于 APPLY / APPLY 键用于 CREATE → 400 互不误伤**；PENDING → 400「处理中」；不同键第二张单正常。
7. `concurrentSameApplyRequestIdBooksOnce`：两线程同 apply 键 → 恰好 1 次 FRESH、另一为 REPLAYED/明确拒绝；台账/批次/消耗/流水/审计各 1 份；无残留 PENDING。
8. `authorizationIsSiteScopedAndPrecedesReplay`：AE-only 用户跨站建单/过账/查询/列表全 403（403 发生在占位之前，`request_rows=0`）；**撤销授权后持合法 requestId 仍 403**；无站点用户列表零行；仓库不存在 400 / 停用 400 / 非法站点码 400。
9. `stkEventKeySpansBatchesAndDuplicateBatchRetransmitIsBlocked`：预置同事件同批消耗 → apply 撞唯一键 400，整单回滚；清理后同键重试成功；跨批两行合法（4+3），守恒 1+7=8。
10. `duplicateSkuLinesAreRejectedAtCreateAndApply`：同 SKU 两条（不同/相同批次标签）建单 400；`qtyActual=-1`、非法 `countEventId`、空明细 400；**库里被写入同 SKU 重复行时 apply 拒绝**（不按全量账面重复计数）。
11. `listAndGetRespectSiteAndPaging`：4 单跨 AE/SA，`total` 不被 LIMIT 截断；`offset` 翻页无重复无遗漏；详情字段；AE 用户只看 AE 且 `get(SA)` 403；非法参数/不存在的 requestId 返回 null 而非 400。

**MySQL 真库用例（5）**

1. `migrationIsIdempotentAndRealStocktakeChecksHold`：迁移可重复执行；真 CHECK 拒绝 `qty_book=-1` / `qty_actual=-1`；`stocktake_no` 唯一、`request_id` 主键唯一；`(biz_event_key,batch_id)` 唯一键允许同事件跨批、拒绝同批重传。
2. `createApplyLossGainEndToEndOnRealSchema`：真实 `InboundService` 入库 10@12.34 → 建单（快照 10、`qty_diff=-3`、不动台账、无盘点流水）→ apply 盘亏 3（批次 10→7、`STK:<item>:CNT-IT-E1` 消耗 `qty=3/unit_cost=12.3400/amount=37.0200`、`sign=1`、`period=2026-11`、流水 -3）→ 第二张单盘盈 2（ADJUSTMENT `qty_in=2/unit_cost=12.3400/CNY/fx=1`、台账 9、不写消耗）→ 守恒 9+3=10+2 → 审计 4 条（CREATE×2、APPLY×2）。
3. `liveBaselineAfterAdditionalInboundOnlyBooksTheDifference`：建单快照 +2、随后补入库 5@20（实时账面 15）→ apply 实盘 12 → **盘亏 3**（不是快照的盘盈 2），FIFO 扣最早批次 10→7、成本 10 不重估、无 ADJUSTMENT 批次。
4. `concurrentSameApplyRequestIdAppliesOnce`：真库两线程同 apply 键 → 1 FRESH、1 REPLAYED/拒绝；台账 4、批次 4、消耗 6、`STOCKTAKE_LOSS` 1、APPLY 审计 1、**无残留 PENDING**。
5. `duplicateStkEventBlockedByRealUniqueKeyAndRollsBack`：预置同事件同批消耗 → 真库唯一键拦下 → 400 + 整单回滚（批次/台账/流水/单据/幂等/审计均无痕）；清理后同键重试成功。

**复现命令**

```bash
cd /Users/dream/Work/noonERP/yx-erp
MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
# 真库 IT（WorkBuddy 统一跑，或本地起一次性 mysqld 后）
MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test \
  -Dtest=MySqlStocktakeIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dnoon.mysql.test.port=<隔离 mysqld 端口>
```

---

## 4. 裁决点（契约留白处逐条：选择 + 理由）

| # | 留白 | 本轮选择 | 理由 |
|---|---|---|---|
| A | 重复行（§6.1 允许"apply 合并或拒绝"） | **create 直接 400 拒绝任何重复 `skuId`**（含同 `(skuId,batchNo)`）；apply 再防御性拒绝 | 基线是 **SKU 级**台账 `qty_available`，同 SKU 两行会各自以全量账面为基线重复计数同一批实物；"合并"必须二选一实盘量，等于臆造。拒绝是唯一不解歧义的选择（比契约最低要求更早） |
| B | `countEventId` 如何到 apply（apply 签名只收 stocktakeId，`inv_stocktake_item` 无事件号列） | 落在 **CREATE 幂等行 `result_json` 的附加键 `itemEventIds`（itemId→countEventId）**；apply 读取后复算 `STK:<itemId>:<event>` | result_json 是 JSON 列、与 create 同事务写入、随回滚消失，是唯一既不破真 DDL 又能稳定复算的通道；顶层仍保持 `StocktakeResult` 四字段，附加键为 additive（见 §6 风险） |
| C | 盘盈无 OPEN 批次时的承接成本 | `unit_cost=0.0000`、`currency='CNY'`、`fx_rate=1`；**不写消耗** | 不凭空造成本、不虚增库存净值；M3 账面本币为 CNY，取 0 是"未定价"的诚实表达。代价是这批盘盈的 COGS 为 0，需后续重估/调价单修正 |
| D | apply 的"状态 DRAFT"闸门与重放先后（契约 §3.2 字面把它排在重放前） | 闸门放在 **DONE 重放之后**（授权 > 指纹 > 重放 > 状态） | 若按字面，已 APPLIED 单无法幂等重放，与 §4 和验收「同 requestId 重放 replayed=true」直接矛盾；第一轮 `InboundService` 同口径 |
| E | `requireSite` 与仓库 `ACTIVE` 的先后 | **先 `requireSite(真实站点)`，再判 ACTIVE** | 撤销授权/跨站者恒得 403，不因仓库停用泄漏 400（越权优先于业务状态） |
| F | 盘盈时 OPEN 批次币种不一致 | **400 拒绝**，不折算 | 无汇率来源即折算等于臆造；宁可让盘点失败由人工处理 |
| G | `period` 与跨期 | 空取 UTC+8 业务月；消耗 `period`=盘点单 period、`cross_period=0`；跨期不阻断 | 本轮没有"跨期重算原期快照"能力，置 `cross_period=1` 会声称一个未实现的语义，故显式 0 并在报告声明 |
| H | `§6.1` 的 `(sku_id, batch_no)` 台账读取 | 台账无批次维度，实际按 **`(sku_id, warehouse_id)` 读 `qty_available`**；`batchNo` 仅作追溯标签，不参与差异 | 结构性事实：`inv_stock_ledger` 唯一键是 `(sku_id,warehouse_id)`。若将来要按批次盘点，需在 item 上存 per-batch 基线并改本口径（见 §6） |
| I | 盘亏流水的 `batch_id` | 跨批时 **NULL**，批次级明细看 `inv_batch_consumption` | 一条流水不指向单一批次，避免误导；契约只要求 `qty_delta/ref/operator` |
| J | `resultOf` 返回的 `status` | 返回**单据当前状态**（非 create 时缓存的 DRAFT） | 只读查询应对齐真实状态；`replayed` 恒 true 已表达"这是幂等结果" |
| K | 审计 `detail` 内容 | 固定摘要键值串（`stocktakeNo/warehouseId/site/period/lines/qtyBook/qtyActual/gain/loss/gainBatches`） | 列宽 1024，不写完整行数组；批次级证据在消耗表/流水表，审计只做可检索痕迹 |

---

## 5. 与 E2E 脚本（`scripts/verify-m4-stocktake-e2e.py`）的对齐

- 建单返回 `stocktakeNo` 前缀 `ST:AE:` ✔；`replayed` 布尔 ✔；空 `batchNo` 落 NULL 且不影响指纹 ✔。
- create 后 `qtyBook` 快照 100、`qtyActual` 90 ✔；建单不写 `inv_stock_move`（脚本按 `ref_doc_no` 过滤）✔。
- 出库后 apply：`qtyBook=80 / qtyDiff=+10`（盘盈而非盘亏）✔；台账 80→90、ADJUSTMENT `qty_in=10/OPEN`、`STOCKTAKE_GAIN +10`、**无 `STK:%` 消耗** ✔（该场景加权平均 = (40×10+40×20)/80 = 15，脚本未断言金额）。
- 盘亏单：`STK:<item 表 id>:CNT-D2`、`batch_id=1`、`qty=10`、`unit_cost=10.0000` ✔；批 1 剩余 40→30 ✔。
- apply 重放 `replayed=true` 且不再过账 ✔；同 `requestId` 当作 create → 400 ✔。
- 撤销 `sys_user_site` 后 `get` → 403 ✔；`list` 仅 AE、`total≥2` ✔；`result?requestId=` → `replayed=true` ✔。

---

## 6. 未做 / 已知边界（提请 WorkBuddy / Codex 知悉）

1. **`result_json` 附加键**：CREATE 行的 `result_json` 为 `{stocktakeId, stocktakeNo, status, replayed, itemEventIds:{...}}`。若后续有消费方严格反序列化为 `StocktakeResult`，需忽略未知键；若要把事件号落成独立列（更干净），请加列并回填，我可再改读取通道。
2. **盘盈零成本**：无 OPEN 批次时 `unit_cost=0`，库存金额虚低（数量正确）。属裁决点 C，需业务侧后续重估。
3. **按批次盘点不支持**：本轮口径为 SKU 级（裁决点 H）；同单同 SKU 多批次标签会被 400 拒绝。
4. **跨期**：只记录 `period`，没有跨期快照重算（裁决点 G）。
5. **无审批/多次点数**：`DRAFT → APPLIED` 一步过账；计数事件号仅作幂等与留痕，不做多轮点数合并。
6. 真库 IT 需隔离 MySQL（`NOON_MYSQL_TEST_PORT` / `noon.mysql.test.url`）；本机已用 MySQL 8.4.7 一次性实例跑通，生产/开发库一律未连。
