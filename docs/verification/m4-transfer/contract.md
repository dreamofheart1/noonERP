# M4 库存与 FIFO · 第二轮「调拨 + 在途拆批 + 短少」契约 v1.2

日期：2026-11-22。v1.1 修订：按豆包交付报告 §4 的 10 个裁决点定版措辞（新增 §8）。v1.2 修订：按 Codex 第二轮复核补齐「创建调拨单」入口（新增 §9），并把几处实现口径明确标注为**本轮技术限制、待用户业务确认**，不再视作已确认的业务决定。依据：`docs/development-modules.md` M4 行 + Codex「M4 v1.1 第一轮复核通过、授权继续 M4 第二轮」授权（见 `docs/agent-collaboration.md`）。

## 0. 第二轮范围（先读，收敛边界）

**第二轮只做调拨闭环**（C-5 核心），直接延伸第一轮的四态台账：
- ✅ 做：调拨单 `DRAFT→IN_TRANSIT→RECEIVED`；出库仓批次**拆批**生成在途子批次（`TRANSFER_SPLIT` + 血缘）；四态台账 `qty_available`(源仓减) / `qty_in_transit`(目标仓增) 双边守恒；收货时在途转 OPEN、在途减、可用增；**短少**记 `inv_batch_consumption(TRANSFER_SHORT)` 且总量守恒；站点授权、并发幂等、数量守恒；复用第一轮幂等/台账/审计范式。
- ✅ 做：真库端到端验证「调拨拆出的在途批次，收货转 OPEN 后能被现有 `FifoConsumptionService.postDelivery` 消费」。
- ❌ 不做（第三轮）：ASN `log_asn`、头程 `log_shipment_cost`、盘点 `inv_stocktake`、不合格/待检 `qty_unsellable` 的完整流程、多段（国内仓→中转仓→FBN）自动路由。这些表 DDL 在 schema v0.5，本轮建调拨所需表，不冲突。
- ❌ 不连生产、不部署、不推送、不碰密钥；全部隔离 MySQL 8.4 + H2。

## 1. 数据表（新建隔离迁移 `noon_m4_transfer.sql`，幂等 DDL）

沿用 schema v0.5 定义、不改字段语义：`inv_transfer`、`inv_transfer_item`。本轮还会**写入**第一轮已建的 `inv_batch`（血缘字段）、`inv_stock_ledger`（四态）、`inv_stock_move`、`inv_batch_consumption`（短少），这些表由既有迁移建立，本文件用 `CREATE TABLE IF NOT EXISTS` 声明依赖或由测试/真库既有脚本提供，避免重复定义。

新增（本轮补）：
- 调拨幂等表 `noon_m4_transfer_request`：`request_id VARCHAR(64) PK`、`transfer_id BIGINT`、`content_fingerprint CHAR(64) NOT NULL`、`status VARCHAR(16) PENDING/DONE`、`result_json JSON`、`operator_id BIGINT`、`created_at`。语义对齐 M4 第一轮 `noon_m4_inbound_request`（授权>指纹>DONE重放>状态闸门、PENDING 并发拒绝、异常整事务回滚）。ship 与 receive 各用独立 requestId（不同幂等命名空间，前缀区分或 transfer_id+动作绑定指纹）。
- 菜单 `noon_m4_transfer_menu.sql`：`noon:inventory:transfer:{list,query,add,ship,receive}`（menu_id 3300 段，避开 M4 第一轮 3200 段）。

## 2. 核心规则（Codex 重点审核点）

1. **站点授权经仓库归属**：`inv_transfer` 无 site_code。授权口径：源仓与目标仓 `md_warehouse.site_code` 必须都获 `guard.requireSite` 授权，且两仓 `status='ACTIVE'`；**源仓与目标仓必须同站点**（调拨在站点内进行，跨站调拨属第三轮在途路由，本轮拒绝 400）。禁止用请求体自报站点。ship/receive/result/list 全部按此口径。
2. **拆批血缘（C-5）**：ship 时对源批次 `SELECT ... FOR UPDATE` 锁行，`qty_shipped <= 源批次.qty_remaining`（不足 400）；源批次 `qty_remaining -= qty_shipped`（扣到 0 置 `CLOSED`）；创建子批次：`batch_no` 服务端生成 `B:TR:<UUID>`、`sku` 继承源批次、`warehouse_id=目标仓`、`received_at=源批次.received_at`（**FIFO 顺序按父批次原始入库时刻延续**）、`qty_in=qty_remaining=qty_shipped`、`unit_cost/currency/fx_rate` 继承源批次（**成本不重估、不摊头程**）、`source_type='TRANSFER_SPLIT'`、`parent_batch_id=源批次.id`、`split_seq=父下递增`（同父多次拆批 `MAX(split_seq)+1`）、`split_reason='TRANSFER'`、`transfer_no`、`status='IN_TRANSIT'`。
3. **四态台账双边守恒**：
   - ship：源仓 `qty_available -= qty_shipped`（不足 400）、目标仓 `qty_in_transit += qty_shipped`。
   - receive：目标仓 `qty_in_transit -= qty_shipped`、`qty_available += qty_received`。
   - 台账按态操作：新增按字段累加/扣减的 DAO（`FOR UPDATE` 行锁 + 唯一键兜并发），**不改第一轮 `InboundService.applyLedger` 的站点一致性语义**。四态均不得为负（DDL CHECK + 应用层前置校验）。
4. **库存流水**：ship 写 `inv_stock_move(TRANSFER_OUT, qty_delta=-qty_shipped, 源仓)`；receive 写 `TRANSFER_IN(qty_delta=+qty_received, 目标仓)`（**实收 0 不写该流水**，真 DDL `chk_move_delta(qty_delta<>0)`）。`move_no` 唯一、带 operator/时间戳（不删字段）。
5. **短少守恒（C-5/事件键复用）**：`qty_received <= qty_shipped`（DDL CHECK）。短少 `diff = qty_shipped - qty_received`：
   - 子批次 `qty_remaining` 收敛为 `qty_received`（>0 转 `status='OPEN'`，=0 转 `CLOSED`）；`qty_in` 在零收货时**保留发货量**（真 DDL `chk_batch_qty(qty_in>0)` 禁止写 0，历史发货量不丢）。
   - 短少部分记一条 `inv_batch_consumption`：`move_type='TRANSFER_SHORT'`、`sign=1`、`biz_event_key='TSHORT:'+transfer_item_id+':'+short_event_id`（schema 注释规则）、`qty=diff`、`unit_cost` 继承、`amount=qty*unit_cost`、`consumption_no` 唯一。同一 short_event 重放被唯一键拦截，不双计。
   - `inv_transfer_item.diff_qty = diff` 显式回写。总量守恒：`源批次.qty_remaining + 所有在途/已收子批次 + 已记短少 = 初始剩余`（真库 IT 断言）。
6. **幂等**：`request_id` UUID，绑定 `transfer_id|动作(SHIP/RECEIVE)|内容指纹`。内容指纹 = SHA-256(transferId|动作|按 itemId 升序的 itemId:qty)。授权 > 指纹 > DONE 重放 > 状态闸门；重放返回首次结果并 `replayed=true`，不重复拆批/改台账/写流水/短少。PENDING 并发 → 400「处理中」；内容不一致 → 400；异常整事务回滚。ship 与 receive 的 requestId 必须不同动作（内容指纹含动作，天然隔离）。
7. **状态机**：`inv_transfer.status` DRAFT→(ship)IN_TRANSIT→(receive)RECEIVED；全部收齐后可 CLOSED（本轮不强制关单）。`inv_batch.status` OPEN(源)→IN_TRANSIT(子)→OPEN/CLOSED(子，收货后)。无审批流。
8. **防伪造**：入参只含 `transferId / fromWarehouseId / toWarehouseId / lines(itemId, qtyShipped 或 qtyReceived) / requestId`；SKU/成本/批次号/源批次全部服务端按 itemId 权威带出，客户端夹带字段由 record 结构丢弃（与 M4 第一轮 §2.3 同口径）。

## 3. 接口契约（新包 `com.yx.noon.transfer`，风格参考 `inventory`）

所有 Service 构造注入 `JdbcTemplate` + `SiteAuthGuard`；record 均 `public`；时间 UTC+8；异常 `IllegalArgumentException`(400) / `guard` 抛 403。

```java
public record TransferShipInput(Long transferId, Long fromWarehouseId, Long toWarehouseId, String siteCode, List<TransferShipLine> lines, String requestId) {}
public record TransferShipLine(Long itemId, int qtyShipped) {}
public record TransferReceiveInput(Long transferId, Long toWarehouseId, List<TransferReceiveLine> lines, String requestId) {}
public record TransferReceiveLine(Long itemId, int qtyReceived, String shortEventId) {}
public record TransferResult(Long transferId, String transferNo, boolean replayed, String action, java.util.List<Long> childBatchIds) {}
public record TransferRow(Long id, String transferNo, Long fromWarehouseId, Long toWarehouseId, String status, String siteCode, Integer itemCount, java.time.LocalDateTime createdAt) {}
public record TransferDetail(TransferRow head, java.util.List<TransferItemRow> items) {}
```

Service 公开方法：
```java
TransferResult ship(TransferShipInput in)
TransferResult receive(TransferReceiveInput in)
TransferResult resultOf(String requestId)
PageResult<TransferRow> list(String siteCode, int offset, int limit)
TransferDetail get(Long id)
```

## 4. 依赖与复用

- `com.yx.noon.masterdata.PageResult`；`SiteAuthGuard`；`md_warehouse`（仓库归属/ACTIVE）。
- 复用第一轮范式（同口径、可复制而非改原类）：`InventoryRules.requireRequestId/contentFingerprint/sha256Hex/now`（如需跨包，提升为公共工具或在 transfer 包同口径实现）、幂等表 PENDING/DONE、`noon_m4_inventory_audit` 审计、`inv_stock_ledger` 按态累加范式。
- 真库端到端复用现有 `FifoConsumptionService.postDelivery`（只读调用验证子批次转 OPEN 后可被消费，不改其代码）。

## 5. 验证要求（隔离环境）

- H2 单测 `TransferServiceTest`：授权（无站点/跨站/两仓站点不一致/仓库非 ACTIVE）、拆批守恒（超源批次剩余 400、源批次扣减、血缘字段正确、split_seq 递增）、台账双边（源可用减、目标在途增、不足拒绝）、收货（在途→OPEN、在途减可用增、短少记 TRANSFER_SHORT、diff_qty 回写）、幂等（ship/receive 各自重放 replayed、并发、内容冲突、失败回滚）、resultOf 两态。
- 隔离 MySQL `MySqlTransferIT`：真 DDL CHECK（qty_received<=qty_shipped、四态>=0）、并发同键仅拆一次、真库端到端「调拨子批次收货转 OPEN 后被 `postDelivery` 消费」，总量守恒断言。
- HTTP E2E + 真实浏览器：完整应用（MySQL+Redis+若依），调拨 ship/receive 闭环、幂等/越权/内容冲突、列表/详情、生成器实调与采用、真 UI 闭环。
- 全项目离线 `mvn test`、`vite build`；结果写入 `docs/verification/m4-transfer/`。

## 6. 文件归属（owner，防同时改同一文件）

| 负责方 | 文件/目录 |
|---|---|
| 豆包 | `yx-erp-noon/src/main/java/com/yx/noon/transfer/**`（新建）；`yx-erp-noon/src/test/java/com/yx/noon/transfer/**`（H2 + `MySqlTransferIT`）；`docs/verification/m4-transfer/doubao-report.md` |
| WorkBuddy | `contract.md`；`yx-erp/sql/noon_m4_transfer.sql` + `noon_m4_transfer_menu.sql`；`yxerp-admin/.../noon/TransferController.java`；`yxerp-ui/src/api/noon/inventory/transfer.js` + `src/views/noon/inventory/transfer/**`；`scripts/verify-m4-transfer-*.py`；生成器产物 `generated/**`；协作文档 |

严禁跨 owner；H2 建表豆包在测试文件内完成，真库迁移 WorkBuddy 在 SQL 文件完成，两边字段一致。

## 7. 生成器要求（M4 起新规）

按 v1.1 已确立口径：对适合的表**实际运行若依生成器并在其上修改**，生成产物与采用对照留存；不适合部分如实说明。第二轮对 `inv_transfer`（只读列表/详情结构）实际生成并采用其 Vue/api 骨架，跨表 ship/receive 状态机手写并说明原因。

## 8. 豆包交付报告 §4 裁决（v1.1 定版，WorkBuddy 2026-11-22）

逐条裁定：**全部采纳豆包口径，不改代码、不改测试**。前 2 条是契约文字与真 DDL 硬约束冲突，已回改 §2.4/§2.5；其余是契约留白，按下列措辞补定，作为 Controller/前端/Codex 复核的统一依据。

| # | 裁决点 | 裁定 | 定版口径 |
|---|---|---|---|
| 1 | 零收货时子批次 `qty_in` | 采纳实现 | `qty_remaining=0`、`status=CLOSED`、`qty_in` 保留发货量；真 DDL `chk_batch_qty(qty_in>0)` 优先。已修 §2.5。 |
| 2 | `TRANSFER_IN` 流水 `qty_delta=0` | 采纳实现 | 实收 0 不写该流水，短少由 `TRANSFER_SHORT` 单独记账；真 DDL `chk_move_delta(qty_delta<>0)` 优先。已修 §2.4。 |
| 3 | ship 行覆盖 | 采纳实现 | **整单发运**：必须覆盖全部 `inv_transfer_item` 行、每行恰好一次且 `qtyShipped>0`；单行实发量可 ≤ 计划量。未发单行不产生在途子批次，receive 无从定位，故本轮不做行子集发运。前端已同步取消"过滤 0 量行"并提交全部行。 |
| 4 | receive 分批 | 采纳实现 | 允许多个 requestId 按行分批收货；同一行只可收一次（子批次离开 `IN_TRANSIT` 即视为已收）；全部子批次离在途时整单 `RECEIVED`。 |
| 5 | `itemId` 指代 | 确认笔误 | `inv_transfer_item` 无 `item_id` 列；`itemId` 一律指 `inv_transfer_item.id`（行主键），也是 `TSHORT` 事件键中间段。Controller/DTO/前端均按行主键传。 |
| 6 | `shortEventId` 格式 | 采纳实现 | `[A-Za-z0-9_-]{1,64}`，短少时必填；冒号/中文/超长 400。事件键 `TSHORT:<itemId>:<shortEventId>` 以冒号分隔，故事件号本身不得含冒号。 |
| 7 | 指纹不含 `shortEventId` | 保持契约字面 | 指纹 = `SHA-256(transferId|动作|按 itemId 升序的 itemId:qty)`，不含事件号；同 requestId 重放即使事件号变化也返回首次结果（幂等语义），如需强一致须另立契约。 |
| 8 | receive 要求源仓 ACTIVE | 采纳实现 | 契约 §2.1 字面要求两仓 ACTIVE，receive 沿用；发货后源仓停用阻塞收货属既定口径，不为便利放宽。 |
| 9 | `batch_no` 语义 | 采纳实现 | `inv_transfer_item.batch_no` 保持来源父批次号；子批次通过 `parent_batch_id + transfer_no` 定位，行上回带 `childBatchId/childBatchNo/childStatus` 供 UI，收货后不覆写父批次血缘。 |
| 10 | 调拨单创建 | 维持范围外 | 第二轮只做 `ship/receive/resultOf/list/get`；DRAFT 单由种子/后续 `create` 契约产生，本轮服务不越权扩面。 |

**边界声明**：跨站调拨、部分行发运、ASN/头程/盘点/不合格库存、真实数据库升级脚本、跨设备异常重试均不在第二轮范围；真实 Noon 平台未联调。

**本轮限制 vs 业务决定（Codex 要求澄清，不得自行视作用户已确认）**：
- ③ 整单发运（必须覆盖全部行、每行 >0）：因未发行不产生在途子批次、receive 无从定位，是**本轮技术限制**；非用户业务决定。后续若需部分行发运，须先定义未发行处置与部分在途状态机。
- ④ 同一行只能收一次、不足即短少记 `TRANSFER_SHORT` 损失：防重复记账成立，但「短少即损失、不挂待查/索赔」是**本轮记账口径**，非用户已确认的业务规则，待业务确认。
- ⑧ receive 仍校验源仓 ACTIVE：来自契约 §2.1 字面，是保守选择；「发货后源仓停用是否应阻塞收货」**待业务确认**，不改代码前按现状执行。

## 9. 创建调拨单入口（v1.2 新增，回应 Codex「无创建接口、只能处理种子单」）

Codex 复核指出：原第二轮只有 ship/receive/resultOf/list/get，真实用户无法从系统创建 DRAFT 调拨单，闭环只能靠种子，不能认定可用。本轮补齐 `create`，实现「新建 → 发运 → 收货」关键流程在隔离环境闭环，不依赖测试种子。

### 9.1 接口与 record（新包 `com.yx.noon.transfer`，风格同 §3）

```java
public record TransferCreateInput(Long fromWarehouseId, Long toWarehouseId, String siteCode,
                                  List<TransferCreateLine> lines, String requestId) {}
public record TransferCreateLine(Long skuId, String batchNo, int qty) {}   // qty = 计划发货量
// 复用 TransferResult(action = "CREATE", childBatchIds = 空)
TransferResult create(TransferCreateInput in)   // TransferService 新增方法
```
Controller：`@PreAuthorize("@ss.hasPermi('noon:inventory:transfer:add')")` + `POST /noon/inventory/transfer/create`（菜单 3301 已存在，无需新增迁移）。前端列表页增加「新建调拨」按钮与弹窗。

### 9.2 核心规则

1. **授权在一切之前**：源仓/目标仓 `md_warehouse` 必须存在且 `ACTIVE`；`guard.requireSite(两仓 site_code)` 都要过；**两仓同站点**（跨站 400）、源≠目标；请求体 `siteCode` 仅展示校验，禁止自报站点提权。错误码：403 无权 / 400 参数与规则违反。
2. **来源批次服务端权威校验**（防伪造）：每条 line 的 `(skuId, batchNo)` 必须在 `inv_batch` 命中唯一 OPEN 批次，且 `warehouse_id=源仓`、`site_code=源仓站点`、`qty_remaining >= qty`、`qty>0`；同一 `(skuId,batchNo)` 不得重复提交。SKU/成本/币种/汇率/来源批次 id 全部由服务端带出写入 `inv_transfer_item`，客户端 record 结构上不含这些权威字段（夹带被 Jackson 丢弃）。
3. **创建不扣库存**：create 只写 DRAFT 单与计划行，**不扣批次、不动台账、不写流水**；实际扣减发生在 ship（`FOR UPDATE` 批次重新校验剩余，不足 400）。因此两个 create 可同时引用同一批次剩余量，真正守恒发生在发运——契约如实声明此口径，IT 需覆盖「create 不扣库存、ship 才扣」。
4. **单号与数据**：`transfer_no` 服务端生成 `TR:<SITE>:<UUID前8大写>`（唯一键兜重）；`inv_transfer.status='DRAFT'`；每行 `qty_shipped = 计划量`（DRAFT 期该列语义为计划发货量，与 §8 第 5 条一致）、`qty_received=0`、`diff_qty=0`；`batch_no` 保持来源父批次号。
5. **幂等**：新建独立幂等表 `noon_m4_transfer_create_request`（`request_id VARCHAR(64) PK`、`transfer_id BIGINT`、`content_fingerprint CHAR(64)`、`status PENDING/DONE`、`result_json JSON`、`operator_id BIGINT`、`created_at`），与 ship/receive 幂等表分离（因 create 时还没有 transferId）。语义同 §2.6：授权 > 指纹 > DONE 重放 > 校验 > PENDING 占位；异常整事务回滚；重放 `replayed=true` 原样返回零写入；内容冲突 400、PENDING 并发 400。`resultOf(requestId)` 同时查两张幂等表：命中 CREATE 记录时按结果里的 transferId 回读单据做 `requireSite` 后返回缓存结果。
6. **内容指纹**：`SHA-256("CREATE|" + fromWarehouseId + "|" + toWarehouseId + "|" + 按 (skuId,batchNo) 升序拼接的 skuId:batchNo:qty)`；动作 `CREATE` 进入指纹，与 ship/receive 天然隔离。
7. **审计**：写 `noon_m4_inventory_audit(biz_type='TRANSFER', action='CREATE')`，与业务同事务。

### 9.3 验证要求

- H2 `TransferServiceTest` 补 create：授权（无站点/跨站/两仓站点不一致/仓库停用/源=目标/自报站点）、批次可用性（不存在/SKU 不符/仓库不符/非 OPEN/超剩余/重复行）、防伪造（权威字段客户端结构上不可见）、幂等（重放 replayed、内容冲突、PENDING 并发、回滚重试）、create 不扣库存而 ship 才扣、新单能被 list/get 按站点查到。
- MySQL `MySqlTransferIT` 补：真 DDL CHECK 约束、**create → ship → receive 全链路不依赖种子单**、真库并发同键只建一单。
- HTTP E2E + 浏览器：改为通过 UI/API 新建调拨单后再发运/收货，种子只保留仓库/SKU/批次/台账基础数据，不再预置调拨单。
