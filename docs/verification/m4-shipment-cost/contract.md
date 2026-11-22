# M4 库存与 FIFO · 第三轮第二批「头程费用登记」契约 v1

日期：2026-11-22。基线：Codex「M4 盘点 v1.2 复核」通过并授权继续 M4；用户明确先完成 M4 再做 M5。本批交付**头程/关税/包材费用登记**闭环（`log_shipment_cost`），纯登记不动库存、不摊 FIFO。

## §0 范围与边界

- 本批：`log_shipment_cost` 的**登记（create）→ 列表/详情 → 幂等结果查询**闭环；费用归集到期间（period）、可按 ASN 关联（asn_id 可空）。
- **不动库存/批次/台账/COGS**：`allocation_flag` 默认 `NONE`（不摊进批次成本，schema 注释 Q8 默认口径）；`PARTIAL/FULL` 仅作标记，**本批不做实际分摊**，分摊算法留 M6 费用归集时另立契约。
- 不在本批：ASN 收货闭环、不合格库存隔离（依赖 M5 退货分级）、头程费用分摊到批次成本、真实 Noon/FBN API 对接。
- 沿用：站点授权 `SiteAuthGuard.requireSite`、动作级幂等三态（PENDING/DONE/result_json + SHA-256 指纹）、同事务审计、分页真实 total、金额 4 位 HALF_UP、业务时刻 UTC+8、若依生成器实调。

## §1 数据模型（复用 schema v0.5 `log_shipment_cost`，不新增业务表）

字段沿用 DDL：`id / cost_no / asn_id / site_code / period / fee_type / amount / currency / fx_rate / allocation_flag / remark / created_at`；唯一键 `uk_cost_no(cost_no)`、`idx_asn`、`idx_period`、CHECK `allocation_flag IN ('NONE','PARTIAL','FULL')`。新增**幂等表** `noon_m4_shipment_cost_request`（见 §4），不新增费用主表字段。

## §2 服务接口（com.yx.noon.shipment.ShipmentCostService）

```
ShipmentCostResult create(ShipmentCostInput in);
ShipmentCostResult resultOf(String requestId);
PageResult<ShipmentCostRow> list(String siteCode, int offset, int limit, CostQuery q);
ShipmentCostDetail get(Long id);
```

- `ShipmentCostInput(Long asnId, String siteCode, String period, String feeType, BigDecimal amount, String currency, BigDecimal fxRate, String allocationFlag, String remark, String requestId)`。
- `ShipmentCostResult(long costId, String costNo, boolean replayed)`；`CostQuery` 支持 `feeType` / `asnId` / `period` 可选过滤。
- `ShipmentCostRow`/`ShipmentCostDetail`：详情含全部字段；列表含摘要字段 + 真实 total。
- **record 类型必须 public**（M2 已踩坑）。

## §3 授权（授权先于判重）

- `create`：先 `siteCode` 非空校验 → `guard.requireSite(siteCode)`（越权恒 403，不泄漏业务状态）；带 `asnId` 时校验 ASN 存在且 `log_asn.site_code == siteCode`（跨站 400），不存在 400。
- `resultOf`：按幂等行 `site_code` 取真实站点 → `requireSite` → 才返回 DONE 结果；无权 403，非法/PENDING 返回 null，只读不写。
- `list`：指定 `siteCode` 先 `requireSite` 再单站过滤；未指定返回全部授权站点；total 为同条件真实总数。
- `get`：按费用行 `site_code` 走 `requireSite`（授权后才返回详情）。

## §4 幂等（动作级 + 内容指纹）

- 独立幂等表 `noon_m4_shipment_cost_request(request_id PK, cost_id, site_code, operator_id, content_fingerprint CHAR(64), status PENDING/DONE, result_json JSON, created_at)`。
- 指纹 = SHA-256（`CREATE` + 规范化字段：asnId/siteCode/period/feeType/amount(4位)/currency/fxRate/allocationFlag/remark）。
- 顺序：入参校验 → operatorId → `requireSite` → 指纹 → DONE 重放 → 状态/内容校验 → `claimRequest`(PENDING) → 写费用行 → markDone(DONE+result_json)；同键内容冲突 400，PENDING 重试 400「处理中」，异常整单回滚（PENDING 一并消失可安全重试）。
- `cost_no = "SC:<SITE>:" + UUID8`，站点来自 `siteCode`；与 `uk_cost_no` 天然幂等。

## §5 校验规则

- `fee_type` ∈ `FREIGHT/DUTY/CUSTOMS/PACKAGING/LABEL`（与 DDL 注释一致），否则 400。
- `amount > 0`，4 位 HALF_UP。
- `currency` 3 位大写字母；`fx_rate`：币种 CNY 必须显式 `fxRate=1`；外币 `fxRate > 0`；缺失/非法 400（沿用 `PurchasingRules.fxRate` 口径，不兜底 1）。
- `period` 格式 `YYYY-MM`（UTC+8 业务月），非法 400；空则取业务月（裁决点待豆包报告）。
- `allocation_flag` 空默认 `NONE`；给定值须 ∈ `NONE/PARTIAL/FULL`；`PARTIAL/FULL` 仅标记不实际分摊（§0）。
- `asnId` 空合法（费用不一定挂 ASN）；非空须存在且同站点（§3）。
- `remark` ≤ 512。
- **不接受客户端传 cost_no/id/status/created_at**（服务端生成）。

## §6 审计与只读

- 同事务写审计 `CREATE`（沿用现有 `*Audit` 模式或本模块自建 `ShipmentCostAudit`，豆包报告说明选择）。
- `resultOf` 的 `replayed` 恒 true；`create` 首次 `replayed=false`，重放 `replayed=true`。
- 列表/详情按站点授权过滤，不写任何表。

## §7 生成器采用

按既定口径实际运行若依生成器：对 `log_shipment_cost` 实际调用 `/tool/gen/importTable` + `batchGenCode`，采用其 Vue/api 列表骨架，手写业务弹窗（费用登记）；生成产物与采用对照写入 `generated/ADOPTION.md`，说明生成范围/沿用/手写原因。

## §8 owner 分工

- 豆包：`yxerp-noon/.../shipment/**` 服务内核 + H2 `ShipmentCostServiceTest` + `MySqlShipmentCostIT` + `docs/verification/m4-shipment-cost/doubao-report.md`。
- WorkBuddy：契约、迁移 DDL（幂等表）+ 菜单 3500 段、Controller、前端 api + 页（生成器骨架）、E2E（生成器实调 + create/list/get/result + 幂等/越权/跨站/校验 + 浏览器 UI）、runtime IT 脚本、全量验证、协作文档。

## §9 待确认业务规则（不自行假定、不宣称已认可）

- `allocation_flag=PARTIAL/FULL` 的实际分摊口径（按数量/金额/重量、何时摊、摊到哪个批次成本）——本批只标记不执行，留 M6/业务确认。
- 费用是否允许修改/作废、跨期调整如何重算快照——本批不支持修改/删除（登记不可变），待业务确认。
- FX 缺失时的处理已明确为拒绝（P1-1 口径），不属待确认。
- 真实 Noon/FBN 费用字段、API 回写能力未联调。

## §10 遗留项接口预留（本批不实现，按用户/Codex 边界记录）

- **ASN 收货闭环**（下一批）：`log_asn`/`log_asn_item` DDL 齐备但零 Java 实现；短少拆批 `ASN_SHORT` 血缘已在 schema 定义，待实现 create→seal→receive + 短少不虚增库存。
- **不合格库存隔离**（依赖 M5 退货 A/B/C 分级）：`qty_unsellable` 目前仅建台账初始化 0、无写入点。真实来源是 M5 退货分级（A 级回售 / B/C 级不合格），**本批不臆造业务规则**；先预留接口 `quarantine(skuId, warehouseId, qty, sourceReturnId, grade)` 签名与验收要求，待 M5 退货落地后联调验证成本口径/能否回售；不得标成完整验收。
- **盘点期间出入库控制**（Codex 复核要求）：v1.2 快照闸门在 apply 时检测账面变动并拒绝，但「实盘与建单账面须同一时点」的实际控制/记录（冻结出入库 or 记录期间变动并补偿）需业务确认，另立契约；不声称已完全覆盖实物盘点时序。
