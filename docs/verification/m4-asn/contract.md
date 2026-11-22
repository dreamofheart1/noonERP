# M4 库存与 FIFO · 第三轮第三批「FBN 入仓 ASN 收货闭环」契约 v1

日期：2026-11-22。基线：Codex「M4 头程费用登记重点复核」通过并授权继续 M4 ASN；用户明确**一次性持续完成 M4 剩余可独立范围、不再逐批等审核**，重点覆盖 ASN + 相关库存流程 + 盘点出入库时序 + 成本缺失处理，并结合采购入库/调拨/台账做关键流程联调；依赖 M5 的退货场景保留接口不无限阻塞，业务规则需用户决策须标注不自行假定。

本批只做 **ASN 收货闭环**（不动头程分摊、不动不合格库存、不改盘点 v1.2）。不合格库存隔离与盘点期间出入库控制按用户边界另文记录（见 §11）。

---

## §1 业务定位与关键假设（务必先读）

`log_asn` = FBN 入仓预告单（Seller Lab 登记或未来 API 回写）。本批把 ASN 建模为**本地仓 → FBN 仓的入仓调拨**，与已验收的 M4 第二轮调拨**同构**，复用其守恒口径：

- ASN 明细 `log_asn_item.batch_no` = **本地仓已有 OPEN 成本批次**（承载成本，收货时把成本/库存迁到 FBN 仓）。
- `qty_shipped` = 计划/实发量；`qty_received` = FBN 仓实收量；`diff_qty = qty_shipped - qty_received` = 短少。

**技术假设（列为业务待确认，不得宣称已获业务认可，见 §11）**：
- A1「发货即货权转移」：`seal` 时扣源仓可用、加 FBN 仓在途。若真实业务是"供应商直发 FBN 仓、不经本地仓"，则无源仓扣减，需另立直发模式——本批不实现该模式，标注待确认。
- A2 短少成本：`diff>0` 记 `ASN_SHORT` 批次消耗（成本损失）；索赔/补发流程不在 M4。
- A3 多送（`qty_received > qty_shipped`）：DDL CHECK `qty_received <= qty_shipped` 禁止，应用层也拒绝。
- A4 slot 预约/物流追踪（`SLOT_BOOKED`/`IN_TRANSIT` 头状态、`slot_date`/`carrier`/`tracking_no`）：属平台交互、FBN Inbound API 未接入，本批 `seal` 即视为发运（在途由子批次 `IN_TRANSIT` 体现），头状态用 `SEALED` 表示已发运、未实现独立 slot/ship 动作——标注待确认。

---

## §2 数据模型（零新增业务表，只加一张幂等表）

复用 schema v0.5 表：`log_asn`(头)、`log_asn_item`(行)、`inv_batch`(批次/在途子批次)、`inv_stock_ledger`(四态台账)、`inv_stock_move`(流水)、`inv_batch_consumption`(FIFO 消耗，短少)、`noon_m4_inventory_audit`(审计)、`md_warehouse`(FBN 目标仓)。

新增（迁移脚本 `yx-erp/sql/noon_m4_asn.sql`）：

```sql
CREATE TABLE IF NOT EXISTS noon_m4_asn_request (
  request_id         VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
  action             VARCHAR(16)  NOT NULL COMMENT 'CREATE/SEAL/RECEIVE 动作级隔离',
  asn_id             BIGINT       NOT NULL COMMENT 'ASN 头 id；PENDING 期 0 占位',
  site_code          VARCHAR(16)  NOT NULL COMMENT '幂等行站点，resultOf 授权用',
  operator_id        BIGINT,
  content_fingerprint CHAR(64)     NOT NULL COMMENT 'SHA-256(动作|规范化业务串)',
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json        JSON         COMMENT 'DONE 缓存 AsnResult，重放原样返回',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id, action),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 ASN 收货闭环动作级幂等表';
```

**动作级隔离**：同一 `request_id` 在 CREATE/SEAL/RECEIVE 之间不通用——CREATE 的幂等号拿去 SEAL 视为新键（各自独立指纹），但同一 action 重试按内容比对。与调拨 `SHA-256(transferId|SHIP|RECEIVE|...)` 同源（豆包裁决点 D1 确认具体串）。

---

## §3 服务签名（owner：豆包，包 `com.yx.noon.asn`）

```java
public final class AsnService {
  public AsnResult create(AsnCreateInput in);      // → DRAFT，只登记
  public AsnResult seal(AsnSealInput in);         // DRAFT → SEALED（发运：扣源仓+拆在途子批次）
  public AsnResult receive(AsnReceiveInput in);    // SEALED → RECEIVED（按行实收+短少 ASN_SHORT）
  public AsnResult resultOf(String requestId);    // 幂等结果查询（授权）
  public PageResult<AsnRow> list(String siteCode, int offset, int limit);
  public AsnDetail get(long asnId);               // 头+行+子批次血缘（授权）
}
```

Input record（字段，豆包可据实微调命名但需报告）：
- `AsnCreateInput(siteCode, warehouseCode, requestId, operatorId, List<Line>)`；Line = `(skuId, batchNo, qtyShipped, partnerSku)`。
- `AsnSealInput(asnId, requestId, operatorId)`（发货量 = create 登记量，不接受客户端改量）。
- `AsnReceiveInput(asnId, requestId, operatorId, List<Line>)`；Line = `(asnItemId, qtyReceived, shortEventId)`（支持按行分批收货）。

`AsnResult(asnId, asnNo, replayed, action, childBatchIds)`，与调拨 `TransferResult` 同形。

---

## §4 create（登记，DRAFT）

顺序：**授权 → 行结构校验 → 指纹 → DONE 重放 → PENDING claim → 事务写 → DONE 回填**。

1. 授权：`guard.requireSite(siteCode)`；FBN 目标仓按 `(warehouseCode, siteCode)` 查 `md_warehouse`，必须存在、`status='ACTIVE'`、`is_fbn=1`（或 `warehouse_type='FBN'`），否则 400。
2. 行校验：非空、按 `(skuId,batchNo)` 排序去重（重复行拒绝）；`qtyShipped>0`；SKU 存在且 ACTIVE（masterdata）；`batchNo` 对应**本地仓 OPEN 批次**存在、站点 = siteCode（禁止跨站），否则 400（豆包裁决点 D2：create 是否强校验源批次存在/OPEN，或延后到 seal）。
3. 指纹：`SHA-256(CREATE|site|warehouseCode|sorted lines[skuId,batchNo,qtyShipped,partnerSku])`，规范化空串/null 区分。
4. `asnNo = 'ASN:' + siteCode + ':' + UUID8`（服务端生成，不接受客户端传）；唯一键冲突重试。
5. 只写 `log_asn`(DRAFT) + `log_asn_item`(qty_received=0,diff_qty=0) + 审计 `CREATE`，**不动库存/批次/流水**。
6. 幂等：DONE 重放返回缓存（replayed=true）；PENDING 内容冲突 400；claim PENDING（asn_id=0 占位）。

## §5 seal（发运，DRAFT → SEALED）

顺序：授权（按 ASN 头真实站点 + FBN 仓）→ 指纹 → DONE 重放 → 状态闸门（必须 DRAFT）→ claim → 事务。

1. **FOR UPDATE 锁 ASN 头**；源/目标仓 `requireSite`、`ACTIVE` 校验（经仓库归属，禁止越权/跨站）。
2. 逐行 FOR UPDATE 锁 `log_asn_item` + 源批次 `inv_batch`：
   - 源批次必须 OPEN、site = ASN site、sku = 行 sku、`qtyShipped <= qty_remaining`，否则整单拒绝。
   - 源批次 CAS 扣减 `qty_remaining -= qtyShipped`，扣到 0 置 CLOSED。
   - 生成**在途子批次**：`batch_no='B:ASN:'+UUID8`、`source_type='TRANSFER_SPLIT'`、`parent_batch_id=源批次id`、`split_seq=父下最大+1`、`split_reason`=ASN 发运扩展值（豆包裁决点 D3，DDL 无 CHECK 约束可写 'ASN_TRANSIT'，需报告）、`asn_no=asnNo`、`qty_in=qtyShipped`、`qty_remaining=qtyShipped`、`status='IN_TRANSIT'`、`unit_cost`/`currency`/`fx_rate`/`received_at` 继承源批次。
   - 台账双边：源仓 `qty_available -= qtyShipped`、FBN 仓 `qty_in_transit += qtyShipped`（FOR UPDATE 锁行、不存在插入，沿用 InboundService/TransferService 的 `applyLedger` 模式）。
   - 流水：源仓 `ASN_OUT`（负），`ref_doc_no=asnNo`，`raw_payload` 留业务字段。
3. 头状态 CAS `DRAFT→SEALED`、`sealed_at=now`；审计 `SEAL`。
4. 结果 `AsnResult(action='SEAL', childBatchIds)`，DONE 回填。

## §6 receive（收货，SEALED → RECEIVED）

顺序同 seal；状态闸门必须 SEALED。

1. 逐行 FOR UPDATE 锁 `log_asn_item`（`qtyReceived <= qtyShipped`，违反 400）+ 锁定其在途子批次（按 `asn_no` + `parent_batch_id` + 行 sku 定位，豆包裁决点 D4）。
2. `diff = qtyShipped - qtyReceived`；`shortEventId = diff>0 ? 必填 : null`。
3. 在途子批次收敛：`status` 实收>0 → OPEN、=0 → CLOSED；`qty_remaining=qtyReceived`（=0 时保留 `qty_in` 守 CHECK）；CAS `status='IN_TRANSIT'` 防重复收。
4. 台账双边：FBN 仓 `qty_in_transit -= qtyShipped`、`qty_available += qtyReceived`。
5. 流水：FBN 仓 `ASN_IN`（正，实收 0 不写），`ref_doc_no=asnNo`。
6. 短少：diff>0 写 `inv_batch_consumption`，`biz_event_key='ASNSHORT:'+asnItemId+':'+shortEventId`（豆包裁决点 D5 确认格式）、`move_type='ASN_SHORT'`、`qty=diff`、`unit_cost` 取子批次、`amount=qty*unit_cost`、`sign=+1`、`batch_id=子批次id`。唯一键 `(biz_event_key,batch_id)` 防双计。
7. 回写行 `qty_received`、`diff_qty=diff`。
8. 全部子批次离开 IN_TRANSIT → 头 CAS `SEALED→RECEIVED`；审计 `RECEIVE`。
9. 按行分批：不同 requestId 可覆盖不同行，同一行只能收一次（子批次离开 IN_TRANSIT 拒重收），全部收齐整单 RECEIVED。

## §7 resultOf / list / get

- `resultOf`：先查 RECEIVE/SEAL/CREATE 幂等表，命中后按行真实 FBN 仓站点 `requireSite` 才返回 DONE 缓存（replayed=true）；未命中返回明确"无结果"。
- `list(siteCode,offset,limit)`：指定站点先 `requireSite` 再单站过滤；未指定返回全部授权站点；`total` 为同条件真实总数。
- `get(asnId)`：按头站点 `requireSite`，返回头+行+子批次血缘（parent/children/split_seq/asn_no）。

## §8 守恒不变量（必须测试）

- 发运：源仓 available 减 = FBN 仓 in_transit 增 = ΣqtyShipped；源批次 qty_remaining 减同量。
- 收货：FBN 仓 in_transit 减 = ΣqtyShipped（该行）、available 增 = ΣqtyReceived；子批次 qty_remaining = qtyReceived。
- 短少：ΣASN_SHORT 消耗 qty = Σ(qtyShipped - qtyReceived)，只记一次（唯一键）。
- 幂等：同 action 重放零副作用；同键跨 action 各自独立；同键改内容 400；PENDING 崩溃可重试且无残留。
- 授权：越权站点/跨站源批次/FBN 仓非本站点 → 403/400，零写入。
- 全链路：create→seal→receive 后，源仓 available、FBN 仓 available、在途、批次 qty_remaining、流水与消耗金额满足双边守恒。

## §9 生成器采用（用户明确要求）

实调若依生成器 `importTable(log_asn)` / `batchGenCode` 导出前后端骨架至 `docs/verification/m4-asn/generated/raw/`，写 `ADOPTION.md` 对照"生成骨架 vs 实际采用"。ASN 页采用生成的列表/查询骨架，动作按钮（封单/收货/幂等/血缘展开）手工补——与头程/盘点一致。

## §10 测试要求

豆包：H2 单测覆盖 create/seal/receive/幂等/守恒/短少/授权边界；隔离 MySQL IT（真 CHECK、并发同键、真库守恒）。WorkBuddy：Controller + 前端页 + 菜单 + E2E（HTTP + 真实浏览器 UI 走 create→seal→receive，断言台账/批次/流水/消耗/幂等/授权）+ runtime IT。全量：Maven、MySQL IT、HTTP/浏览器 E2E、vite build、生成器实调。

## §11 不实现 / 业务待确认（按用户边界，不臆造）

- **不合格库存隔离**（`qty_unsellable`）：来源依赖 M5 退货 A/B/C 分级，本批不实现；预留接口 `quarantine(skuId,warehouseId,qty,sourceReturnId,grade)`，待 M5 联调成本口径/能否回售，不标完整验收。
- **盘点期间出入库控制**（Codex 要求）：v1.2 快照闸门只在 apply 检测账面变动拒绝；"实盘与建单账面同一时点"的冻结/补偿实际控制需业务确认，另文记录，不声称完全覆盖实物盘点时序。
- **盘盈缺成本**：v1.2 `ZeroCostGainException` 已拒绝零成本盘盈并提示补录，本批不改。
- **头程分摊/利润接入**：仅登记，`allocation_flag` 仅标记，属 M6/业务依赖，不误称完成。
- A1-A4（§1）、源批次强校验时机、事件键/子批次定位规则、split_reason 取值：豆包裁决后写报告，业务向待确认项不自行假定。
- 真实 Noon 平台 API、旧库升级、跨设备异常重试未验证。

## §12 实现落地对照与遗留边界（2026-11-22 全量验证后补）

### 12.1 契约↔实现差异（均合理，已对齐 E2E/前端）
- `AsnItemRow` 首字段实现为 `id`（= `log_asn_item.id`），非契约初稿的 `itemId`；请求体 `AsnReceiveLine.asnItemId` 不变。前端详情 `items[].id` → 收货 `asnItemId` 映射，E2E 已对齐。
- 动作级隔离落地为幂等表 `PRIMARY KEY(request_id, action)`：同一 request_id 在 CREATE/SEAL/RECEIVE 各自独立成行、各自独立指纹；同键跨 action 视为不同幂等域（不是"动作冲突 400"）。E2E 已改为查幂等表按 action 隔离验证，不再断言跨动作 400。
- `shortEventId` 纳入 RECEIVE 指纹（豆包 D1 有意收紧，比调拨严）：同键换事件号 → 400。

### 12.2 盘点期间出入库管理（业务待决，不臆造实现）
Codex 复核要求"实盘与建单账面同一时点，盘点期间须明确控制或记录出入库"。**当前状态**：盘点 v1.2 快照闸门在 apply 时用 `FOR UPDATE` 比对实时台账与建单快照，不一致整单拒绝 `StaleStocktakeException`（400，含 snapshotBook/currentBook/请重新盘点），零写入、单据保持 DRAFT，由用户按新账面重盘——这是**事后检测**口径，能保证不臆造盘盈盘亏，但**不阻止**盘点期间发生出入库。

**需业务决策才能落地的三种口径（本批不实现，不自行假定）**：
| 口径 | 做法 | 影响/待确认 |
|---|---|---|
| A 冻结 | 盘点单 DRAFT 期间禁止该 (sku,warehouse) 出入库/调拨/ASN 发运 | 强业务规则，影响正常作业；需确认冻结范围、时长、例外 |
| B 补偿 | 盘点期间出入库逐笔记录，apply 时用"实盘 + 盘后变动 − 盘前变动"还原 | 等于放弃快照闸门，回到"时刻错位"风险；需可靠事件边界 |
| C 检测+重盘（现状） | 闸门检测变动→拒绝→按新账面重盘 | 已实现；接受"盘点失败需重盘"的作业成本 |

**预留接口（待业务决策后实现）**：`listInventoryMovesDuringCount(warehouseId, skuId, from=createdAt, to=now)` —— 只读返回盘点窗口内的 `inv_stock_move` 流水，供 UI 展示"为何被拒绝"；不改闸门、不臆造冻结。`quarantine(skuId,warehouseId,qty,sourceReturnId,grade)` 仍依赖 M5 退货分级（见 §11）。

### 12.3 盘盈无可追溯成本（v1.2 已实现，本批未改）
`ZeroCostGainException` 拒绝零成本盘盈并提示补录；不接受客户端传成本。补录后重建盘点单走正常闭环（豆包 H2 测试覆盖"补录后重试"）。属已落实，非业务待决。
