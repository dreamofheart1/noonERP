# M4 第三轮第三批 · FBN 入仓 ASN 收货闭环（后端内核）· 豆包报告

- 契约：`docs/verification/m4-asn/contract.md`（v1，唯一权威，未修改）
- 范围：只新建 `com.yx.noon.asn` 包 + 测试 + 本报告；**未改** Controller / 前端 / 菜单 / SQL / 契约 / E2E / 既有包
- 状态：H2 单测 14/14 绿，隔离 MySQL 8.4 IT 5/5 绿，`yxerp-noon -am` 全量 198/198 绿
- 未连生产、未部署、未推送、未碰密钥

---

## 1. 交付清单

源码（`yx-erp/yxerp-noon/src/main/java/com/yx/noon/asn/`）：

| 文件 | 说明 |
|---|---|
| `AsnService.java` | 唯一对外服务：create / seal / receive / resultOf / list / get |
| `AsnCreateInput.java` / `AsnCreateLine.java` | 建单入参 / 行 `(skuId,batchNo,qtyShipped,partnerSku)` |
| `AsnSealInput.java` | 封单入参（整单，无明细） |
| `AsnReceiveInput.java` / `AsnReceiveLine.java` | 收货入参 / 行 `(asnItemId,qtyReceived,shortEventId)` |
| `AsnResult.java` | `(asnId,asnNo,replayed,action,childBatchIds)`，与 `TransferResult` 同形 |
| `AsnRow.java` / `AsnItemRow.java` / `AsnDetail.java` | 列表行 / 明细行（带子批次血缘）/ 详情 |
| `AsnRules.java` | 包内校验与工具：指纹、requestId/shortEventId 格式、站点编码、分页、UTC+8 业务时刻、SHA-256 |

测试：
- `yx-erp/yxerp-noon/src/test/java/com/yx/noon/asn/AsnServiceTest.java`（H2 + 真 Spring 事务 + 真站点 Mapper + 真 `SiteAuthGuard`，14 例）
- `yx-erp/yxerp-noon/src/test/java/com/yx/noon/asn/MySqlAsnIT.java`（隔离 MySQL 8.4，5 例，opt-in）
- `scripts/verify-m4-asn-runtime.py`（沿用 `verify-m4-*-runtime.py` 模式：临时 mysqld + 随机端口 + 随机 root 密码 + 一次性库，结束 drop）

复用（未改动）：`SiteAuthGuard`、`InventoryAudit`、`PageResult`、`JdbcTemplate`。`TransferRules` / `InventoryRules` 为包私有，无法跨包引用，故 `AsnRules` 按同口径重建（M4 前几批同样做法）。

---

## 2. 裁决点（D1–D5）

### D1 幂等指纹规范化串

`SHA-256(规范化串)`，取 64 位**小写**十六进制；动作前缀在第一段，天然实现动作级隔离。

```
CREATE |<site>|<warehouseCode>|<line>,<line>…     line = skuId:batchNo:qtyShipped:partnerSku
SEAL   |<asnId>
RECEIVE|<asnId>|<line>,<line>…                    line = asnItemId:qtyReceived:shortEventId
```

- **排序**：CREATE 行按 `(skuId, batchNo)` 升序，RECEIVE 行按 `asnItemId` 升序；因此客户端行序不影响指纹，同内容必得同指纹。
- **null/空串区分**：每个字符串字段经 `field()` 编码——`null` → `~`；非空 → `=` 前缀 + 转义（`\ | : , = ~` 前加 `\`）。故 `null ≠ ""`，且含分隔符的值不会与相邻字段串位（`"a|b"` 与 `("a","b")` 不会撞串）。
- **业务规范化**：`batchNo` trim 后落库并进指纹；`partnerSku` trim 后空白 → `null`（同一语义不产生两个指纹）；`shortEventId` 空白 → `null`。
- **shortEventId 纳入 RECEIVE 指纹**（比调拨更严）：同键换事件号 → 400「内容与原请求不一致」，避免“同一请求换个事件键重记短少”。调拨批曾裁决“事件号不进指纹”，此处**有意收紧**。
- 实现：`AsnRules#createFingerprint / #sealFingerprint / #receiveFingerprint`。

### D2 create 是否强校验源批次

**强校验（采纳契约建议）**，只读、不加锁、不预留：`batchNo` 全局唯一取回后逐项比对 `SKU 一致`、`站点 = siteCode`、`源仓 ACTIVE 且站点一致`、`源仓 ≠ FBN 目标仓`、`status = OPEN`、`qty_remaining ≥ qtyShipped`、SKU ACTIVE。任一不符 400。

理由与影响：
1. 建单即拒绝“批次不存在/已关批/跨站/SKU 错配/计划量明显超库存”，把最常见的错误挡在 DRAFT 之前；
2. **不构成预留**：create 不扣库存也不锁行（与调拨 create 一致），多张 ASN 可先后对同一批次登记计划量；**权威判定仍在 seal 的 `FOR UPDATE` + 显式 CAS**（`UPDATE … WHERE qty_remaining = <读到的旧值>`），并发下最多一张成功，另一张 400 或事务级拒绝；
3. 因此该强校验**不会**误伤“计划量合法的多单登记”，只会在“登记量本身已超过当前库存”时提前报错；
4. `qty_remaining` 在 create 检查、在 seal 复查，两层同口径，不存在“建单时合法、封单时悄悄超发”。

### D3 在途子批次 `split_reason`

取**扩展值 `ASN_TRANSIT`**（11 字符，`split_reason VARCHAR(24)` 内），不复用调拨的 `TRANSFER`。DDL 无 CHECK 约束，已由真库 IT 落库验证。理由：拆批原因必须能区分“调拨在途”与“ASN 入仓在途”，否则后续按 `split_reason` 统计/排查无法区分；`transfer_no` 留 NULL、`asn_no` 填 ASN 号，两个来源列互斥，语义自洽。

`source_type` 仍用既有枚举 `TRANSFER_SPLIT`（契约明确），未新增 `source_type` 值。

### D4 receive 如何定位在途子批次

定位键：**`父批次号 + asn_no + sku_id + warehouse_id(FBN 目标仓)`**，`JOIN inv_batch p ON p.id = c.parent_batch_id`，`FOR UPDATE` 锁子批次行；返回值必须恰好 1 行，0 行 → 400「尚未封单发运/找不到在途子批次」，>1 行 → 数据异常 500。

- 不新增字段（DDL 不改，契约边界内）。
- 唯一性由**建单去重**保证：同一 ASN 内 `(skuId, batchNo)` 已拒绝重复，所以每个明细行最多对应 1 个 `(parent, asn_no, sku)` 子批次；再叠加 `warehouse_id` 排除跨仓同号风险。
- 状态不参与定位（否则“已收货”会被误报成“未发货”）：先定位、再校验 `status = IN_TRANSIT`，非在途 → 400「已收货，禁止重复收货」。

### D5 ASN_SHORT 事件键

`biz_event_key = 'ASNSHORT:' + asnItemId + ':' + shortEventId`，`move_type = 'ASN_SHORT'`，`sign = +1`，`consumption_no = 'AS:UUID'`。

- `shortEventId` 允许 `[A-Za-z0-9_-]{1,64}`，**禁止冒号**等会破坏三段结构的字符（格式在入参校验阶段拒绝）；
- 与调拨同为 `TSHORT:<itemId>:<event>` 的形状，只是前缀换成 `ASNSHORT`；
- 唯一键 `uk_consume_biz_event (biz_event_key, batch_id)` 兜底：撞键 → 400「短少事件已过账」，整事务回滚，不双计（H2 与真库均验证）；
- 短少成本取**子批次** `unit_cost`（继承源批次），`amount = qty × unit_cost`（4 位 HALF_UP），不重估、不摊头程。

---

## 3. 其他影响正确性/守恒的裁决

1. **类非 `final`**：契约 §3 写 `public final class`，但 `@Transactional` 要用 CGLIB 代理，final 类无法被代理（与既有 `TransferService`/`InboundService` 一致）。已按 `public class` 实现并在类注释标注。
2. **整单封单**：`AsnSealInput` 无明细（契约规定发货量 = 登记量），因此 seal 一次处理**全部明细行**；不允许“部分行先发运”，否则会出现“已封单但某行无子批次”，receive 无从定位。按行分批只在 **receive** 支持（契约 §6）。
3. **`qtyReceived = 0` 时保留 `qty_in`**：子批次 `qty_remaining` 置 0、`status = CLOSED`，但 `qty_in` 保持原发运量，守真 DDL `chk_batch_qty (qty_in > 0)`；同时**不写 `ASN_IN` 流水**（守 `chk_move_delta qty_delta <> 0`），短少全额记 `ASN_SHORT`。
4. **子批次号用 `B:ASN:<32 位无横线 UUID>`**：契约字面写 `UUID8`，实际取 32 位；前缀一致、唯一性更强（8 位十六进制在批量场景有撞号概率）。列宽 64 内。
5. **`log_asn` 无 `received_at` 列**：收货完成只更新 `status = RECEIVED`（`updated_at` 自动刷新），收货时刻落在 `noon_m4_inventory_audit(RECEIVE)`。未新增列（不改 DDL）。
6. **`pur_order_item_id` 置 NULL**：与调拨拆批同口径，采购血缘走 `parent_batch_id`。
7. **输出行字段名 `id`**：`AsnItemRow.id` 即 `log_asn_item.id`（既有 `AsnController` 与前端 `index.vue` 按 `detail.items[].id` 绑定）；入参仍按契约叫 `asnItemId`。
8. **`AsnRow` 额外提供 `qtyShippedTotal / qtyReceivedTotal`**：列表页只用到 `itemCount`，多给的合计字段是只读便利字段，不影响既有绑定。
9. **FBN 仓判定**：`is_fbn = 1` **或** `warehouse_type = 'FBN'`（契约允许二选一），且必须 `status = 'ACTIVE'`；按 `(warehouse_code, site_code)` 解析，**不会**跨站找仓。
10. **源仓校验**：seal 时源批次所在仓必须 `ACTIVE`、站点一致、且**不得等于 FBN 目标仓**（禁止自己入自己）；源仓停用会阻塞封单（与调拨“源仓停用阻塞发运”同口径），已作为业务假设列出。
11. **锁序统一**：头行 `FOR UPDATE` → 明细行 `FOR UPDATE` → 源/子批次 `FOR UPDATE` → 台账 `FOR UPDATE`（先源仓后 FBN 仓）。receive 并发按行收货依赖头部行锁串行化，保证“全部子批次离开 IN_TRANSIT → 整单 RECEIVED”判定不出现双提交漏判。
12. **`operatorId` 入参仅结构保留**：一律使用 `SiteAuthGuard.requireCurrentUserId()`，不信任请求体身份（防伪造）。
13. **`resultOf` 多动作取最新**：同一 `requestId` 可能同时存在 CREATE/SEAL/RECEIVE 三行（动作级隔离且互不冲突），`resultOf` 按 `RECEIVE > SEAL > CREATE` 取首条 DONE；未命中/PENDING/ASN 不存在返回 `null`；命中后按**头真实站点** `requireSite`（撤销授权后同键查询 403，不是泄漏缓存）。

---

## 4. 与契约字面的差异（均未改契约）

| 位置 | 契约字面 | 实现 | 原因 |
|---|---|---|---|
| §3 | `public final class AsnService` | `public class` | `@Transactional` 需代理，final 不可代理 |
| §5.2 | `B:ASN:` + `UUID8` | `B:ASN:` + 32 位 UUID | 唯一性更强，前缀不变 |
| §4.2 | 建议强校验源批次 | 已强校验（只读） | 采纳；权威判定仍在 seal |
| §6.6 | 未规定 shortEventId 是否进指纹 | 进 RECEIVE 指纹 | 防换事件键重记短少 |
| §1/§9 | — | 未接 E2E/生成器 | 本批只交付后端内核 |

---

## 5. 测试证据（真跑）

### 5.1 H2 单测 `AsnServiceTest`：14/14

`mvn -o -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test -Dtest=AsnServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
→ `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`

| 用例 | 覆盖 |
|---|---|
| `createRegistersDraftWithoutTouchingStock` | DRAFT/明细/审计 CREATE/指纹落库/asn_id 回填；批次·台账·流水·子批次零变动 |
| `createValidatesRowsAndFbnWarehouse` | 非 FBN 仓、FBN 停用、仓不存在、跨站 FBN 仓、跨站源批次、关批、SKU 停用、SKU 错配、超剩余、重复行、qty=0、空行、null 入参；全部零写入；partnerSku trim |
| `sealSplitsBatchWithLineageLedgerAndAsnOut` | 子批次 `TRANSFER_SPLIT/IN_TRANSIT/split_seq=1/ASN_TRANSIT/asn_no/qty_in=qty_remaining/warehouse`；源批次关批；成本·币种·fx·received_at 继承；台账双边；`ASN_OUT −qty`；`sealed_at`；审计 SEAL |
| `sealRequiresDraftAndSplitsSeqPerParent` | 同父多单 `split_seq` 递增（1、2）；重复封单 400 零写入；剩余量不足 400 且源批次未被扣减、无 PENDING 残留 |
| `receiveConvergesChildLedgerAndRecordsShort` | 8/10：子批次 `OPEN/qty_in=8/qty_remaining=8`；在途清零、可用 +8；`ASN_IN +8`；`ASN_SHORT(2, 24.68)`；行回写；RECEIVED；守恒 0+8+2=10 |
| `receiveZeroShortageClosesChildWithoutInboundMove` | 0/5：`CLOSED`、`qty_in` 保留 5、无 `ASN_IN`、短少 5、RECEIVED |
| `receiveSupportsStagedLinesAndRejectsSecondReceiveOfSameLine` | 按行分批（头保持 SEALED→全收齐 RECEIVED）；同一行二次收货 400 零写入；无 PENDING 残留 |
| `idempotencyReplayConflictPendingAndCrossActionIsolation` | 三态：同键同内容 replay 零写入（头/行/审计/幂等行计数不变）、同键改内容 400、PENDING 400「处理中」；跨动作同 requestId 各自独立成行且指纹不同；SEAL/RECEIVE 重放零写入（不再拆批/不再写流水/不再动台账/不双记短少）；DONE 后换 shortEventId 400；RECEIVED 后同键重放仍返回首次结果 |
| `failedTransactionLeavesNoPendingAndSameKeyCanRetry` | 中途失败（审计表缺失）→ PENDING、半张单、明细行全回滚；同键重试首次成功 |
| `shortEventIdRequiredAndDuplicateEventBlocked` | 短少缺事件号 400 零写入；非法格式（含冒号）400；预置同事件键 → 唯一键拦截、整单回滚（子批次仍在途、台账未动、无 PENDING）；无短少可不给事件号 |
| `authorizationFollowsAsnSiteAndFbnWarehouse` | 无站点 403 零写入；仅 AE 建 SA 单 403；AE 单可建可封；SA 单对 AE 用户 get/list 403、列表零泄漏；AE+SA 可读；撤销授权后同键重放/resultOf/get 403 |
| `concurrentSealOfSameSourceBatchKeepsConservation` | 两线程同源批次各 10 并发封单：恰好 1 次 FRESH、另 1 次 REJECT；只 1 个子批次、源批次只扣一次、台账只动一次、只 1 条 `ASN_OUT`、只 1 条 SEAL 审计、无 PENDING |
| `resultOfRequiresSiteAndPrefersLatestAction` | CREATE/SEAL/RECEIVE 命中与 `replayed=true`；同键多动作取最新；PENDING/非法键/不存在 → null；授权 |
| `listAndGetFilterBySite` | 单站/全站/分页 total 与分页无关；仅 AE 全站只见 AE；空授权零行（不省略 WHERE）；非法站点/负 offset 400；非法 limit 收敛；get(-1) 400 |

### 5.2 隔离 MySQL 8.4 IT `MySqlAsnIT`：5/5

`python3 scripts/verify-m4-asn-runtime.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin --maven /tmp/apache-maven-3.9.11/bin/mvn --maven-settings /tmp/noon-maven-settings.xml --maven-repo /tmp/noon-m2`
→ `MySQL JUnit: tests=5 errors=0 failures=0 skipped=0` / `maven_exit=0`

建表：schema v0.5 真 DDL（`md_sku/md_warehouse/inv_batch_consumption/log_asn/log_asn_item`）+ `noon_m4_inventory.sql` + `noon_m4_asn.sql`；临时实例、随机端口、随机 root 密码、一次性库（结束 drop）。

| 用例 | 结果 |
|---|---|
| `migrationIsIdempotentAndRealAsnChecksHold` | `noon_m4_asn.sql` 二次执行不报错；真 CHECK：`qty_shipped>0`、`qty_received<=qty_shipped`、`qty_in>0`、`qty_remaining<=qty_in`、台账在途非负、`qty_delta<>0`；`(request_id,action)` 唯一且同键异动作可并存；真库落 DRAFT 头 + seal 子批次血缘体 |
| `concurrentSameSealRequestIdSealsOnce` | 同 requestId 并发封单：1 FRESH + 1 REPLAY/REJECT；**只落 1 行 DONE**；只 1 个子批次；源批次扣满关批；台账一次；1 条 `ASN_OUT`；无 PENDING |
| `createSealReceiveKeepsConservationOnRealSchema` | 9 件全链路：create 零副作用；seal 关批/在途 9；receive 7 短少 2 → `OPEN`、在途 0、可用 7、`ASN_SHORT 2×9.5=19.0000`、`ASN_IN 7`、RECEIVED；真库守恒 `0+7+2=9`，全仓可用 7、在途 0、无 PENDING |
| `duplicateShortEventBlockedByRealUniqueKey` | 真库唯一键拦截同事件短少：子批次仍在途、台账未动、ASN 仍 SEALED、PENDING 回滚 |
| `stagedReceivePerLineOnRealSchema` | 按行分批：先 A 后 B，头 SEALED→RECEIVED；同行业已收货 400；两仓台账与在途清零；无 PENDING |

### 5.3 全量

`mvn -o -s /tmp/noon-maven-settings.xml -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test`
→ `Tests run: 198, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（M4 前几批 184 + 本轮 14，IT 因 opt-in 跳过）
`mvn -pl yxerp-admin -am compile` → 成功（确认既有 `AsnController` 与本源包签名/字段吻合，未改其文件）

---

## 6. 待确认业务假设（需 WorkBuddy / 用户确认，不影响本轮闭环正确性）

1. **整单封单**：发货量锁定在 create 登记量；若业务需要“部分行先发、其余行后发”，需要在 seal 增加行级入参（契约未授权，未做）。
2. **源仓停用阻塞封单**：源批次所在仓 `INACTIVE` 时 400。若业务允许“停用仓清库”，需要放宽为仅校验站点。
3. **`log_asn` 无收货时刻列**：前端“收货时间”只能取审计时间或 `updated_at`；若列表必须展示，需 DDL 增列（本轮不改 DDL）。
4. **短少成本口径**：`ASN_SHORT` 取子批次 `unit_cost`（继承源批次），头程费用未摊入（本轮明确不做头程分摊）。若财务要求短少成本含头程，需要在头程分摊批次落地后重算——属于下一批。
5. **ASN 状态枚举**：DDL 注释含 `SLOT_BOOKED / IN_TRANSIT`，本轮只使用 `DRAFT / SEALED / RECEIVED`；“在途”由子批次状态承载，头不进入 `IN_TRANSIT`（契约 §2 即如此）。
6. **`source = 'MANUAL'`**：建单未开放来源入参，全部落库默认值；若后续接平台 ASN 拉取（API 来源），需增加 `source` 入参与指纹字段。
7. **`partnerSku` 仅登记**：不参与库存/成本匹配，也未校验与 `md_partner_sku` 的一致性；若需校验 Noon 货号归属，需另开批次。

---

## 7. 防伪造与守恒要点（供 Codex 复核）

- 入参**不含**成本/币种/汇率/源批次 id/子批次 id；成本、币种、fx、`received_at` 全部由 `batch_no` 命中的源批次服务端带出，`sku_id` 也以源批次为准（客户端 skuId 仅作选择键，不符即 400）。
- 台账四态在写入前算绝对新值并校验非负，且先 `FOR UPDATE` 读、再写；无行时插入并在唯一键冲突后重读累加，避免重复建行。
- 源批次扣减用**显式 CAS**（`WHERE qty_remaining = <读到的旧值>`），不依赖 `SET qty_remaining = qty_remaining - ?` 求值差异（MySQL/H2 行为一致）。
- 收货“同一行只能收一次”以**子批次 CAS `status = IN_TRANSIT`** 为保证，明细行 `qty_received` 只回写不参与并发判定。
- 所有业务拒绝路径都在写前抛出 `IllegalArgumentException`，整事务回滚（含幂等 PENDING 占位），因此“失败不写库存/批次/流水/审计/幂等”由事务保证，而不是靠代码顺序承诺。
