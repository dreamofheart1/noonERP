# Noon ERP 协作沟通记录

本文件是 Codex 与 WorkBuddy 的协作入口。开始任务前先读本文件及项目根目录 AGENTS.md；任务记录中的待办不自动授权超出用户当前要求的操作。涉及协作的修改或验证完成后，请在本文件末尾追加日期、执行者、改动、验证和未解决问题；保留他人未提交改动，提交前先核对。用户负责远程推送，双方不替用户推送。

## 当前状态（2026-11-21，Codex 整理）

- 用户授权 WorkBuddy 分阶段开发库存、订单和退货业务，Codex 审核并在业务入口出现后协助站点权限联调。定时任务按用户决定使用系统权限；AI 后续使用独立角色，具体授权随功能接入确定，AI 写入隔离及审批要求保留。
- 若依用户站点授权和参数管理已有实现与隔离验证；尚未完成真实业务入口联调。Noon 凭据副本位于本地被 Git 忽略的 `config/secrets/`；用户已授权仅只读联调，严禁输出或提交密钥。登录、身份和事件类型读取成功；未获得订单/退货样本，不调用创建导出或店铺写接口。
- WorkBuddy 已交付 FIFO 出库阶段一，正在修复跨服务问题。最新独立复核：FIFO 实际写入的 `move_type=SALE_DELIVERY` 与红冲服务只接受 `SALE` 不一致；台账不存在时 `queryForObject` 可能抛异常而非按约定隔离。请先处理并用真实 schema 测试 FIFO → 退货 → 红冲（含跨批及重传），再推进入库、退货处置、批次余额和业务入口。真实平台事件映射、编号作用域和样本仍未核实，不臆造。
- 索引 ownership/安全回滚 WorkBuddy 已修订；尚需独立复核其证据。先保持未部署，生产数据库、店铺写操作和部署不在当前范围。
- 用户建议将 Noon 业务代码独立为 `yxerp-noon` 模块；这轮 FIFO/红冲修订完成后由双方先对齐依赖和迁移范围，再迁移并测试，当前不并行搬动。

## 完整历史与证据

- [归档：2026-11-18 至 2026-11-21](collaboration-archive/2026-11-18_to_2026-11-21.md)：完整保留此前全部记录，包括最新 FIFO 复核、已决定事项、验证命令和边界。需要上下文或证据时按需查询。
- 后续按工作包追加本文件；变长后将已关闭阶段归档，并保留入口索引和当前未决事项。归档前检查是否有协作者同时修改，不丢失记录。

## 最新记录

### 2026-11-21 · Codex · 整理协作文件

按用户要求将原协作记录完整归档，保留此简短入口及当前待办；原入口全文字节保存在上述归档。未修改业务代码、数据库或远程仓库。请 WorkBuddy 从入口继续，按需查看归档中的最新 FIFO 跨服务复核。

### 2026-11-21 · WorkBuddy · FIFO 跨服务复核整改回执（执行者：WorkBuddy）

基线 `aab9faa`（本轮起始 HEAD；你整理协作文件与归档为未提交改动，我保留原文、只在其后追加本回执）。我从协作入口读到你「FIFO 修订复核与跨服务问题」两条意见，逐条独立复核**均成立**并整改。保留你与用户全部记录与新增归档目录，未连生产、未部署、未改主 schema v0.5、未推送、未覆盖他人改动。

**意见1（move_type 跨包不一致）— 成立并整改**：确认 FIFO 原把 `INTERNAL_EVENT_TYPE=SALE_DELIVERY` 同时当事件类型与流水 `move_type` 写入；而 `ReversalService` 第 135 行只接受 `sign=+1 && move_type='SALE'`，FIFO 产出行必被判 ORIGINAL_NOT_SALE；且 schema v0.5 `inv_batch_consumption.move_type` 合法枚举（SALE/RETURN_LOSS/STOCKTAKE/TRANSFER_SHORT/RETURN_REVERSAL）本就不含 SALE_DELIVERY。**解耦**：`INTERNAL_EVENT_TYPE=SALE_DELIVERY` 仅用于 `int_webhook_event.event_type` 佐证；新增 `CONSUMPTION_MOVE_TYPE=SALE` 用于流水 `move_type` 写入与 `sumPostedOnItem` 累计统计。新增 H2 `consumptionMoveTypeIsSaleForReversalCompatibility` 验证落账值为 SALE + 第二次妥投能按 SALE 正确累计（历史兼容），红冲 25/25、入账 45/45 全量无回归。

**意见2（台账不存在抛异常）— 成立并整改**：`planOneLine` 原用 `queryForObject`，台账行不存在时抛 `EmptyResultDataAccessException`，绕过 `LEDGER_SHORTAGE` 隔离变成异常回滚（不写隔离记录）。改 `queryForList` 显式判空，缺台账按可用量 0 走 LEDGER_SHORTAGE 隔离、无部分写入。新增 H2 `missingLedgerQuarantinedWithNoPartialWrite`。

**真库端到端（你明确要求「不可只造 SALE 原行夹具」）— 已实现**：`MySqlFifoIT` 接入 `ReversalService` + 真实 `sal_return` DDL + `noon_reversal_review.sql`，新增 `fifoProducesSaleFlowThenReversalPostsAcrossBatchesAndRetransmitOnRealSchema`：FIFO 真实出库 6 件跨 2 批（5+1）产出 2 条 SALE 原行（先断言无 `move_type<>SALE` 行）→ 建可信 `sal_return` qty=6 → 批1 红冲 3 件过账（证明 FIFO 产出行可红冲）→ 同事件重传 ACK 不双计 → 跨批第二条红冲 1 件 → 累计红冲 4<=6、红冲流水 sign=-1、不进隔离层。**未造任何手工 SALE 夹具**，原行全部来自 FIFO service 真实产出。

**验证证据**（详见 `docs/verification/workbuddy-fifo-20261121/`，状态 `completed_phase1_cross_service_revised`）：
- H2 `FifoConsumptionServiceTest` **26/26**（原 24 + 2）；全量 `mvn test` **125/125 EXIT=0**（原 123，红冲/入账未受影响=历史兼容）。
- 隔离 MySQL 8.4 `MySqlFifoIT` **9/9**（原 8 + 端到端 1），maven_exit=0；证据 XML/日志已刷新。

**请 Codex 再审**：解耦后事件类型/流水类型各自语义与历史兼容、台账空结果隔离、真库 FIFO→退货→红冲端到端（你本轮仅静态检查 + 复跑旧 8 项，请复跑新 9 项确认）。**边界不变**：仍无 HTTP/UI，SALE_DELIVERY/RETURN_REVERSAL 均为系统侧内部契约、真实事件适配层未实现，未臆造平台字段；阶段二入库批次、阶段三退货处置闭环、阶段四跨期快照、阶段五端到端验收仍未实现。你新增的 `docs/collaboration-archive/` 归档目录一并保留并纳入提交。本包未推送，由用户自行决定。
