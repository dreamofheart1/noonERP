# M17 · 期初库存 / 销售退货联动 / 差异对账 交付报告

日期：2026-11-24  
范围：按用户"简化 FBN 对齐 + 期初→销售退货→对账集中实施"的决定落地。  
**关键边界：本次授权开发与隔离验证；实际期初建账须用户查看确认后才执行——本报告未执行任何真实建账，未改 ERP 库存。**

---

## 一、现状调研结论（决定设计）

| 能力 | 现状 |
|---|---|
| 库存四态台账 | `inv_stock_ledger`，入口分散在 Inbound/Asn/Stocktake/Transfer/Fifo/ReturnDisposition，均带 requestId 幂等 + 站点闸门 |
| FIFO 销售扣减 | `FifoConsumptionService.postDelivery(DeliveryInput)`，要求 sal_order_item 父行 + 系统侧 `int_webhook_event` 证据，成熟闸门 |
| 退货恢复可售 | `ReturnDispositionService.dispose(A)` 要求完整红冲链，唯一正道 |
| 期初库存 | **完全空白**，无表无代码 |
| item_nr 快照 | `noon_sales_item` 独立表，与 ERP 正式订单无关联 |

因此本实现不重造库存内核，只补"建议→确认→联动→对账"四层，并严格复用既有闸门。

---

## 二、交付内容

### 1. 期初建议 → 用户确认建账

| 组件 | 说明 |
|---|---|
| `noon_opening_proposal` | 建议表；`generate()` 只读平台快照与 ERP 现状，绝不写库存账 |
| 待核项标注 | 已有 FBN 台账、已关联销售、FBN 在途、未匹配 SKU 只标注，**不自动抵扣/删除** |
| `confirm()` | 必须显式 proposalNo + 操作人 + **真实成本**；`unitCost=null 或 0` 直接拒绝，不造零成本 |
| 幂等 | 按 `(proposal_no, site, partner_sku, warehouse_id, snapshot_at)` 唯一；重复确认只处理仍 PENDING 的行 |
| 建账 | 复用 `inv_batch(source_type=ADJUSTMENT)` + `inv_stock_ledger`，同事务 + `noon_opening_audit` 留痕 |
| 成本未知 | `cost_status=UNKNOWN`，不允许建批；与既有 FIFO/利润限制在页面提示 |

### 2. 销售送达扣减 / 状态倒退 / 退货证据

- `noon_sales_link`：item_nr 快照 → 联动判定表；`sal_order_id/sal_order_item_id` **未关联为 NULL**，不用 0 冒充。
- 判定规则：
  - 已送达但无正式订单行 → `NO_ORDER_LINK`，**禁止臆造订单/扣库存**；
  - 已送达且有正式订单行 → `READY_FOR_FIFO`，仅表示"若存在系统事件证据可走 FIFO"，**本服务不自动扣**；
  - 状态倒退 → `STATE_REGRESSION`，保持已送达事实、不回退库存；
  - 未送达 → `TRACKING`。
- 退货：`noon_return_evidence` 默认 `PENDING/INSUFFICIENT_EVIDENCE`；只有平台确认"重新入仓且可售"并提供证据才标 `VERIFIED`，**缺证据不加库存**；后续恢复仍须走既有红冲 + `ReturnDispositionService(A)`。

### 3. 简单对账

M16 的 compare 已按 FBN 仓口径汇总、不可比较不做差；本轮补 `noon_reconcile_snapshot` 表用于留痕（官方可售/不可售、ERP 可售/不可售、差额、状态、双方时间）。

### 4. 页面与接口

新增「Noon官方数据 → 期初与联动」（menu 3913）三个 Tab：期初建议（生成只读 + 显式确认建账）、销售送达待核、退货证据。
所有写动作带 `sku:save` 权限 + `SiteAuthGuard`；请求统一走 3 秒限速、平台只读。

---

## 三、隔离验证结果

| 项 | 结果 |
|---|---|
| 单测 | 全模块 **255 用例 0 失败**（新增期初 2 项 + 销售联动 3 项） |
| 前端 build | 通过 |
| 期初建议（真库只读） | 生成 18 行；`opening_batches=0`（**未建账**）；"未匹配 SKU"正确标注不可建账 |
| 确认建账 | **未执行**（须用户查看确认）；单测已覆盖建账/幂等/零成本拒绝 |
| 销售待核（真库只读） | 40 条；未送达 TRACKING、已送达无订单行 NO_ORDER_LINK；未扣库存 |
| 退货证据 | 单测覆盖缺证据保持待核、有证据才 VERIFIED |
| 站点/只读 | AE/SA 白名单；平台无写操作 |

过程中修复一个真实 bug：MySQL DATETIME(3) 返回 `LocalDateTime`，期初服务强转 `Timestamp` 失败（已兼容两种类型）。

---

## 四、明确未做 / 局限

1. **未执行真实期初建账**——按用户要求须先查看建议与重叠项、填写真实成本后确认。
2. 未把平台销售明细自动转成 ERP 正式订单；`READY_FOR_FIFO` 只是待核判定，扣减仍需正式订单行 + 系统事件证据。
3. 退货只做到"证据闸门 + 待核"，恢复可售仍走既有 `ReturnDispositionService(A)`，本次未新增自动恢复。
4. 对账快照表已建但写入留痕未在 UI 暴露为独立页面；当前 compare 直接查实时快照。
5. 未覆盖：起点前下单起点后送达、跨期未完订单、退货已含期初等特殊时序的实数据验证（需用户提供确认后的期初才能测）。
6. 非公开接口正式支持/SLA、多实例限速、仓库逐码映射仍未确认。

未连接生产、未部署、未推送。请 Codex 复核；**下一步需要用户查看期初建议并确认成本后，才可执行建账**。
