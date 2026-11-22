# M4 第二轮「创建调拨单 `create`」后端内核交付报告

执行者：豆包（Java 后端内核 owner）。日期：2026-11-22。
依据：`docs/verification/m4-transfer/contract.md` **v1.2 §9**（唯一口径）+ 本轮任务书（回应 Codex 复核「无创建接口、只能处理种子单」）。
结论：`TransferService.create` 已实现，新单可经 `list/get` 查到并被 `ship → receive` 完整闭环消费，**H2 21/21、隔离 MySQL 6/6、全量离线 189（noon 160 + framework 29）全绿**。不依赖任何测试种子调拨单。

未改动契约、迁移 SQL、Controller、前端、E2E 脚本、协作文档；未推送；未连生产。

---

## 1. 改动文件（仅本 owner 范围）

**新增（main）**

| 文件 | 内容 |
|---|---|
| `com/yx/noon/transfer/TransferCreateInput.java` | `record`（public）：from/to 仓、siteCode（仅展示校验）、lines、requestId；无成本/币种/汇率/批次 id 字段 |
| `com/yx/noon/transfer/TransferCreateLine.java` | `record`（public）：`(skuId, batchNo, qty)`，只有选择键与计划量 |

**修改（main）**

| 文件 | 改动 |
|---|---|
| `TransferService.java` | 新增 `create`、CREATE 幂等（`noon_m4_transfer_create_request` 读写/回填）、来源批次权威校验、单号生成、审计；`resultOf` 扩展为两表（先 ship/receive，再 create）；`ACTION_CREATE` 常量 |
| `TransferRules.java` | 新增 `createFingerprint(from,to,lines)`（动作 CREATE 入指纹、按 (skuId,batchNo) 升序） |
| `TransferResult.java` | 注释补 `CREATE`、`childBatchIds` 恒空 |

**修改（test，本 owner）**

| 文件 | 改动 |
|---|---|
| `TransferServiceTest.java` | H2 建表补 `noon_m4_transfer_create_request`；审计表抽出 `createAuditTable()`（回滚用例可 drop/复原）；**新增 7 个 create 用例**（14 → 21） |
| `MySqlTransferIT.java` | 迁移幂等用例由断言 3 表改为 **4 表**并补 create 表主键/`transfer_id NOT NULL` 断言；`clean()` 纳入新表；**新增 2 个用例**（4 → 6） |

本报告：`docs/verification/m4-transfer/doubao-create-report.md`。

---

## 2. 实现口径（逐条对契约 §9）

**顺序**：入参基本校验 → `guard.requireCurrentUserId()` → 两仓 `md_warehouse` 存在且 `ACTIVE`（`loadWarehouse` 拒绝空站点）→ 源≠目标 → **两仓同站点**（跨站 400）→ `guard.requireSite(源)`、`guard.requireSite(目标)` → 请求体 `siteCode` 仅展示校验 → 行结构校验 → 指纹 → DONE 重放 → 来源批次只读权威校验 → PENDING 占位 → 写单/行 → 审计 → 回填 `transfer_id` + DONE。异常整事务回滚。

- **授权**（§9.2.1）：授权先于一切业务校验；请求体 `siteCode` 不参与授权，非空时须等于源仓站点，禁止自报提权。无站点授权 403，跨站/停用/不存在/同仓 400。
- **批次权威**（§9.2.2）：按 `batch_no`（全局唯一）取回后逐项比对 **仓库=源仓 → SKU→ 站点=源仓站点 → status=OPEN → `qty_remaining >= qty`**，另要求 `qty>0`、同一 `(skuId,batchNo)` 不重复；`inv_transfer_item.sku_id` 写批次真实 SKU（客户端 SKU 只是选择键）。批次只读、**不加锁**（理由见下条）。
- **不扣库存**（§9.2.3）：create 只写 DRAFT 单 + 计划行，不写 `inv_batch`/`inv_stock_ledger`/`inv_stock_move`、不建子批次；真实扣减在 ship 的 `FOR UPDATE` 重校验后发生，故两个 create 可同时引用同一批次剩余（守恒点在 ship）。
- **数据**（§9.2.4）：`transfer_no = TR:<SITE>:<UUID前8大写>`（如 `TR:AE:1A2B3C4D`），`uk_transfer_no` 撞键换号重试 3 次（仍失败则 500 式异常回滚）；`status='DRAFT'`；行 `qty_shipped=计划量`、`qty_received=0`、`diff_qty=0`、`batch_no=来源父批次号`；`created_at` 显式写 UTC+8 `TransferRules.now()`。
- **幂等**（§9.2.5）：独立表 `noon_m4_transfer_create_request`；授权 > 指纹 > DONE 重放 > 校验 > PENDING 占位。重放 `replayed=true`、零写入；同键不同内容 400；同键 PENDING 400「正在处理中」；异常整事务回滚（PENDING 一并消失，同键可重试）。
- **指纹**（§9.2.6）：`SHA-256("CREATE|<from>|<to>|<skuId>:<batchNo>:<qty>,...")`，明细按 `(skuId,batchNo)` 升序、逗号分隔（与 ship 的 `itemId:qty` 同分隔风格）；动作 CREATE 入指纹，与 ship/receive 互不串键（且分表）。
- **审计**（§9.2.7）：`noon_m4_inventory_audit(biz_type='TRANSFER', biz_id=transferId, action='CREATE')`，detail 记单号/两仓/站点/行数/总计划量；同事务。
- **resultOf**（§9.2.5）：先查 ship/receive 表（原逻辑不变），再查 create 表：DONE 且 `transfer_id>0` → 回读单据 → 源仓 `requireSite`（跨站再校验目标仓）→ 返回缓存并 `replayed=true`；PENDING/非法键/无行/无单返回 `null`。

### 待裁决 / 契约留白处的落地口径（请 WorkBuddy 裁决）

1. **`transfer_id` 占位 0**：WorkBuddy 的 DDL 为 `transfer_id BIGINT NOT NULL`，而 create 时真实 id 尚未生成。实现用 `0` 作 PENDING 占位，DONE 时 `UPDATE ... SET transfer_id=<真实 id>` 回填（与 DDL 注释「成功后回填」一致）。若希望 PENDING 期允许 `NULL`，需改 DDL（不在我 owner）。
2. **create 不校验目标仓台账/容量**：契约未要求，本轮不检查目标仓是否有台账行；在途量由 ship 累加时按既有 `applyLedger` 规则自动建行。
3. **create 不预占库存**：契约明示「两个 create 可同时引用同一批次剩余量」，先建单后发运时可能因他人先发运而在 ship 阶段 400，属既定口径。
4. **PENDING 期的 resultOf 返回 `null`**：PENDING 无真实 `transfer_id`，无法定位单据做 `requireSite`，故按「未完成」返回 `null`（契约允许）。

---

## 3. 测试与证据

### H2 `TransferServiceTest`（21/21，0 失败/跳过）

原 14 项全部保留且全绿，新增 7 项：

1. `createAuthorizationFollowsWarehousesSitesAndSuppliedSite`：无站点 403、仅 AE 用户建 SA 单 403、跨站 400、源=目标 400、停用 400、不存在 400、自报 `siteCode` 不符 400；越权零写入；授权内建单成功且单号匹配 `TR:AE:[0-9A-F]{8}`、状态 DRAFT。
2. `createValidatesSourceBatchAuthorityAndDuplicateLines`：批次不存在 / SKU 不符 / 仓库不符 / 非 OPEN / 站点不符 / 超剩余 / qty≤0 / 同 `(skuId,batchNo)` 重复，全部 400 且零建单、批次剩余不变。
3. `createWritesDraftPlanWithoutDeductingStockAndIsListGetVisible`：DRAFT、计划行 4 件（`qty_received=0/diff_qty=0`、`batch_no` 保留）、批次仍 10/OPEN、**台账 0 行、流水 0、子批次 0**；审计 CREATE 且 operator 真实；create 幂等表 DONE + 回填 id + 指纹；`list("AE")` 与 `get` 均可见新单。
4. `createIdempotencyReplayConflictPendingAndRetryAfterRollback`：重放 `replayed=true` 且零新增（单/行/审计不变）；同键改量 400「内容与原请求不一致」；预置 PENDING 同键同内容 400「正在处理中」；**占位后删除审计表制造事务中途失败 → PENDING 与半张单一起回滚 → 复原后同键重试成功**。
5. `createThenShipThenReceiveCompletesChainWithoutSeedTransfer`：两行建单 → 全量发运（源仓可用 10→4、在途 +6）→ 收货 6/5 + 4/4（短少 1 记 `TRANSFER_SHORT`、`diff_qty=1`）→ 整单 RECEIVED；`list/get` 反映终态；**全程无种子调拨单**。
6. `createIgnoresClientSuppliedCostCurrencyAndBatchId`：Jackson 解析夹带 `unitCost/currency/fxRate/batchId/qtyRemaining/transferNo/status` 的 JSON → 结构上丢弃；单号/状态服务端生成，批次成本不被 999.0000 影响；客户端自报 SKU 与批次不符 400。
7. `resultOfHitsCreateRecordRequiresSiteAndIgnoresPending`：命中 CREATE 记录返回 DONE 缓存（CREATE/replayed/同单号）；PENDING、非法键、不存在 → null；仅 AE 用户查 SA 键 → 403。

### 隔离 MySQL `MySqlTransferIT`（6/6，errors=0 failures=0 skipped=0，maven_exit=0）

原 4 项全绿（迁移用例扩展为 4 表断言），新增 2 项：

- `createShipReceiveFullChainOnRealSchemaWithoutSeedTransfer`：源批次由**第一轮 `InboundService` 真实入库**产生；create 断言 DRAFT/单号格式/不扣批次不写台账不写流水/幂等表回填真实 id → ship（源批 9→0/CLOSED、目标在途 9、`TRANSFER_OUT -9`）→ receive 7（子批 OPEN、目标可用 7、短少 2 记 `TRANSFER_SHORT` 成本 9.5000/金额 19.0000、`diff_qty=2`）→ **守恒：源批 0 + 子批 7 + 短少 2 = 初始 9**。**未预置任何 `inv_transfer` 种子行。**
- `concurrentSameCreateRequestIdCreatesOneTransfer`：真库两线程同 `requestId` → 恰好 1 个 FRESH + 1 个 REPLAYED/REJECT；`inv_transfer`=1、`inv_transfer_item`=1、审计=1、幂等表 DONE、无 PENDING 残留、批次仍 10。
- 迁移用例新增：create 表存在、`request_id` 主键唯一、`transfer_id NOT NULL`（缺值插入被拒）。

### 数字

- H2：`TransferServiceTest` **21/21**（原 14 + 7）。
- 隔离 MySQL 8.4：`MySqlTransferIT` **6/6**（原 4 + 2）。
- 全量离线 `mvn -pl yxerp-noon -am test`：**BUILD SUCCESS**，`yxerp-noon` **160/160**、`yxerp-framework` **29/29**，0 failures/errors/skipped（原 153 → 160）。

### 证据

- 真库：`python3 scripts/verify-m4-transfer-runtime.py ...` → `tests=6 errors=0 failures=0 skipped=0`、`maven_exit=0`；证据目录 `/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-m4-transfer-p0d5kqve`（一次性隔离库，结束已 drop）。
- 离线：`/Users/dream/Work/noonERP/yx-erp/yxerp-noon/target/surefire-reports/TEST-com.yx.noon.transfer.{TransferServiceTest,MySqlTransferIT}.xml`。

---

## 4. 边界与未覆盖

- **不含 HTTP/Controller/前端/E2E**：Controller `POST /noon/inventory/transfer/create`、前端「新建调拨」弹窗、`verify-m4-transfer-e2e.py` 种子精简由 WorkBuddy owner；本包只保证服务层可被其直接调用（`TransferCreateInput` / `TransferCreateLine` 全 public）。
- **未做**：跨站调拨、行子集发运、ASN/头程/盘点/不合格库存、多仓路由、真实 Noon 平台联调；`receive` 仍要求源仓 ACTIVE（契约 §2.1 既定口径）。
- **未启动完整应用**、未连生产、未部署、未推送；`config/secrets/` 未读取。
- `create` 的成功响应不返回行 id，前端新建后若要立即发运需再 `get(id)` 取行（与既有 `ship` 契约一致，未扩面）。
