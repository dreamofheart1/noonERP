# M4 库存与 FIFO · 第三轮第一批「盘点 + 四态台账校正」契约 v1.2

日期：2026-11-22。基线：Codex「M4 调拨创建入口复核」授权继续 M4 第三轮（ASN/头程/盘点/不合格库存）。本轮**第一批只做盘点闭环**；不合格库存隔离顺延（理由见 §10）。

> **v1.2 重大修正（Codex「M4 盘点关键流程初审」）**：v1 的「apply 实时账面回算差异」是**错误业务口径**——实盘与账面在盘点后同步变动（如出库 10，实物与账面同降 10），旧实盘减新账面会凭空盘盈 10。v1.2 改为**账面快照比对闸门**：建单快照的 `qty_book` 是差异计算的唯一合法基线；apply 时若实时台账与快照不一致，整单拒绝并要求重新盘点，绝不臆造盘盈/盘亏（§5、§6.1）。同时零成本盘盈不再直接过账，改为拒绝并提示补录成本/业务估值（§6.3）。v1 中「实时基线回算」的测试口径同步作废（§12）。

## §0 范围与不动的边界

- 本批交付：`inv_stocktake` / `inv_stocktake_item` 的**创建盘点单 → 应用盘点（过账）→ 详情/列表/结果**闭环，把账实差异回写四态台账，写 `STOCKTAKE_GAIN/LOSS` 流水与批次消耗（STK 事件键）。
- 不在本批：ASN 收货、头程费用登记、不合格库存隔离（qty_unsellable 的独立入口）、盘点的审批流、跨仓盘点、真实 Noon 平台联调。
- 沿用不重做：站点授权 `SiteAuthGuard.requireSite`、幂等三态（PENDING/DONE/result_json + SHA-256 指纹）、同事务审计、分页真实 total、金额 4 位 HALF_UP、业务时刻 UTC+8。

## §1 数据模型（复用 schema v0.5 已定义表，不新增业务表）

- `inv_stocktake(id, stocktake_no, warehouse_id, period, status, created_at)`：状态 `DRAFT → APPLIED`。`stocktake_no` 唯一。
- `inv_stocktake_item(id, stocktake_id, sku_id, batch_no, qty_book, qty_actual, qty_diff, created_at)`：`qty_diff = qty_actual - qty_book`（应用时服务端显式写入，不信客户端）；CHECK `qty_book>=0 AND qty_actual>=0`。
- 新增**幂等表**（迁移追加，风格对齐 `noon_m4_inbound_request`）：

```sql
CREATE TABLE IF NOT EXISTS noon_m4_stocktake_request (
  request_id         VARCHAR(64)  NOT NULL,
  stocktake_id       BIGINT       NOT NULL DEFAULT 0 COMMENT 'APPLY 成功后回填；CREATE/PENDING 用 0 占位',
  warehouse_id       BIGINT       NOT NULL,
  operator_id        BIGINT,
  action             VARCHAR(16)  NOT NULL COMMENT 'CREATE / APPLY，进指纹隔离两动作',
  content_fingerprint CHAR(64)    NOT NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE',
  result_json        JSON,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 盘点幂等表（动作级，CREATE/APPLY 隔离）';
```

- 审计复用 `noon_m4_inventory_audit`：`biz_type=STOCKTAKE`、`biz_id=stocktake_id`、`action=CREATE/APPLY`，同业务事务写入。

## §2 服务接口（workbuddy 定签名，豆包实现 `com.yx.noon.stocktake.*`）

```
public record StocktakeLineInput(Long skuId, String batchNo, int qtyActual, String countEventId) {}
public record StocktakeInput(Long warehouseId, String period, String requestId, List<StocktakeLineInput> lines) {}
public record StocktakeApplyInput(Long stocktakeId, String requestId) {}
public record StocktakeResult(long stocktakeId, String stocktakeNo, String status, boolean replayed) {}
public record StocktakeRow(long id, String stocktakeNo, long warehouseId, String siteCode, String period, String status) {}
public record StocktakeItemRow(long id, long skuId, String batchNo, int qtyBook, int qtyActual, int qtyDiff) {}
public record StocktakeDetail(StocktakeRow head, List<StocktakeItemRow> items) {}

StocktakeResult create(StocktakeInput in);                 // 建单，不改库存
StocktakeResult apply(StocktakeApplyInput in);             // 过账，改四态台账 + 流水 + 批次消耗
StocktakeResult resultOf(String requestId);                // 只读查结果
PageResult<StocktakeRow> list(String siteCode, int offset, int limit);
StocktakeDetail get(Long stocktakeId);
```

Controller（`InventoryController` 加方法或新建 `StocktakeController`，WorkBuddy 定）：`POST /create`、`POST /apply`、`GET /result`、`GET /list`、`GET /get`。权限 `noon:inventory:stocktake:list / :create / :apply`。

## §3 授权（硬约束，顺序不可换）

1. **create**：入参校验 → operatorId → `loadWarehouse(warehouseId)` 必须 ACTIVE → `guard.requireSite(仓库真实 site_code)` → 逐行 `skuId` 必填正整数、`qtyActual>=0`、`countEventId` 限 `[A-Za-z0-9_-]{1,64}` → 内容指纹 → DONE 重放 → PENDING/占位 → 建单。
2. **apply**：入参校验 → operatorId → `lockStocktake(stocktakeId)` + `loadWarehouse(头.warehouse_id)` → `guard.requireSite(头真实站点)` → 状态 DRAFT → 内容指纹 → DONE 重放 → claimRequest → 逐行过账。**授权在判重前**：撤销授权/跨站者持合法 requestId 也只得到 403。
3. **resultOf**：按幂等行取 `warehouse_id` → 仓库真实站点 `requireSite` → 才返回结果；无权 403、不存在 null（replayed 恒 true）。
4. **list**：传 `siteCode` 先 `requireSite(siteCode)`；按授权站点集合过滤（`JOIN md_warehouse` 或头表带 site_code），total 为同条件真实总数；空授权 `WHERE 1=0`。
5. **get**：按头 `warehouse_id → md_warehouse.site_code` 做 `requireSite`。

## §4 幂等

- 指纹（动作进指纹，CREATE/APPLY 互不误伤）：
  - `CREATE = SHA256("CREATE|" + warehouseId + "|" + 按(skuId,batchNo)升序的 "skuId:batchNo:qtyActual:countEventId" 逗号连接)`
  - `APPLY  = SHA256("APPLY|" + stocktakeId)`（apply 只认单，行内容已在 create 固定）。
- 申请处理权：`INSERT ... VALUES(...,'PENDING')` 撞主键 → 读行判 DONE 且内容一致 → replayed=true 返回缓存 `result_json`；PENDING → 400「处理中」；内容/动作不一致 → 400。
- PENDING 占位 `stocktake_id=0`，DONE 前回填真实 id（沿用调拨 create 裁决）。
- 异常回滚：业务异常 → 事务回滚连 PENDING 行一起消失，同键可安全重试（与 M3/M4 一致）。

## §5 建单 create（不改库存）

- `stocktake_no = "ST:<SITE>:" + UUID8`，站点来自仓库真实 site_code。
- `qty_book` 在建单时按行**权威快照**写入台账当时可用量，**是差异计算与过账的唯一合法基线**（v1.2 修正，见 §6.1）；`qty_diff = qty_actual - qty_book` 同为真值。该快照一经写入不可在 apply 时覆盖。
- 建单状态 `DRAFT`；写审计 `CREATE`；不写任何台账/流水/批次消耗。

## §6 应用盘点 apply（核心过账）

### 6.1 账面快照比对闸门（守恒关键，v1.2 重写）

**问题（Codex 初审）**：v1 以 apply 实时台账为基线算 `qty_actual - qty_available`。但真实出库/调拨会让**实物与账面同步变动**——盘点时实物/账面同为 100，之后真实出库 10（实物/账面同为 90），旧实盘 100 减新账面 90 = 凭空盘盈 10。行锁只保证应用瞬间并发，不解决实盘时刻与账面时刻错位。

**v1.2 正解**：`qty_actual` 是某时刻的实盘事实，只有**同一时刻**的账面快照 `qty_book` 才能与之配对算差异。规则：

1. 逐行按 `stocktake_item.id` `FOR UPDATE` 锁行；按 (sku_id, warehouse) `FOR UPDATE` 锁台账，读实时 `qty_available = nowBook`。
2. **快照比对**：取建单时持久化的 `qty_book`（快照，不得被 apply 覆盖）。若 `nowBook != qty_book` → **整单拒绝应用**，抛业务异常 `409 StaleStocktakeException`，错误文案含具体 `skuId / snapshotBook / currentBook / 请重新盘点`；不改台账/流水/批次/状态，仅记审计 `APPLY_REJECT_STALE`（或等价标记）。
3. 若 `nowBook == qty_book` → 差异 `qty_diff = qty_actual - qty_book` 用**快照基线**（等于此时实时值，二者一致），后续盘亏/盘盈照 §6.2/§6.3 过账；`stocktake_item.qty_book/qty_diff` 保持建单值不变（可幂等回读）。
4. 重盘路径：用户按新账面重新建单（快照=新账面）再 apply；旧单保持 `DRAFT`（或由业务作废，作废接口不在本批）。
5. 幂等：`StaleStocktakeException` 是**业务校验失败**，整单回滚（含 PENDING 幂等行/审计之外全部写入），同 requestId 重试仍会再次判定为 stale；不得以 PENDING/DONE 缓存错误结果。

> 若同一盘点单两行指向同一 skuId，建单即拒绝（豆包裁决点 A，已定版）。batchNo 仅追溯标签，不参与基线比对（SKU 级台账）。

### 6.2 盘亏（qty_diff < 0）

- 数量 `loss = -qty_diff`，按 FIFO 从该 (sku,warehouse) 的 `OPEN` 批次消耗：`SELECT ... FROM inv_batch WHERE sku_id=? AND warehouse_id=? AND status='OPEN' ORDER BY received_at,id FOR UPDATE`，逐批扣 `qty_remaining`（扣到 0 置 `CLOSED`）；累计必须能覆盖 `loss`，不足 → 400「可用批次不足以覆盖盘亏」（不透支台账）。
- 每批写一条 `inv_batch_consumption`：`biz_event_key = "STK:" + stocktake_item_id + ":" + countEventId`（来自该行入参，非空）、`move_type='STOCKTAKE'`、`sign=1`、`qty=消耗量`、`unit_cost=批次成本`、`amount=qty*unit_cost`（4 位）、`consumption_no` 唯一、`sku_id`。唯一键 `(biz_event_key,batch_id)` 保证同事件跨批多行合法、重传同批拦截。
- 四态台账：`qty_available -= loss`（FOR UPDATE 后更新，CHECK 保证不为负）；写 `inv_stock_move`：`change_type='STOCKTAKE_LOSS'`、`qty_delta=-loss`、`ref_doc_type='STOCKTAKE'`、`ref_doc_no=stocktake_no`、operator/时间戳、`move_no` 唯一。

### 6.3 盘盈（qty_diff > 0）

- 数量 `gain = qty_diff`。新建 ADJUSTMENT 批次承接成本（**避免账上多出库存却没有成本、后续 FIFO 出库算不出 COGS**）：`source_type='ADJUSTMENT'`、`batch_no='B:STK:<SITE>:'+UUID8`、`qty_in=gain`、`qty_remaining=gain`、`unit_cost`=该盘点行入参未提供成本（**结构上不接受客户端传成本**），按该 (sku,warehouse) 现有 OPEN 批次的**加权平均** `unit_cost`；`currency` 随仓库站点（沿用批次币种，CNY 显式 fx=1）、`received_at=业务时刻`、`status='OPEN'`。
- **v1.2 零成本盘盈闸门（Codex 要求）**：若该 (sku,warehouse) 无任何 OPEN 批次、或加权平均 `unit_cost <= 0`，**不得**让零成本批次进入 FIFO（后续销售 COGS=0、利润虚高，且无人确认过这一估值）。整单拒绝应用：抛业务异常 `409 ZeroCostGainException`，文案含 skuId/仓库/盘盈量并提示「无可追溯成本，请先补录采购价或由业务确认估值」。零成本盘盈是**待业务确认限制**，绝不当作已确认事实。若业务后续确认估值口径，另立契约提供带成本来源的盘盈入口，本批不接受客户端传成本。
- 四态台账：`qty_available += gain`；写 `inv_stock_move`：`change_type='STOCKTAKE_GAIN'`、`qty_delta=+gain`。
- **不写 `inv_batch_consumption`**：盘盈是库存增加，不是消耗（消耗表 sign/amount 口径是 COGS 来源）。

### 6.4 状态与审计

- 全部行成功后：`inv_stocktake.status='APPLIED'`，标记幂等 DONE 并回填 `stocktake_id`、写 `result_json`（`replayed=false` 的首次结果）。
- 同事务写审计 `APPLY`；任一行失败整单回滚（含台账/流水/批次/消耗/幂等/审计）。

## §7 只读接口

- `resultOf` / `list` / `get` 语义见 §3。详情含 head + items（含 `qtyBook/qtyActual/qtyDiff`）。

## §8 生成器采用

按既有口径：对适合的表**实际运行若依生成器并在其上修改**，留生成产物与对照。本批对 `inv_stocktake`（列表/详情结构）实际生成并采用其 Vue/api 骨架；`inv_stocktake_item` 明细随主表弹窗手写；跨表 apply 状态机手写（与入库/调拨同理由说明原因）。生成范围与沿用/手写清单写入 `generated/ADOPTION.md`。

## §9 owner 分工

- 豆包：`yxerp-noon/.../stocktake/**` 服务内核 + H2 `StocktakeServiceTest` + `MySqlStocktakeIT` + `docs/verification/m4-stocktake/doubao-report.md`。
- WorkBuddy：契约、迁移 DDL + 菜单（3400 段）、Controller、前端 api + 页（生成器骨架）、E2E（生成器实调 + create/apply + 盘盈盘亏守恒 + STK 幂等 + 越权/跨站 + 列表详情）、浏览器、全量验证、协作文档。

## §10 边界与裁决留白

- **不合格库存隔离顺延**：`qty_unsellable` 的独立转入入口与 M5 退货 A/B/C 分级共用成本口径（退货待检 vs 损坏 vs 二次上架），提前实现易与退货模块冲突，留第三轮第二批与退货一并定。
- 盘盈加权平均成本、重复行合并、跨期归属等由豆包提裁决点，WorkBuddy 定版（不得臆造成本、不得虚增库存）。
- 本批限制（待业务确认，不宣称已认可）：盘点无审批流直接 apply；盘盈成本取加权平均而非最近采购价；盘点行必须属同一仓库。
- 真实 Noon 平台、旧库升级脚本、跨设备异常重试仍未验证。

## §11 豆包交付裁决定版（v1.1，2026-11-22）

豆包交付报告 `doubao-report.md` §4 提 11 个契约留白裁决点，**全部采纳，未改后端一行代码**：

| # | 裁决 | 定版结论 |
|---|---|---|
| A | 重复 SKU 行 | create 直接 400 拒绝任何重复 skuId（含同 sku+batch），apply 再防御性拒绝。台账是 SKU 级，合并会臆造实盘量，拒绝是唯一无歧义选择 |
| B | countEventId 传递通道 | 落 CREATE 幂等行 `result_json` 附加键 `itemEventIds`（itemId→event），apply 读取复算 `STK:<itemId>:<event>`；顶层仍是四字段 StocktakeResult，附加键 additive |
| C | 盘盈无 OPEN 批次成本 | unit_cost=0、CNY、fx=1、不写消耗；不凭空造成本，代价是 COGS=0 待业务重估（**列为待确认限制**） |
| D | 状态闸门与重放顺序 | 授权→指纹→DONE 重放→状态闸门；否则已 APPLIED 单无法幂等重放（第一轮同口径） |
| E | 授权与 ACTIVE 顺序 | 先 requireSite 再判 ACTIVE，越权恒 403 不泄漏业务状态 |
| F | 盘盈多币种 | 400 拒绝不折算（无汇率来源不臆造） |
| G | period/跨期 | 空取 UTC+8 业务月；消耗 period=单据期间、cross_period=0；跨期快照重算不在本批 |
| H | 台账读取粒度 | 台账唯一键 (sku,warehouse)，按 SKU+仓库读 qty_available；batchNo 仅追溯标签，按批次盘点不支持（**列为待确认限制**） |
| I | 盘亏流水 batch_id | 跨批时 NULL，批次级明细看消耗表 |
| J | resultOf 的 status | 返回单据当前状态，replayed 恒 true |
| K | 审计 detail | 固定摘要键值串（列宽 1024），批次级证据在消耗/流水表 |

**v1.1 说明**：仅定版措辞与服务内部裁决，不改 §2 签名、接口路径与 E2E 口径。三条新增「待确认限制」（盘盈零成本、按批次盘点不支持、无审批一步过账）+ §10 原有三条，均属本轮技术限制、待业务确认，不宣称已认可。

## §12 v1.2 整改说明（Codex「M4 盘点关键流程初审」）

Codex 静态分析指出两处影响库存准确性/利润正确性的问题，**全部成立，v1.2 修正**：

| # | Codex 意见 | v1.2 修正 | 契约位置 |
|---|---|---|---|
| 1 | 建单保存实盘 qty_actual，apply 用旧实盘减实时台账直接调到旧实盘；出库/入库会让账面与实物同步变动，凭空盘盈/盘亏。行锁不解决实盘与账面时刻错位；原「重算为盘盈」测试恰证明错误 | 弃用实时回算；快照 `qty_book` 为唯一合法基线；apply 比对实时台账与快照，不一致整单拒绝 `StaleStocktakeException`，要求按新账面重盘。E2E 原场景反转断言 | §5 / §6.1 |
| 2 | 无历史成本时盘盈批次成本为 0 并进入 FIFO，可能致销售成本/利润错误；未确定可追溯成本或业务确认前不得把零成本当确认事实 | 零成本盘盈整单拒绝 `ZeroCostGainException`，提示补录采购价或业务估值；不接受客户端传成本；标为待业务确认限制 | §6.3 |

**不变量**：v1.2 只改差异基线与盘盈成本闸门，不改 §2 服务签名、接口路径、幂等三态、授权顺序、FIFO 消耗算法、STK 事件键格式。豆包 11 裁决中「盘盈零成本取 0（裁决点 C）」被本整改覆盖为「拒绝过账」；其余裁决不变。E2E 需重写盘盈/盘亏场景以符合快照闸门口径（§12 与验证脚本为准）。
