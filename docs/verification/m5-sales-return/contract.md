# M5 订单与退货 · 编排闭环 + A/B/C 分级处置 契约 v1

日期：2026-11-22。基线：用户/Codex「开始 M5」指令——按 `docs/development-modules.md` 组织，先盘点复用已有入账/FIFO/红冲实现避免重复建设，M5 与 M4 联调退货分级/不合格库存/批次成本红冲，核对站点权限/重复事件/数量守恒，一次性交 Codex 审核。业务规则以 PRD FR-04.6/FR-06.6 + C-2/C-2b（成本100/售价150/全额退款/赔偿30 → L1=−70）为准。

## §1 复用边界（不重复建设，硬约束）

经盘点，以下内部 Spring service 闸门**已实现并经 Codex 多轮复核 + H2/MySQL IT 验证**，本批**不修改其一行代码**，只作为编排层被调用：

| 内部服务 | 包 | 已覆盖能力 | 本批角色 |
|---|---|---|---|
| `IntakeService.postOrder(OrderInput)` | `com.yx.noon.intake` | 站点授权、来源可核验（webhook 事件佐证+payload 一致）、主数据映射、幂等/冲突、隔离 | 订单导入闸门 |
| `IntakeService.postReturn(ReturnInput)` | 同上 | 退货入账闸门（父订单行取真实 sku_id）、同站同号幂等 | 退货入账闸门 |
| `FifoConsumptionService.postDelivery(DeliveryInput)` | `com.yx.noon.fifo` | 妥投出库：FIFO 选批、批次/台账扣减、qty_delivered 同步、SALE 消耗流水、幂等 | 妥投出库闸门 |
| `ReversalService.postReversal(ReversalInput)` | `com.yx.noon.reversal` | 红冲：锁原 SALE 消耗行(sign=+1)、累计不超原消耗、sign=-1 冲回流水、幂等、跨期 | 退货红冲闸门 |

**本批唯一核心缺口**：红冲之后没有 **A/B/C 分级处置**——A 级二次上架建新批次、B/C 级按原采购层成本全额计损（RETURN_LOSS）+ 不合格库存隔离（qty_unsellable）+ 赔偿单次登记。这是 M5 新建重点，也是 M4 遗留 `qty_unsellable` 写入点缺失的补齐。

## §2 架构与新增包

新建 `com.yx.noon.sales` 编排层（豆包 owner），分两个 service：
- `SalesFlowService`：事件接收 + 订单导入 + 妥投出库 + 退货入账 + 红冲 的编排（把 §1 内部服务串成 HTTP 可调用闭环）。
- `ReturnDispositionService`：A/B/C 分级处置（核心新逻辑，§6）。

**编排层职责**：① 把客户端业务动作翻译成内部服务入参；② 为每个动作在系统侧登记可信事件留痕（`int_webhook_event`），再调内部闸门；③ 动作级幂等（独立表）；④ 事务边界与错误映射。
**编排层不做**：不绕过内部闸门、不伪造平台身份、不自行判定来源可核验——事件 payload 严格按内部服务 verify 逻辑生成，字段不匹配闸门会隔离，编排层不吞隔离结果。

> 事件留痕说明：`int_webhook_event` 原先由 M9（集成）接收平台 webhook 写入。本批编排层提供**受控事件登记**作为隔离环境的事件适配占位（模拟 M9 接收），事件类型仍用内部契约 `ORDER`/`RETURN`/`SALE_DELIVERY`/`RETURN_REVERSAL`；真实平台事件类型（Codex 只读实测为 `FBPI::ORDER_SYNC`/`RETURNS::REFERENCE_UPDATE` 等）→内部类型适配属 M9 未确认范围，本批不臆造、明确标注。

## §3 HTTP 接口（SalesFlowController，站点授权 + 动作级权限）

统一幂等：每个写动作入参含 `requestId`，独立幂等表 `noon_m5_sales_request`，PK `(request_id, action)`，PENDING(cost/id=0)→DONE 回填结果 JSON，重放原样返回；内容指纹 `SHA256(action|规范化业务串)`，同键改内容 400。站点授权先于判重（跨站恒 403），与 M4 各批一致。

| 动作 | 路径 | 说明 |
|---|---|---|
| 订单导入 | `POST /noon/sales/order/import` | 生成 ORDER 事件→`postOrder`。入参 orderNo/siteCode/storeId/skuId/partnerSku/grossAmount/qty/currency/sourceChannel；返回 orderId |
| 妥投出库 | `POST /noon/sales/order/deliver` | 生成 SALE_DELIVERY 事件→`postDelivery`。入参 salOrderItemId/siteCode/qty；返回 consumptionIds（FIFO 可能拆多批） |
| 退货入账 | `POST /noon/sales/return/import` | 生成 RETURN 事件→`postReturn`。入参 orderNo/siteCode/sourceReturnNo/salOrderItemId/qty；返回 returnId |
| 退货红冲 | `POST /noon/sales/return/reverse` | 生成 RETURN_REVERSAL 事件→`postReversal`。入参 salReturnId/originalConsumptionId/qty/siteCode；返回 reversalId |
| 分级处置 | `POST /noon/sales/return/dispose` | **核心新逻辑**，见 §6。入参 salReturnId/grade/restockCost?/compensationAmount?/siteCode/requestId |
| 订单列表/详情 | `GET /noon/sales/order/list`、`GET /noon/sales/order/get?id=` | 只读，站点隔离 |
| 退货列表/详情 | `GET /noon/sales/return/list`、`GET /noon/sales/return/get?id=` | 只读，站点隔离 |
| 动作结果回读 | `GET /noon/sales/result?requestId=&action=` | 幂等结果回读 |

**内部闸门返回 `NEEDS_REVIEW`（隔离）时**：编排层如实向客户端返回 `409 + reasonCode + reviewNo`（非 200、非静默成功），客户端可查 `noon_review_queue`（如有）。编排层不把隔离当成功、不自动重试非幂等失败。

## §4 事件登记（编排层内部动作，非独立 HTTP）

对每个写动作，编排层在调用内部闸门前，按内部服务期望的 payload 字段生成 `eventId = <ACTION>:<requestId>` 并写 `int_webhook_event`（status=RECEIVED，退货/红冲事件用 PROCESSED 与内部服务测试口径一致）：
- ORDER payload：`{order_no,store_id,sku_id,partner_sku,gross_amount,qty,currency}`（与 `IntakeServiceTest.orderPayload` 一致；verify 字段名见 `IntakeService.verifyOrderEvidence`）
- RETURN payload：`{order_no,return_no,qty,item_id}`（**字段名以 `IntakeService.verifyReturnEvidence` 为准**：`return_no`=`sourceReturnNo`、`item_id`=`salOrderItemId`；不是 source_return_no/sal_order_item_id）
- SALE_DELIVERY payload：按 `FifoConsumptionService` verify 逻辑生成（DeliveryLine 含 skuId/qty，payload 须含父订单行/明细字段，豆包读源码确认）
- RETURN_REVERSAL payload：`{sal_return_id,reversal_of_id,qty,sku_id}`（见 `ReversalService` line 346-349：sal_return_id=入参、reversal_of_id=原消耗 id、qty=入参、sku_id=父退货真实 sku）
- 事件 status：ORDER/SALE_DELIVERY 用 `RECEIVED`；RETURN/RETURN_REVERSAL 用 `PROCESSED`（与内部服务测试口径一致）；不得用 FAILED/KILLED（闸门会隔离）

`eventId` 用 requestId 派生保证幂等（同动作重放命中同一事件，不重复登记）；`raw_payload` 与入参报文逐字一致。重复登记用唯一键 `uk_event_id` 兜底。

## §5 妥投/退货累计守恒（沿用内部服务，编排层不重复实现）

- 妥投 qty ≤ 订单行 qty − 已妥投；FIFO 拆多批返回多个 consumptionId，合计=妥投 qty。
- 退货 qty ≤ 订单行 qty − 已退货；退货入账真实关联父行 sku_id。
- 红冲累计 ≤ 原 SALE 消耗 qty（内部服务 FOR UPDATE 当前读 + 累计守恒已实现）。
- 编排层校验各动作间引用关系（如 deliver 的 orderItemId 必须是本单真实行、reverse 的 originalConsumptionId 必须是 sign=+1 的 SALE 行且属于该退货父行），不臆造跨站关联。

## §6 A/B/C 分级处置（核心新逻辑，C-2/C-2b）

前置：该 `sal_return` 已存在且已红冲原 SALE 消耗层（`reversal_of_id` 指向 sign=-1 冲回流水）。未红冲先处置 → 400 拒绝（防 COGS 双计）。

**通用第一步（所有等级已由 reversal 完成）**：红冲原 SALE 消耗 `sign=-1`，冲回 COGS；跨期指向 original_period。分级处置在其后执行，不得再红冲一次。

| 等级 | 处置动作 | 成本口径 | 库存影响 | 损益影响 |
|---|---|---|---|---|
| **A**（可二次上架） | 新建 FIFO 批次（`source_type='RETURN_RESTOCK'`、`batch_no='B:RTN:<SITE>:<UUID8>'`、`status='OPEN'`、插队尾按 received_at=业务时刻），回写 `sal_return.restock_batch_no` | 新批次 `unit_cost = restockCost`（入参，缺省取原 SALE 消耗 unit_cost）；资产转移，不产生损益 | 退货实物回可用：`qty_unsellable` 不增（或增后即转回，见§6.1）；新批次 qty_in=qty_remaining=退货 qty | 无 RETURN_LOSS，无损益 |
| **B**（降级/待处理） | 不合格库存隔离 + B 级 RETURN_LOSS | `amount = 原采购层成本 × 退货比例`（=被红冲原消耗 amount，全额，**不得扣赔偿**） | `inv_stock_ledger.qty_unsellable += qty`（不合格库存隔离）；写 `inv_batch_consumption(move_type='RETURN_LOSS', sign=+1, sal_return_id, reversal_of_id)` | RETURN_LOSS 计损 |
| **C**（报废） | 不合格库存隔离 + C 级 RETURN_LOSS | 同 B（全额成本，不扣赔偿） | 同 B | RETURN_LOSS 计损 |

**赔偿 C-2b（防双计红线）**：`compensation_amount` 只登记到 `sal_return`（供 M6 账单在 `platform_fee.COMPENSATION` 减项确认**一次**）；本批**不写任何 COMPENSATION 平台费流水、不从 RETURN_LOSS 扣减赔偿**。若同时出现"RETURN_LOSS 减赔偿"和"COMPENSATION 减项"即为双计（会把 L1 从 −70 错算成 −40）。`return_loss_amount` 写全额成本（100），不是 100−30。

**退款**：`refund_amount` 登记到 `sal_return`，冲减 revenue（revenue 归集属 M7 报表，本批只登记字段不汇总）。

**§6.1 不合格库存隔离口径**：B/C 级退货实物进入 `qty_unsellable`（四态台账最后一块写入点，补齐 M4 遗留）。A 级不进不合格库存——实物直接转新批次可用；若业务要求"A 级先入待检再质检放行"，列为待确认（§9），本批按 PRD"A 级二次上架"直接转可用实现。

**分级处置幂等**：`biz_event_key = 'RTNDISP:<sal_return_id>:<grade>'` 写 consumption（A 级建批次用独立幂等键 `RTNRESTOCK:<sal_return_id>`），重复处置同 returnId+grade → ACK（不重复建批次/计损/隔离）。同一 returnId 换 grade → 400 拒绝（防重复损益），需作废流程（未实现，标待确认）。

## §7 验收基准（C-2/C-2b 统一验算，必测）

PRD 基准：单件成本100、售价150、全额退款、赔偿30。
- 正确：revenue=150−150=0；platform_fee=−30（COMPENSATION 减一次，M6）；cogs=100−100(红冲)+100(RETURN_LOSS)=100；**L1=0−(−30)−100=−70**。
- 双计错：RETURN_LOSS 写70 → L1=−40；不红冲错：cogs=200 → L1=−200。
- 部分退货 2/10（B 级，每件赔偿3）：退的2件净损 −14，未退8件 L1=8×50=400。
- 跨期：原期2026-10、退货2026-11 → 红冲归原期并重算、退款/赔偿归退货期。

E2E 必须断言：红冲流水 sign=-1 且 reversal_of_id 指向原消耗、RETURN_LOSS amount=全额100（非70）、compensation_amount=30 且不写 COMPENSATION 流水、qty_unsellable 正确增减、三级台账守恒。

## §8 授权/幂等/站点/菜单

- 站点授权先于判重；跨站引用（退货/红冲/处置引用他站订单/退货/批次）403。
- 动作级幂等表 `noon_m5_sales_request`（PK(request_id,action)），与内部服务事件级幂等并存、互不替代。
- 菜单 `3700` 段（3700 目录 + 3710 订单/3711 退货/3712 处置等），权限 `noon:sales:*`。
- 迁移 DDL：`yx-erp/sql/noon_m5_sales.sql`（仅幂等表，业务表 schema v0.5 已定义）+ `noon_m5_sales_menu.sql`。
- 生成器：对 `sal_order`/`sal_return` 实调若依生成器导出 list/get 前端骨架与 domain，采用对照写 `generated/ADOPTION.md`；强事务编排/分级处置手写（单表 CRUD 生成器不适用，说明原因）。

## §9 待确认业务规则（不自行假定，明确标注）

1. **分级触发方**：本批由人工在处置动作显式传 grade（质检结果）。是否允许平台事件自动触发 A/B/C、谁有权限改 grade，待业务确认。
2. **A 级 restock_cost 口径（Codex M5 复核收紧）**：PRD `return_restock_cost=ORIGINAL`——A 级为资产转移不产生损益，成本**恒取被红冲原 SALE 层加权成本**，传值仅当与原价一致时放行，任何偏离（含正数）400 拒绝。质检重估需明确业务授权 + 完整资产差额处理，需求未确认，属待决，不臆造阈值/审批流。详见 §13.2。
3. **A 级是否经待检**：本批按 PRD 直接转可用新批次；若需先 qty_unsellable 待检再放行，待确认。
4. **赔偿实际过账**：属 M6 账单（COMPENSATION 平台费减项），本批只登记 compensation_amount；M6 联调时确保单次确认、不双计。
5. **grades 变更/处置作废**：本批不允许同 returnId 改 grade（400），未实现作废/冲正流程，待业务确认。
6. **退货窗口/申诉时效字段**：schema 有 window_deadline/appeal_deadline，本批只登记不校验时效（时效参数属 noon_rule_config，未确认），待确认。豆包核对补充——**可独立部分**（有依据、待用户确认后再接入）：纯计算函数（起算时刻 + 配置天数 → UTC+8 截止 + overdue，天数从 noon_rule_config 解析、禁止写死）、只读暴露 deadline 两列 + overdue 派生（NULL 标"未计算"而非 false）、`*_raw_text`/`*_raw_tz` 保持 NULL 不臆造。**必须待决**（缺来源，无法独立落地）：①起算时点（妥投 vs 退货登记；且 `sal_order_item` 无 delivered_at、M5 导入未写 `sal_order.delivered_at`）；②平台原始截止 vs 配置重算的优先级；③C-03 天数场景（14/15、7/14、Directship 7 天、AE/SA 分站取值）；④高亮阈值（仅逾期 vs +N 天预警；原型"≤3 天红色"是账单争议 D-4，不可借用）；⑤配置可信快照写入依赖 WP2（`noon_config_snapshot.biz_type` 无 SAL_RETURN）。本批不新增时间入参、不臆造截止，维持只登记不校验。
7. **不合格库存后续出库**：B/C 级 qty_unsellable 隔离后，报废/降级销售/残值入账的后续出库流程属后续范围，待确认。豆包核对补充待决：①报废出库/物理核销状态与触发；②降级销售定价/出库/是否重估；③残值科目与金额（PRD 无残值概念）；④损益确认时点（当前处置时一次性计损，后续再产生损益是否二次确认——与 C-2b 防双计红线冲突）；⑤`qty_unsellable→可用/出库` 四态流转与 inv_stock_move 的 move_type（枚举未定义）。当前"全额计损 + qty_unsellable 隔离"忠实 PRD C-2/FR-04.4/原型 P-01，保持不变，不改 writeOff。
8. **真实平台事件适配**：M9 未确认，本批事件登记为隔离环境占位，不臆造平台事件类型/字段。
9. 通用未验证：真实 Noon 平台、旧库升级、跨设备异常重试。
10. **C-03 Directship 7 天场景覆盖**：仅在 PRD `return_window_days` remark / schema 配置注释，未列入正式清单；AE/SA 分站取值未定（锁行预留、仅初始化 GLOBAL=15/14）。
11. **幂等表 TTL/清理**：`noon_m5_sales_request` 无 TTL/清理策略，属运维待决。

## §10 与 M4 联调（任务 87）

写联调脚本 `verify-m5-m4-keyflow-e2e.py`：采购入库→订单导入→妥投出库(FIFO)→退货入账→红冲→B 级分级处置(RETURN_LOSS+qty_unsellable)→A 级分级处置(新批次)→全局数量/成本守恒；核对跨站 403、重复事件幂等、多次退货累计不超原消耗。作为 M5 与 M4 联调证据。

## §11 版本

v1 初稿。后续实现对照与裁决点追加 §12。契约与实现不一致时，以实现为准并在此记录差异原因。

## §12 实现落地对照与裁决（豆包报告，2026-11-22 全量验证后补）

### 12.1 契约↔实现差异（均合理）
- **HTTP 错误码**：契约 §3 承诺隔离结果 409；实现中异常继承 `IllegalArgumentException`（默认 400），WorkBuddy 已在 `SalesFlowController` 补 `@ExceptionHandler(SalesFlowException.class)→409`（含 reasonCode/reviewNo/requestId/action）与 `ReturnDispositionException→409`。跨站=403、隔离=409、其余业务拒绝=400。
- **分级处置严格整单**：D3 实现为 `qty≠退货单 qty → 400 QTY_PARTIAL_NOT_SUPPORTED`（比契约 §6"可空默认整单"更严：不接受部分 qty）；D7 要求 `reversedQty==退货 qty` 才可处置，否则 400 `REVERSAL_INCOMPLETE`（防部分红冲后重复损益）。前端须红冲完成后才放开处置按钮。
- **跨期 period**：D4 `RETURN_LOSS.period` 归退货期（优先 `sal_return.period`），`cross_period` 任一原层不同期即 1，`original_period` 记首层。
- **成本口径**：D2 A 级 restockCost 缺省取原 SALE 消耗层、多层按"红冲金额/qty"加权（保证回补库存金额=冲回 COGS）；D5 RETURN_LOSS 取原消耗行成本、多层逐行拆分、金额合计严格等于红冲合计，合并键用 batchId 防唯一键撞键。
- **A 级库存**：`restock` 除建 RETURN_RESTOCK 批次外还 `addAvailable`（台账 available 增加），保证四态台账与新批次一致；A 级不增 qty_unsellable、不写 RETURN_LOSS。
- **B/C 级隔离**：`qty_unsellable` 按原批次仓库 `FOR UPDATE` 累加（CHECK≥0 真库保证）；RETURN_LOSS 全额=qty×原层成本，赔偿/退款只回写 sal_return 字段，不写任何 COMPENSATION 平台费流水。
- **幂等/事件**：D1 eventId=`<事件类型>:<requestId>` 派生、不建独立事件幂等表（uk_event_id 兜底 + 类型/站点/规范化报文比对）；D8 隔离时事件保留并写 processed_at、释放 PENDING 占位，同 requestId 可修正重试。
- **resultOf 对 PENDING** 返回 400「处理中」（非特殊码）。

### 12.2 豆包 8 裁决（D1-D8）全部采纳，未改后端
D1 事件登记无独立幂等表 / D2 A 级成本加权 / D3 整单处置 / D4 跨期归属 / D5 原层成本逐行拆 / D6 同事务 / D7 全红冲后处置 / D8 隔离留痕可重试——均不违背契约且不臆造业务规则。

### 12.3 验证数字（豆包报告）
H2 `SalesFlowServiceTest` 10/10 + `ReturnDispositionServiceTest` 14/14 = 24/24；隔离 MySQL `MySqlSalesIT` 5/5（maven exit 0）；`yxerp-noon` 全量 222/222（护栏前）；`yxerp-admin -am compile` exit 0；八个既有包零 diff。

---

## §13 Codex M5 初审质疑的口径澄清与护栏（2026-11-22）

Codex 初审提出两处涉及库存与成本的业务边界，集中回应如下。**不擅自假设用户已确认；凡需求已明确的按需求执行，凡需求未明确的标注业务待决。**

### 13.1 C-2b：B/C 全额计损 + qty_unsellable 隔离的语义（不改代码）

**Codex 质疑**：`writeOff` 对 B、C 均全额写 RETURN_LOSS 且把数量加到 qty_unsellable，同时置 DISPOSED；若 C 表示"已报废"，报废实物不应再计为仍在仓的不合格库存，否则库存虚增或重复计损。

**对照需求原文（已明确，非臆造）**：
- PRD §C-2 数字案例表将 B/C 并列为"报废"，基准口径 `cogs = 100 − 100(红冲) + 100(RETURN_LOSS) = 100`，`L1 = −70`（成本100/售价150/全退/赔30）。即**报废 = 全额计一次损 + 赔偿只在 COMPENSATION 计一次**，PRD 从未要求报废时从四态台账"物理删除数量"。
- PRD FR-04.4 定义四态台账含 `qty_unsellable`（不合格/隔离库存），原型 P-01 端到端联动明确"B 级退货 2 件 → 可用 28 不变、unsellable=2 不回可售"（见 `collaboration-archive` 与 `verification/README.md`）。即**"报废/降级"的实物去向就是 qty_unsellable 隔离态**，这是需求既定口径。

**结论与场景验证**：
- 当前实现（B/C → 全额 RETURN_LOSS + qty_unsellable += qty + DISPOSED）**忠实于 PRD C-2 + FR-04.4 + 原型 P-01**，不是"已报废却仍在仓虚增"：qty_unsellable 本身即"在仓但不可售"的隔离态，报废是价值全额损失（损益），隔离是实物去向（台账），二者不重复计损。
- H2 `disposeGradeBWritesFullCostLossAndUnsellableConservation` / `disposeGradeC...` / `c2bBaselineRecomputesL1MinusSeventy` 断言：available 4 + unsellable 1 = 5 守恒、RETURN_LOSS=100（非 70）、L1=−70、同键幂等不二次计损、C 级独立幂等键；隔离 MySQL IT 与真实浏览器 E2E 同口径通过。**以关键场景验证证明：无库存虚增（四态守恒）、无重复计损（单次 RETURN_LOSS + 赔偿不进损耗）。**
- **未擅自改代码**：若将 C 级改为"报废即物理出仓、不进 unsellable"，将与 PRD C-2 表、FR-04.4、原型 P-01 直接冲突，属臆造业务规则，故不改。

**仍需用户决定（契约 §9.7 延续，不标完整验收）**：B/C 级进入 qty_unsellable 后的**后续出库/报废处置**——降级销售出库、物理报废核销、残值入账、损益确认时点——属后续范围，本批仅完成"隔离 + 全额计损"，未实现后续出库，不得据隔离测试通过视为"报废全流程已验收"。

### 13.2 A 级 restockCost 单位/来源/权限与 ORIGINAL 强制口径（Codex M5 复核收紧）

**Codex 两轮质疑**：①（初审）允许客户端指定 restockCost（含 0），资产与原红冲成本不一致；②（复核）"仅拒 0 + 审计留痕"不够——正数客户端覆盖仍可任意偏离原成本，而 PRD `return_restock_cost=ORIGINAL`、A 级资产转移不产生损益。要求默认/ORIGINAL 不允许任意覆盖；重估需明确业务授权 + 完整资产差额处理，不能自行视为已确认。

**核实结论**：
| 维度 | 核实结果 | 依据 |
|---|---|---|
| 单位 | 单件成本，与 `inv_batch.unit_cost` 同口径（本位币 CNY、采购价、不含头程/关税/包材） | PRD FR-03.3 + D2 |
| 来源（缺省） | 被红冲原 SALE 消耗层 unit_cost，多层按红冲金额/qty 加权 | D2，DB 真实来源，可信 |
| 来源（覆盖） | **仅当显式传值 == 系统计算原层成本时放行**；任何偏离（含正数）400 `RESTOCK_COST_OVERRIDE_FORBIDDEN` | 本轮收紧，PRD ORIGINAL |
| 权限 | 复用站点授权 + 处置动作菜单权限；重估授权/凭据属业务待决 | 契约 §9.2，不臆造审批流 |

**已实施（改代码，忠实 PRD ORIGINAL，非臆造）**：
- `ReturnDispositionService.restock` 的二次上架成本**恒取被红冲原 SALE 层加权成本**（资产转移、不产生损益；多层 + 4 位精度下与红冲 COGS 可能有**有界舍入差**，见 §14，不声称恒零）；客户端传值仅在**完全等于原价**时放行（幂等/兼容），偏离一律拒绝。
- `dispose` 入口 `restockCost <= 0` 仍拒绝（`INVALID_RESTOCK_COST`，防 0/负值利润失真）；正数偏离在 `restock` 内经与原价比对拒绝（`RESTOCK_COST_OVERRIDE_FORBIDDEN`）。
- 审计 detail 记 `restockCost`/`restockCostSource=ORIGINAL_LAYER|CLIENT_OVERRIDE`，来源可追溯。
- 前端"上架成本(A)"提示改为"留空取原成本；填入须与原成本一致，偏离将被拒绝"，避免诱导输入。
- 关键场景抽测：H2 用例 `restockCostDeviatingFromOriginalIsRejectedToAvoidProfitDistortion` 断言 0/负/正数偏离（120 vs 原价100）均被拒且无建批/无台账改动；`disposeGradeACreatesRestockBatchAndKeepsUnsellableZero` 传原价 100 放行且 unit_cost=100；隔离 MySQL `MySqlSalesIT.aLevelRestockCreatesRealBatchAndPassesChecks` 真库传原价过 CHECK、资产=原红冲成本。

**仍属业务待决（§9.2，不自行假定，未实现）**：质检重估价要有明确业务授权 + 完整资产差额处理（差额如何入账、是否需凭据/第二人复核、按 SKU 还是退货单授权）——需求未确认，本批不引入阈值/审批流，也不把"留空/等值放行"当作已支持重估。

## §14 四位精度舍入差口径与 M7 衔接（Codex M5 复核任务4）

**Codex 风险**：多原成本层加权 + 四位精度可能产生总额舍入差；要求核对金额/数量口径、用关键场景验证，**不声称数学上始终零差额**，与 M7 利润/快照接口衔接时明确金额口径。

**金额/数量口径（核对结论）**：
- 数量口径：`qty` 为退货件数（int），红冲层 `Σ reversal.qty == head.qty`（处置前置闸门，line 168-183），建批 `qty_in=qty_remaining=qty`。
- 金额口径：`reversedAmount = Σ 各红冲层 amount`（每层 `unit_cost×qty` 已按 4 位 HALF_UP 归一化，line 169-170）；`unit_cost = money(reversedAmount / qty)`（4 位 HALF_UP，line 237）；建批资产 `= unit_cost × qty`。
- **舍入差来源**：当 `reversedAmount` 不能被 `qty` 在 4 位精度内整除时，`unit_cost×qty ≠ reversedAmount`。差额**紧界**：`|建批资产 − 红冲 COGS| ≤ 0.00005 × qty`（unit_cost 4 位 HALF_UP 每件最大误差 0.00005 × qty）。**这是 4 位 DECIMAL 表示的必然结果，不是账务错误，也不声称恒零。仅当 Σamount 在 4 位精度可被 qty 整除时才恰为零。**
- **仅 A 级有此差**：B/C 级 `writeOff` 按原层逐行 `amount = money(loss.unitCost × loss.qty)`（无除法），Σloss 严格等于 Σ红冲金额（D5），**无舍入差**；本问题只出现在 A 级"先加权求单价、再 ×qty"的路径。

**关键场景验证（H2 真跑，`aLevelRestockFourDigitRoundingDifferenceIsBoundedAndExplicit`）**：
- 场景：FIFO 妥投 6 件 = 5@100 + 1@120 → 红冲 COGS = 620.0000；A 级上架 `unit_cost=620/6=103.3333`（4 位），建批资产 `=103.3333×6=619.9998`。
- 断言：差额 `=619.9998−620.0000=−0.0002`（非零）、紧界 `|差|≤0.00005×6=0.0003`（实得 0.0002 在界内）、台账守恒（可用 10、不合格 0）。用例全绿。
- 豆包独立核对补充场景：`qty=3` 三层成本 100.0000/100.0001/100.0000 → Σ红冲 300.0001、单价 round(300.0001/3,4)=100.0000、建批 300.0000 → 差额 +0.0001；反向两层 100.0001 → −0.0001。印证差额可正可负、紧界 `qty×0.00005`。

**与 M7 利润/快照接口衔接（口径约定，M7 未实现不标完成）**：
- M7 计算退货对 L1 利润影响时，**A 级按实际入账金额取数**：红冲冲回 `Σ RETURN_REVERSAL.amount`（原期，权威 COGS 以 `inv_batch_consumption` 实际流水为准，不用 `unitCost×qty` 反推），建批资产按 `inv_batch.unit_cost × qty_remaining`（退货期）。两者在多成本层 + 除不尽时可能有 ≤ 0.00005×qty 的舍入差，**不得假设恒等而省略其一，禁止在 M7 快照校验中写死"回补资产=冲回 COGS"不变量**。
- 舍入差归属：属批次资产与 COGS 冲回的四位表示差，建议在 M7 利润快照以实际入账金额各自归期反映（差额自然体现在成本/资产行），不单列损益科目（避免臆造新科目）；最终归属口径属 M7 待决，本批只约定取数字段、不实现利润逻辑。
- 交接字段：`reversedAmount`（红冲层 amount 之和）、`restockBatchNo` + `inv_batch.unit_cost × qty_in`（建批资产）、`return_loss_amount`（B/C 已全额计损）。M7 联调时按此口径验算，不承诺跨期金额逐分相等。

**本批不改代码行为**：仅修正"回补资产=冲回 COGS 零差额"的绝对化注释/文档表述（改"有界舍入差、按实际入账金额承接"），新增上述 H2 关键场景；成本计算逻辑（ORIGINAL 强制）不变。
