# M4 库存与 FIFO · 第二轮「调拨 + 在途拆批 + 短少」后端内核交付报告

执行者：豆包（Java 后端内核 owner）。日期：2026-11-22。
依据：`docs/verification/m4-transfer/contract.md` v1（唯一口径）+ 用户本轮任务书。
结论：**契约 §2/§3 的服务端内核已实现并通过 H2 14/14、隔离 MySQL 4/4、全模块 153/153 离线测试**；有 4 处契约/DDL 冲突与 3 处契约未明确处需要 WorkBuddy/Codex 裁决（见 §4），均已按"不破真 DDL、不虚增库存"的可辩护口径落地并标注。

---

## 1. 交付文件

新增（main，`yx-erp/yxerp-noon/src/main/java/com/yx/noon/transfer/`，10 个文件）：

| 文件 | 内容 |
|---|---|
| `TransferService.java` | ship / receive / resultOf / list / get；拆批血缘、四态台账双边、短少、幂等、审计 |
| `TransferRules.java` | 包私有工具：requestId/短少事件号校验、内容指纹（含动作）、SHA-256、UTC+8 now、分页、金额 4 位 |
| `TransferShipInput.java` / `TransferShipLine.java` | 发货入参 record（public，无 sku/成本/批次号字段） |
| `TransferReceiveInput.java` / `TransferReceiveLine.java` | 收货入参 record（public） |
| `TransferResult.java` | 结果 record（transferId/transferNo/replayed/action/childBatchIds） |
| `TransferRow.java` / `TransferItemRow.java` / `TransferDetail.java` | 列表/详情 record（public） |

新增（test，`yx-erp/yxerp-noon/src/test/java/com/yx/noon/transfer/`）：

- `TransferServiceTest.java`：H2 + 真 Spring 事务 + 真 SiteAuthGuard + 真 MyBatis 站点 Mapper，**14 个用例**。
- `MySqlTransferIT.java`：隔离 MySQL 8.4 真 DDL/并发/端到端，**4 个用例**（无隔离库时整体 skip）。

本报告：`docs/verification/m4-transfer/doubao-report.md`。

未改动契约、迁移 SQL、Controller、前端、E2E 脚本、协作文档；未推送。

---

## 2. 实现口径（逐条对契约）

**ship（DRAFT→IN_TRANSIT）**：入参校验 → 锁 `inv_transfer`（FOR UPDATE）→ 读源/目标 `md_warehouse` 必须存在且 `ACTIVE` → `guard.requireSite(两仓 site_code)`（无授权 403）→ **两仓必须同站点**（跨站 400）、源≠目标 → 请求体仓库 id 必须等于单上真实源/目标（防伪造 400）→ 请求体 `siteCode` 仅展示校验 → 内容指纹 → DONE 重放 → 状态闸门（仅 DRAFT）→ 占位 PENDING → 逐行（按 itemId 升序）锁调拨行 + 锁来源批次 → 守恒校验 → 源批次 `qty_remaining -= shipped`（显式 CAS，扣到 0 置 CLOSED）→ 建子批次 → 台账双边（源仓 available −、目标仓 in_transit +，行锁 + 唯一键兜并发，四态非负前置校验）→ `TRANSFER_OUT(-shipped, 源仓)` → 回写行 → 整单 `IN_TRANSIT/shipped_at` → 审计 → markDone。

**子批次字段**：`B:TR:<UUID>`、sku 继承、`warehouse_id=目标仓`、`site_code=目标仓站点`、`received_at=父批次.received_at`（FIFO 延续）、`qty_in=qty_remaining=实发量`、`unit_cost/currency/fx_rate` 继承、`source_type='TRANSFER_SPLIT'`、`parent_batch_id=父批次`、`split_seq=MAX(同父)+1`（父批次已行锁，MAX 与插入串行）、`split_reason='TRANSFER'`、`transfer_no`、`status='IN_TRANSIT'`。

**receive（IN_TRANSIT→RECEIVED）**：同授权（目标仓；契约 §2.1 字面还要求源仓 ACTIVE）→ `toWarehouseId` 必须等于单上目标仓 → 指纹 → DONE 重放 → 状态闸门（仅 IN_TRANSIT）→ 逐行锁调拨行 + 锁子批次（`parent_batch_id=父编号.id AND transfer_no=本单 AND warehouse_id=目标仓`）→ 子批次必须仍 `IN_TRANSIT`（已收过 400）→ `qtyReceived<=qtyShipped` → 子批次收敛为实收（`status=received>0?OPEN:CLOSED`）→ 台账 `in_transit-=shipped / available+=received` → `TRANSFER_IN(+received)` → 短少 `diff>0` 写 `inv_batch_consumption`（`move_type='TRANSFER_SHORT'`、`sign=1`、`biz_event_key='TSHORT:'+itemId+':'+shortEventId`、`qty=diff`、`unit_cost` 继承、`amount=qty*unit_cost`、`consumption_no=TS:UUID`、`period=YYYY-MM` UTC+8）→ 回写 `qty_received/diff_qty` → 全部子批次离开 IN_TRANSIT 时整单 `RECEIVED/received_at`。

**幂等**：`noon_m4_transfer_request`（request_id PK / transfer_id / content_fingerprint / status / result_json / operator_id），语义对齐第一轮 `noon_m4_inbound_request`：授权 > 指纹 > DONE 重放 > 状态闸门；`claimRequest` INSERT PENDING，撞唯一键走当前读 `FOR UPDATE` 判重；DONE 且指纹一致 → `replayed=true` 原样返回且零写入；指纹不同（含动作不同）→ 400；PENDING → 400「处理中」；异常整事务回滚（PENDING 一并消失，同键可安全重试）。内容指纹 = `SHA-256("<transferId>|SHIP|RECEIVE|<itemId:qty,...按 itemId 升序>")`。

**resultOf**：按 requestId 取幂等行 → 查 transfer 真实源/目标仓 → `requireSite` → 才返回 DONE 缓存（`replayed=true`）；非法键/无行/PENDING/单不存在 → null；无权 403；只读。

**list/get**：`inv_transfer` 无 site_code，站点取源仓 `md_warehouse.site_code`；list 先 `siteGate`（传 siteCode 先 requireSite，空授权零行）再分页（`total` 真实总数）；get 以源仓站点 requireSite（若目标仓站点不同再校验）并回带行 + 子批次血缘。审计写 `noon_m4_inventory_audit`（`biz_type='TRANSFER'`、`action=SHIP/RECEIVE`，同事务）。

**工具同口径**：`TransferRules` 内实现 `requireRequestId`（`[A-Za-z0-9_-]{8,64}`）、`contentFingerprint`、`sha256Hex`、`now()`（Asia/Shanghai，毫秒截断）、`limit/offset`、`money`（4 位 HALF_UP）——第一轮 `InventoryRules` 包私有不能跨包复用。

---

## 3. 测试与证据

**H2 单测**（`TransferServiceTest`，14/14，0 失败）：

1. `shipAuthorizationFollowsBothWarehousesAndSameSiteRule`：无站点 403、仅 AE 用户发 AE 单、跨站 403、两仓不同站点 400、仓库停用 400、仓库不存在 400、源=目标 400、请求 siteCode 不符 400、请求仓库 id 不符 400、单不存在 400；被拒请求零写入。
2. `shipSplitsBatchWithLineageLedgerMovesAndSplitSeq`：血缘 13 字段逐项断言（含 received_at 继承、split_seq 1/2/3、成本/币种/汇率继承、IN_TRANSIT）；源批次 10→7→4→0→CLOSED；台账源可用/目标在途；TRANSFER_OUT 流水；IN_TRANSIT/shipped_at；超剩余 400、超计划量 400、CLOSED 批次不可拆 400。
3. `receiveTurnsTransitIntoOpenRecordsShortageAndStagedReceive`：按行分批收（第 1 行 8/10 后整单仍 IN_TRANSIT、第 2 行补齐转 RECEIVED）；在途→OPEN、在途减可用增、短少流水全字段（事件键/成本/金额/sign/period/不绑销售行）、diff_qty 回写、TRANSFER_IN、重放零增长、异键重复收 400。
4. `receiveEdgesZeroReceivedMissingShortEventAndUnshippedItem`：未发货收货 400、缺 shortEventId 400、超发货量 400、整行零收货（qty_in 保留发货量、remaining=0、CLOSED、无 qty_delta=0 流水）、已收行再收 400。
5. `shipIdempotencyReplayConflictPendingAndRetryAfterRollback`：DONE 重放、同键改量 400、PENDING 400、失败回滚后同键重试成功。
6. `concurrentSameShipRequestIdSplitsOnce`：两线程同键 → 一个 FRESH 一个 REPLAYED/REJECT，仅 1 子批次/1 流水/1 台账累加。
7. `shipRequestIdCannotBeReusedForReceive`：动作冲突 400（数量相同也拒），receive 自身重放。
8. `resultOfIsReadOnlySiteAuthorizedAndTwoState`：DONE 返回、PENDING/null/非法键 null、只读、跨站 403、无站点 403。
9. `ledgerInsufficiencyRejectsShipAndReceiveWithRollback`：源仓可用不足 400（不扣批次/台账、不留子批次/PENDING）、目标仓在途不足 400 回滚。
10. `duplicateShortEventIsBlockedByUniqueKeyWithoutDoubleCounting`：预置同事件键 → 400「短少事件已过账」，回滚不双计。
11. `listGetRespectSiteAuthorizationPagingAndDetailLineage`：超管 3 单、详情头/行/子批次、分页无重无漏、AE 用户仅 AE、跨站 403、空授权零行、非法 id 400。
12. `totalQuantityConservationAcrossSourceChildAndShortage`：`源批次剩余(0)+子批次(7)+短少(3)=10`；可用+短少=初始；在途 0；流水净额 −3；短少金额 22.50；RECEIVED。
13. `clientSuppliedSkuCostAndBatchAreIgnored`：JSON 夹带 skuId/unitCost/batchNo/qty 被丢弃，SKU/成本/数量全部服务端权威。
14. `lineValidationRequiresFullCoverageAndUniqueItems`：未覆盖全部行 400、空行/重复行/0 量/非法 itemId 400、行不属于本单 400、正常全行发货成功。

**全模块离线测试**：

```
cd yx-erp && MAVEN_OPTS=-Xmx2g /tmp/apache-maven-3.9.11/bin/mvn -o \
  -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
→ framework 29/29；yxerp-noon 153/153（含 TransferServiceTest 14/14）；BUILD SUCCESS
```

**隔离 MySQL 8.4 真库 IT**（`MySqlTransferIT`，4/4，0 失败/错误/跳过）：

```
python3 scripts/verify-m4-transfer-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2
→ MySQL JUnit: tests=4 errors=0 failures=0 skipped=0；maven_exit=0
→ 证据目录 /var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m4-transfer-b1a11uf7
```

（同一用例另用我自建的等效驱动脚本在 `/var/folders/.../noon-m4-55afbphm` 复跑一次，亦 4/4、exit 0。）

1. `migrationIsIdempotentAndRealTransferChecksHold`：迁移二次执行幂等；真 CHECK —— `qty_shipped=0` 拒、`qty_received>qty_shipped` 拒、调拨幂等表 request_id 主键撞键、在途负数拒、`qty_remaining>qty_in` 拒、`qty_delta=0` 拒；子批次落库体（TRANSFER_SPLIT/IN_TRANSIT/seq=1/reason/transfer_no）逐字段。
2. `concurrentSameShipRequestIdSplitsOnce`：真库两线程同键 → 仅 1 子批次/1 流水/1 审计/1 次台账累加，源批次只扣一次。
3. `transferChildBatchIsReceivedOpenThenConsumedByExistingFifo`：第一轮 `InboundService` 真库入 10 → 调拨拆 10 → 收 8（短少 2）→ 子批次 OPEN 且 `received_at` 与父批次相等 → 现有 `FifoConsumptionService.postDelivery` 妥投 8 **直接消费该子批次**（batch_id 指向子批次、`qty_remaining→0`、CLOSED、台账可用清零、不进 FIFO 隔离层）；守恒：`源剩余 0 + 子批次 0 + 短少 2 + 已售 8 = 10`。
4. `duplicateShortEventBlockedByRealUniqueKey`：真库 `uk_consume_biz_event(biz_event_key,batch_id)` 拦截同事件 → 400，整事务回滚（子批次仍在途、台账未动、PENDING 不留）。

---

## 4. 需 WorkBuddy / Codex 裁决的点（含契约与 DDL 冲突）

> 以下均已按"不破真 DDL、不虚增库存、不丢数量"落地，并如实标注；若裁决不同，改动集中在 `TransferService` 的对应一处。

1. **【契约与真 DDL 冲突】整行零收货时子批次 `qty_in`**：契约 §2.5 要求 `qty_in = qty_remaining = qtyReceived`，但 `inv_batch` 真 DDL 有 `chk_batch_qty(qty_in > 0 AND qty_remaining <= qty_in)`。`qtyReceived=0` 时 `qty_in=0` 必被拒。当前口径：`qty_remaining=0`、`status='CLOSED'`，**`qty_in` 保留发货量**（历史发货量不丢、CHECK 不破）。建议契约措辞改为"`qty_remaining=实收`；`qty_in` 在零收货时保留发货量"。
2. **【契约与真 DDL 冲突】`TRANSFER_IN` 流水 `qty_delta=0`**：契约 §2.4 写 `qty_delta=+qtyReceived`，而 `inv_stock_move` 有 `chk_move_delta(qty_delta <> 0)`。当前口径：实收 0 的行**不写 TRANSFER_IN**（短少由 `TRANSFER_SHORT` 记损失）。建议契约补一句"实收 0 不写流水"。
3. **【契约未明确】ship 行覆盖与计划量**：`inv_transfer_item.qty_shipped` 在 DRAFT 期即计划量（NOT NULL > 0），而契约未说明 ship 是否可部分行发货。当前口径：**必须覆盖全部调拨单行（每行恰好一次、itemId 必须属于该单）**；单行发货量可 ≤ 计划量（发货成功后该行 `qty_shipped` 收敛为实发量）。理由：未发的行不会有在途子批次，receive 无从定位。**若 WorkBuddy 的 add/发货接口允许只提交部分行，需要改成"允许子集 + 未发行为何处置"的明确规则，请裁决。**
4. **【契约未明确】receive 分批**：当前允许**多个 requestId 按行分批收货**（同一行只能收一次：子批次离开 IN_TRANSIT 即视为已收，二次收 400），"全部收齐"定义为该单所有子批次离开 IN_TRANSIT。契约只写"全部收齐后 RECEIVED"，未禁止按行分批；若不支持，请裁定为"单次收货必须覆盖全部行"。
5. **【契约笔误/歧义】`itemId` 指代**：契约写"锁每行 `inv_transfer_item`（FOR UPDATE）"、"`TSHORT:`+itemId"，但 `inv_transfer_item` 没有 `item_id` 列。当前口径：`itemId = inv_transfer_item.id`（也是短少事件键中的 transfer_item_id）。请确认 Controller/DTO 也按行主键传。
6. **【安全边界】`shortEventId` 格式**：契约未定格式，但事件键列宽 `VARCHAR(160)` 且以冒号分隔。当前限制 `[A-Za-z0-9_-]{1,64}`、短少时必填；含冒号/中文会 400。若平台事件号形态不同（如含 `.`、`:`），需要同步放宽（建议同时确认事件键拼接不被破坏）。
7. **【幂等指纹范围】** 按契约 §2.6 字面，指纹只含 `transferId|动作|itemId:qty`，**不含 `shortEventId`**。因此同一 requestId 重试时即使 `shortEventId` 变化也会被当作 DONE 重放（返回首次结果）。这是契约定义的行为；如需更强一致性，需改契约把事件号并入指纹。
8. **【授权范围】receive 是否要求源仓 ACTIVE**：契约 §2.1 字面要求"源仓与目标仓都 ACTIVE"。当前 receive 也校验源仓 ACTIVE。若业务认为"发货后源仓停用不应阻塞收货"，需改为一处（只校验目标仓 ACTIVE，站点仍按两仓同站）。
9. **【`batch_no` 语义】** 沿用 WorkBuddy 迁移中的注释：`inv_transfer_item.batch_no` 保持**来源父批次号**；子批次通过 `parent_batch_id + transfer_no` 定位（`TransferItemRow` 已回带 `childBatchId/childBatchNo/childStatus` 供 UI 展示）。若前端希望行上直接存子批次号，需要在收货后再回写，与"父批次为血缘根"的现有注释冲突，请裁决。
10. **服务不创建调拨单**：契约 §3 只定义 `ship/receive/resultOf/list/get`，本服务假定 `inv_transfer`/`inv_transfer_item`（DRAFT、计划量、`batch_no`）已由 WorkBuddy 的 add 接口或种子写入。若 add 也归后端内核，需要新增 `create` 契约（当前未实现，避免越权扩面）。

---

## 5. 对接要点（给 WorkBuddy 的 Controller/E2E）

- 400：`IllegalArgumentException`（参数/授权经仓库归属的仓库不存在或停用/跨站/状态闸门/守恒/内容冲突/PENDING 处理中/短少事件重复）；403：`AccessDeniedException`（`SiteAuthGuard`）。
- ship 请求：`transferId, fromWarehouseId, toWarehouseId, siteCode(可选，仅展示校验), lines[{itemId, qtyShipped}], requestId`；`lines` 必须覆盖全部调拨行，`requestId` 为 8–64 位 `[A-Za-z0-9_-]`（建议 UUID）。
- receive 请求：`transferId, toWarehouseId, lines[{itemId, qtyReceived, shortEventId}]`；`qtyReceived<发货量` 时 `shortEventId` 必填（同事件号不可复用于同一行）。
- 结果：`TransferResult{transferId, transferNo, replayed, action(SHIP|RECEIVE), childBatchIds}`；重放时 `replayed=true` 且为零写入。
- 列表 `TransferRow`、详情 `TransferDetail{head, items[itemId, skuId, batchNo(父批次), qtyShipped, qtyReceived, diffQty, childBatchId, childBatchNo, childStatus]}`。
- 审计：`noon_m4_inventory_audit(biz_type='TRANSFER', action='SHIP'|'RECEIVE')`，与业务同事务。

## 6. 未做 / 边界

- 未做第三轮：ASN、头程费用、盘点、`qty_unsellable` 流程、跨站/多段路由（跨站 400 拒绝）。
- 未实现调拨单创建/编辑/删除；未自动关单（收齐只置 RECEIVED，符合契约"本轮不强制关单"）。
- 未改契约与迁移；未连生产、未部署、未推送、未碰密钥。
- HTTP E2E / 真实浏览器 / 生成器采用由 WorkBuddy 负责，本报告不含这两部分结果。
