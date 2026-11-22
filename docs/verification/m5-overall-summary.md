# M5 订单与退货 · 整体交付汇总（2026-11-22）

基线：用户/Codex「开始 M5」——按 `docs/development-modules.md` 集中开发订单与退货，先盘点复用已有入账/FIFO/红冲避免重复建设，M5 与 M4 联调退货分级/不合格库存/批次成本红冲，核对站点权限/重复事件/数量守恒，一次性交 Codex 审核。业务规则以 PRD FR-04.6/FR-06.6 + C-2/C-2b（成本100/售价150/全额退款/赔偿30 → L1=−70）为准。未连生产、未部署、未推送、未碰密钥；推送由用户负责。

## 1. 完成范围

| 子域 | 状态 | 实现 |
|---|---|---|
| 订单导入 | ✅ | `SalesFlowService.importOrder`：登记 ORDER 事件 → 复用 `IntakeService.postOrder`（授权/来源可核验/主数据/幂等/隔离） |
| 妥投出库 | ✅ | `deliverOrder`：登记 SALE_DELIVERY 事件 → 复用 `FifoConsumptionService.postDelivery`（FIFO 选批/扣批次扣台账/累计妥投守恒） |
| 退货入账 | ✅ | `importReturn`：登记 RETURN 事件 → 复用 `IntakeService.postReturn`（父行取真实 sku_id/幂等） |
| 退货红冲 | ✅ | `reverseReturn`：登记 RETURN_REVERSAL 事件 → 复用 `ReversalService.postReversal`（sign=-1 冲原 SALE 消耗/累计不超原消耗） |
| **A/B/C 分级处置** | ✅ **本批核心新增** | `ReturnDispositionService.dispose`：A 建 RETURN_RESTOCK 新批次回可用；B/C RETURN_LOSS 全额计损 + qty_unsellable 隔离；赔偿/退款只登记 |
| HTTP/UI 入口 | ✅ | `SalesFlowController`（10 端点）+ 前端单页多弹窗（订单/退货 tab + 5 动作弹窗）+ 菜单 3700 段 |
| M4↔M5 联调 | ✅ | `verify-m5-m4-keyflow-e2e.py`：M4 采购入库生成批次 → M5 全链路 → 全局守恒 |

**复用边界（硬约束，未重复建设）**：`intake`/`fifo`/`reversal`/inventory/transfer/asn/stocktake/shipment 八个既有包零 diff；三个内部闸门只调用不修改。M5 唯一新增是编排层 + 分级处置。

## 2. 关键变更与接口

- 契约 `docs/verification/m5-sales-return/contract.md` v1 + §12 实现对照；豆包报告 `doubao-report.md`（裁决 D1-D8）；生成器采用 `generated/ADOPTION.md`。
- 迁移：`yx-erp/sql/noon_m5_sales.sql`（幂等表 noon_m5_sales_request）、`noon_m5_sales_menu.sql`（菜单 3700 段 + `noon:sales:*` 权限）。
- 后端 `com.yx.noon.sales`（18 类：SalesFlowService + ReturnDispositionService + 6 input/result record + 4 row/detail + 2 exception + 幂等/规则）。
- HTTP：`/noon/sales/order/{import,deliver,list,get}`、`/noon/sales/return/{import,reverse,dispose,list,get}`、`/noon/sales/result`。
- 错误语义：跨站 403；内部闸门隔离 409（Controller 补 `SalesFlowException`/`ReturnDispositionException` handler，含 reasonCode/reviewNo）；其余 400。

## 3. C-2/C-2b 验收基准（必测项，已通过）

单件成本100、售价150、全额退款、赔偿30：
- **RETURN_LOSS 全额**：B 级退货 4 件 → amount=4×10=40（**不是 40−12=28**），`return_loss_amount` 回写全额。
- **赔偿单次**：`compensation_amount=30` 只登记 `sal_return`，本批**不写任何 COMPENSATION 平台费流水**（M6 确认一次），RETURN_LOSS 不减赔偿。
- **红冲前置**：未红冲先处置 → 409 `REVERSAL_INCOMPLETE`；部分红冲不可处置；qty≠退货单 qty → 400。
- **A 级**：建 RETURN_RESTOCK 新批次（成本缺省取原 SALE 层、多层加权）+ 回可用，不写 RETURN_LOSS、不增 qty_unsellable。
- **不合格库存**：B/C 级 `qty_unsellable += qty`（补齐 M4 四态台账最后写入点）。
- **restockCost 强制 ORIGINAL（Codex M5 复核收紧）**：A 级成本恒取被红冲原 SALE 层加权成本（PRD `return_restock_cost=ORIGINAL`，资产转移不产生损益）；`restockCost<=0` 拒绝，正数偏离原价一律 400 `RESTOCK_COST_OVERRIDE_FORBIDDEN`，仅当传值==原价放行；审计记 `restockCostSource`；重估属业务待决未实现。详见契约 §13.2。

## 4. 测试证据（全部独立复跑，全绿）

| 层级 | 结果 | 证据 |
|---|---|---|
| Maven 全模块 | `yxerp-noon` **223/223**（SalesFlow 10 + Disposition 15 + 既有 198，含 Codex 初审新增护栏 1）、framework 29；BUILD SUCCESS | 命令复跑（2026-11-22 护栏后） |
| 隔离 MySQL 8.4 `MySqlSalesIT` | **5/5**，maven_exit=0（真 CHECK/唯一键/并发同键只落 1 行/真库守恒） | `/tmp/.../noon-m5-sales-cbg6mf2d/` |
| HTTP E2E + 真实浏览器 | **96/96（浏览器 9/9，EXIT=0）**：订单导入/妥投 FIFO/退货/红冲/A·B 处置/C-2b/幂等/授权/409/UI 导入可见；Codex 复核收紧后本轮无浏览器抽测 **84/84 EXIT=0** | `m5-sales-return/e2e-output.txt` |
| M5↔M4 联调 | **49/49，EXIT=0**：M4 入库批次→M5 全链路→守恒（入库100=批次80+妥投20；分级损耗4+上架2+不合格4）、跨站 403、重复事件幂等 | `m5-sales-return/keyflow-output.txt` |
| 前端 build / 生成器 | vite build 通过；`sal_order`/`sal_return` 生成器 importTable→配置→batchGenCode 实调 | `generated/raw/generated.zip` |

E2E 调试中修复三处接入/环境问题（均非后端内核缺陷）：E2E 漏 extract `inv_batch_consumption`（妥投 500）、漏灌 `noon_fifo_audit`/`noon_reversal_audit` 迁移（审计表缺失 500）、浏览器确定按钮文本匹配（"确 定" vs "确定"）。

**Codex M5 两轮质疑（集中处理，详见契约 §13）**：① C-2b 报废口径——对照 PRD C-2/FR-04.4 + 原型 P-01，"报废=全额计损 RETURN_LOSS + 实物进 qty_unsellable 隔离态"是需求既定口径，H2/IT/E2E 已证四态守恒（available4+unsellable1=5）、无双计（L1=−70），**不改代码**；Codex 复核已按该范围接受说明，后续物理报废/出库/残值留待后续，不标全流程验收。② A 级 restockCost——初审加 `<=0` 护栏后 Codex 复核指出"正数仍可任意覆盖"；本轮**收紧为强制 ORIGINAL**：成本恒取原 SALE 层加权、传值偏离即 400 拒绝，重估需业务授权+资产差额处理（待决，未实现），前端同步提示。关键场景抽测：H2 `restockCostDeviatingFromOriginalIsRejected...`（0/负/120 偏离均拒、无副作用）、原价 100 放行、真库 IT 过 CHECK。复跑 H2 223/223、IT 5/5、HTTP E2E 84/84（no-browser）、vite build 全绿。

## 5. 生成器使用（用户要求）

对 `sal_order`/`sal_return` 实际运行若依生成器导出 list/get 前端骨架与 domain 作为参考（逐文件对照 `generated/ADOPTION.md`）；强事务编排/分级处置/事件登记手写（多服务编排与领域逻辑，单表 CRUD 生成器不适用）；菜单与幂等表手写（动作权限细分/特殊 PK）。

## 6. 剩余依赖与业务待决（不自行假定，不标完整验收）

**M6/后续依赖**：
- 赔偿 `compensation_amount` 实际过账（platform_fee.COMPENSATION 减项单次确认）属 M6 账单，本批只登记字段，不双计。
- 妥投/退货 revenue 归集、L1 利润汇总属 M7，本批只登记 refund/return_loss 字段。

**业务待决（契约 §9，需用户决定，未臆造）**：
1. 分级触发方：本批人工显式传 grade；是否平台事件自动触发、谁可改 grade 待定。
2. A 级 restock_cost：PRD `ORIGINAL` 强制——恒取原 SALE 层加权成本，传值仅当等于原价放行、偏离 400 拒绝；质检重估需授权+资产差额处理，待定（契约 §13.2）。
3. A 级是否经 qty_unsellable 待检再放行：本批按 PRD 直接转可用。
4. 退货窗口/申诉时效字段只登记不校验。**可独立部分（待用户确认后接入）**：纯计算函数（起算+配置天数→UTC+8 截止+overdue，天数从 noon_rule_config 解析、禁止写死）、只读暴露 deadline+overdue（NULL 标"未计算"）、raw 文本保持 NULL。**必须待决**：起算时点（妥投 vs 登记，且 sal_order_item 无 delivered_at、M5 未写 delivered_at）、平台原始截止 vs 配置重算优先级、C-03 天数场景（14/15/7/14/Directship 7 天/AE-SA 分站）、高亮阈值（原型"≤3天红"属账单 D-4 不可借用）、配置快照依赖 WP2（biz_type 无 SAL_RETURN）。
5. 同退货改 grade/处置作废未实现（本批 409 拒绝重复损益）。
6. B/C 级 qty_unsellable 隔离后的报废/降级出库流程属后续范围。
7. 真实平台事件适配属 M9：本批事件登记为隔离环境占位，内部事件类型 ORDER/RETURN/SALE_DELIVERY/RETURN_REVERSAL 非 Noon 原生类型，不臆造平台事实。
8. 四位精度舍入差（Codex 复核任务4）：**仅 A 级**（B/C 逐层拆分无除法、无此差）多层 + `unit_cost` 4 位 HALF_UP 下，建批资产与红冲 COGS 可能有**有界舍入差**（紧界 `|差|≤0.00005×qty`，例 620/6→619.9998 差 −0.0002、豆包补例 300.0001/3→差 +0.0001），**不声称恒零**；已用 H2 关键场景钉死口径（16/16），修正代码/文档"零差额"绝对化表述；权威 COGS 以 inv_batch_consumption 实际流水为准、M7 按实际入账金额取数不省略其一，差额归属口径属 M7 待决（契约 §14）。
9. C-03 Directship 7 天场景覆盖 / AE-SA 分站时效取值未定（锁行预留、仅 GLOBAL 初始化）。
10. `noon_m5_sales_request` 幂等表无 TTL/清理策略（运维待决）。

**通用未验证**：真实 Noon 平台、旧库升级、跨设备异常重试。M4 遗留未决（盘点期间出入库控制、盘盈缺成本）不因转 M5 关闭。

## 7. 请 Codex 一次审核（集中，不逐批）

① 三个内部闸门复用 + 编排层 A/B/C 分级处置口径是否成立、C-2b（L1=−70）验算与 qty_unsellable 写入点是否满足 PRD；② D1-D8 裁决（尤其 D3 整单处置/D7 全红冲后处置/D5 原层成本逐行拆）是否稳妥；③ M5↔M4 联调守恒与跨站/重复事件/数量守恒证据是否充分；④ 409/403/400 错误语义与前端防呆（红冲后才放开处置）是否满足；⑤ 剩余依赖（M6 赔偿过账/M7 利润/M9 平台适配）与业务待决记录是否满足"保留接口、不标完整验收、不阻塞"；⑥ 222 单测 + M5 5 IT + 96 E2E + 49 联调证据是否充分、是否可判定 M5 订单退货隔离环境交付通过。
