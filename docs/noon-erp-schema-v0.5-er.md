# Noon ERP 数据库 ER 图 v0.5

对应 DDL：`docs/noon-erp-schema-v0.5.sql`（72 表 + 5 视图在若依共存库中的实测值；本文件只画 Noon 业务域 52 张表，若依框架表另列）。
日期：2026-11-18（UTC+8）　执行者：WorkBuddy　状态：与 v0.5 SQL 逐表核对一致。

## 阅读约定

- **物理外键**：业务表一律逻辑关联（DDL 无 `FOREIGN KEY`）；图中连线表示 `子.字段 → 父.主键` 的逻辑引用，便于单体模块拆分与批量导入。唯一带物理外键的是若依侧 `sys_user_site → sys_user`（由 `yx-erp/sql/noon_wp1_site_auth.sql` 管理，不在本 DDL 重复定义）。
- **基数**：`||--o{` = 一对多；`}|--|{` = 多对多（经中间表）；`o|` = 零或一。
- **时区**：所有业务时刻 `DATETIME(3)` = UTC+8；带 `_raw_text/_raw_tz` 的字段保留平台原始截止瞬间，仅展示层换算。
- **★** 标记为核心/对接阻塞表。

## 1. 主数据 · 权限 · 参数（A 组）

```mermaid
erDiagram
    MD_PARTNER_ACCOUNT ||--o{ MD_STORE : "partner_id"
    MD_STORE ||--o{ MD_SITE : "store_id"
    MD_SITE ||--o{ MD_WAREHOUSE : "site_code"
    MD_SITE ||--o{ MD_PARTNER_SKU : "site_code"
    MD_SKU ||--o{ MD_PARTNER_SKU : "sku_id"
    MD_SKU ||--o{ MD_SKU_SUPPLIER : "sku_id"
    MD_SUPPLIER ||--o{ MD_SKU_SUPPLIER : "supplier_id"

    SYS_USER ||--o{ SYS_USER_SITE : "user_id (若依物理FK)"
    SYS_SITE_AUTH ||--o{ SYS_USER_SITE : "site_code 授权"
    MD_SITE ||--o| SYS_USER_SITE : "site_code 同义"

    NOON_RULE_CONFIG_LOCK ||--o| NOON_RULE_CONFIG : "(config_key,scope) 先锁后写"
    NOON_RULE_CONFIG ||--o| NOON_CONFIG_SNAPSHOT : "version_id 引用"
    NOON_RULE_CONFIG ||--o| NOON_RULE_CONFIG_AUDIT : "version_id 审计"
    NOON_CONFIG_SNAPSHOT ||--o| PUR_ORDER : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| SAL_ORDER : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| SAL_RETURN : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| FIN_STATEMENT : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| FIN_PROFIT_SNAPSHOT : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| RP_REPLENISH_SUGGESTION : "snapshot_batch_no"
    NOON_CONFIG_SNAPSHOT ||--o| AI_SUGGESTION : "snapshot_batch_no"

    MD_SKU {
        bigint id PK
        varchar sku_code UK
        varchar category_code
        json spec_json
    }
    MD_SITE {
        bigint id PK
        varchar site_code UK "AE/SA"
        char currency "AED/SAR"
    }
    NOON_RULE_CONFIG {
        bigint id PK "versionId"
        varchar config_key
        varchar scope "GLOBAL/AE/SA"
        varchar config_value "不可变快照"
        datetime effective_from
        datetime effective_to "NULL=无穷"
        tinyint status "派生/展示,非历史判据"
        datetime revoked_at "撤销生效UTC+8"
        bigint revoked_by
    }
    NOON_RULE_CONFIG_LOCK {
        varchar config_key PK
        varchar scope PK "GLOBAL/AE/SA 预置"
    }
    NOON_RULE_CONFIG_AUDIT {
        bigint id PK
        varchar action "CREATE/REVOKE"
        bigint version_id
        json before_json
        json after_json
        bigint actor_id "若依user_id"
        datetime act_at
    }
    NOON_CONFIG_SNAPSHOT {
        bigint id PK
        varchar snapshot_batch_no
        varchar biz_type
        bigint version_id
        varchar requested_site
        varchar resolved_scope "GLOBAL或真实站点"
        varchar value_snapshot "不可变"
        datetime business_time
        datetime resolved_at
    }
```

**参数契约要点**（与框架侧 `EffectiveConfigResolver`/`VersionedConfigReader`/`ConfigIntervalPolicy` 一致）：
- 有效区间半开 `[effective_from, effective_to)`，`effective_to=NULL` 为无穷；`end = min(effective_to, revoked_at)`。
- 撤销：`业务时刻 < revoked_at` 旧版本可用，`= revoked_at` 起排除；历史解析**不得**用当前 `status` 或 `revoked_at IS NULL` 无条件过滤。
- 站点优先：同 key 下 `scope=真实站点` 命中优先于 `GLOBAL`；同 scope 多于一个有效版本 → 失败关闭。
- 写入：按 `(config_key, scope)` 先锁 `noon_rule_config_lock` 行，当前读候选 → 查重叠 → 追加新版本 → 写审计，同一事务；首行竞争需隔离 MySQL 并发测试（待定）。
- 重算：只读 `noon_config_snapshot.value_snapshot + version_id`，不查当前配置，仍 `requireSite(requested_site)`。

## 2. 采购 · FIFO 库存（B 组）

```mermaid
erDiagram
    MD_SUPPLIER ||--o{ PUR_ORDER : "supplier_id"
    MD_SITE ||--o{ PUR_ORDER : "site_code"
    PUR_ORDER ||--o{ PUR_ORDER_ITEM : "pur_order_id"
    MD_SKU ||--o{ PUR_ORDER_ITEM : "sku_id"
    PUR_ORDER_ITEM ||--o{ INV_BATCH : "pur_order_item_id"
    MD_WAREHOUSE ||--o{ INV_BATCH : "warehouse_id"
    INV_BATCH ||--o| INV_BATCH : "parent_batch_id 拆批血缘"
    INV_BATCH ||--o{ INV_BATCH_CONSUMPTION : "batch_id 扣减"
    INV_BATCH ||--o{ INV_INBOUND_ITEM : "batch_no 到货建批"
    PUR_ORDER ||--o{ INV_INBOUND : "pur_order_id"
    INV_INBOUND ||--o{ INV_INBOUND_ITEM : "inbound_id"
    MD_SKU ||--o{ INV_STOCK_LEDGER : "sku_id+warehouse_id UK"
    MD_WAREHOUSE ||--o{ INV_STOCK_LEDGER : "warehouse_id"
    MD_SKU ||--o{ INV_STOCK_MOVE : "sku_id"
    INV_TRANSFER ||--o{ INV_TRANSFER_ITEM : "transfer_id"
    INV_STOCKTAKE ||--o{ INV_STOCKTAKE_ITEM : "stocktake_id"
    SAL_ORDER_ITEM ||--o{ INV_BATCH_CONSUMPTION : "sal_order_item_id"
    SAL_RETURN ||--o| INV_BATCH_CONSUMPTION : "sal_return_id 红冲"

    INV_BATCH {
        bigint id PK
        varchar batch_no UK
        datetime received_at "FIFO 排序键"
        int qty_in
        int qty_remaining "0<=x<=qty_in"
        decimal unit_cost
        varchar source_type
        bigint parent_batch_id
        varchar status "OPEN/IN_TRANSIT/CLOSED"
    }
    INV_BATCH_CONSUMPTION {
        bigint id PK
        varchar biz_event_key UK "★幂等键=过账事件ID(与batch_id复合)"
        varchar source_event_id "可空,追溯索引(事件级去重在webhook层)"
        bigint batch_id "复合唯一键第二列"
        bigint sal_order_item_id
        int qty
        decimal amount "正值"
        tinyint sign "+1/-1"
        bigint reversal_of_id "被红冲原行"
    }
    INV_STOCK_LEDGER {
        int qty_available
        int qty_in_transit
        int qty_reserved "平台预留"
        int qty_unsellable "待检/损坏"
    }
```

**守恒/幂等要点**：
- 部分到货只建已到货数量批次；未收部分挂采购行 `qty_ordered - qty_received`，不建在途批。
- 在途子批 `status=IN_TRANSIT` 且 `qty_remaining=0`，FIFO 只对已到货 `OPEN` 批扣减，避免双计。
- 退货先红冲原 `SALE` 消耗（`sign=-1, reversal_of_id`），再按 A/B/C 分级；赔偿只在 `COMPENSATION` 计一次。
- `uk_consume_biz_event(biz_event_key, batch_id)`：幂等键绑定**每次实际过账事件**（非订单行/原消耗 ID）。同一事件跨批次多条、同事件重传同批次被拦、不同部分妥投/退货事件（不同 key）可再次消费同批次——三条同时成立。事件 ID 来源优先平台履约号，缺失时应用按确定规则生成，无法识别进 NEEDS_REVIEW；累计退货≤原消耗由应用层行锁事务校验，DDL 只管单次幂等。
- 订单行 `qty_delivered + qty_cancelled <= qty` 且 `qty_returned <= qty_delivered`：退货是妥投子集（妥投10退货2合法）。
- ★ 三轮修正（跨站同订单号）：主表 `sal_order` 以 `(order_no, site_code)` 唯一，AE/SA 允许同号订单。`uk_platform_item(sal_order_id, platform_item_no)` 锚点由平台 order_no 改为内部可信订单 ID：同一订单内重复行号拦截、跨站同号订单的各自行号可存在。`sal_return` 新增可空 `site_code`，`uk_return_source(site_code, order_no, source_return_no)`：同站同号同源拦截、跨站同号同源可区分。平台行号/来源号的真实唯一作用域未经样本证实，不虚构全局唯一；站点归属一致性由应用层 `requireSite` + 父订单校验，累计退货守恒由行锁事务校验，DDL 只保单次幂等。
- `noon_config_snapshot`：`trg_snapshot_immutable` 拒绝原地 UPDATE、`trg_snapshot_no_delete` 拒绝物理 DELETE（归档走受控迁移）；`chk_snap_owner` 禁止跨站解析。
- ★ 三轮修正（AI 终态）：`trg_ai_suggestion_insert_gate` 强制新建 PENDING/MANUAL；`trg_ai_suggestion_update_gate` 改为显式状态转移白名单——仅允许 `PENDING→APPROVED/REJECTED`、`APPROVED→CONVERTED` 及同态补充审核字段；`REJECTED`/`CONVERTED` 为终态不得改判，进入审批态必须有 reviewed_by/reviewed_at，CONVERTED 须有 auto_rule_ref 或 review_note。`review_mode=AUTO` 必须提供 auto_rule_ref；规则是否真实命中 `ai_auto_review_rules` 由服务端校验（触发器无法查配置表）。触发器为数据库不变量，完整审批闭环仍需服务端鉴权+审计+行锁。

## 3. 头程 · 订单 · 退货 · 结算 · 利润（C/D 组）

```mermaid
erDiagram
    MD_SITE ||--o{ LOG_ASN : "site_code"
    LOG_ASN ||--o{ LOG_ASN_ITEM : "asn_id"
    LOG_ASN ||--o{ LOG_SHIPMENT_COST : "asn_id L2登记"
    MD_STORE ||--o{ SAL_ORDER : "store_id"
    MD_SITE ||--o{ SAL_ORDER : "site_code"
    MD_PARTNER_SKU ||--o| SAL_ORDER : "partner_sku 回查"
    SAL_ORDER ||--o{ SAL_ORDER_ITEM : "sal_order_id"
    SAL_ORDER_ITEM ||--o{ SAL_RETURN : "sal_order_item_id"
    SAL_ORDER ||--o| FIN_STATEMENT_LINE : "order_no"
    FIN_STATEMENT ||--o{ FIN_STATEMENT_LINE : "statement_id"
    MD_SITE ||--o{ FIN_STATEMENT : "site_code"
    FIN_STATEMENT ||--o| FIN_PAYOUT : "statement_no"
    MD_SITE ||--o{ FIN_PAYOUT : "site_code"
    MD_SITE ||--o{ FIN_EXPENSE_POOL : "site_code L2"
    LOG_ASN ||--o| FIN_EXPENSE_POOL : "asn_id"
    FIN_FEE_DICT ||--o| FIN_PROFIT_SNAPSHOT : "费率输入"
    FX_RATE ||--o| FIN_PROFIT_SNAPSHOT : "汇率输入"
    FIN_STATEMENT ||--o| FIN_PROFIT_SNAPSHOT : "账单输入"
    INV_BATCH_CONSUMPTION ||--o| FIN_PROFIT_SNAPSHOT : "COGS汇总"

    SAL_ORDER {
        bigint id PK
        varchar order_no UK "order_no+site_code"
        decimal gross_amount "原币"
        char currency
        decimal fx_rate "默认NULL 缺失报错"
        varchar import_fingerprint "SHA256 ACK"
        int state_version
        datetime delivered_at "UTC+8"
    }
    SAL_RETURN {
        bigint id PK
        varchar biz_return_key UK "★P2-2"
        varchar site_code "★三轮:可空,跨站同号隔离"
        varchar source_return_no UK "site+order+source"
        varchar grade "A/B/C"
        decimal return_loss_amount "全额计损"
        datetime appeal_deadline "UTC+8"
        varchar appeal_deadline_raw_tz "原始瞬间"
    }
    FIN_STATEMENT {
        varchar statement_no UK
        datetime dispute_deadline "UTC+8 generated+15d"
        varchar dispute_deadline_raw_tz "原始瞬间"
    }
    FIN_PROFIT_SNAPSHOT {
        varchar period
        varchar scope_type "SITE汇总/SKU明细"
        bigint sku_id "SITE=0"
        decimal contribution_margin "L1"
        decimal net_profit "L1-allocated_expense"
        varchar allocation_policy "维度A NONE/PARTIAL/FULL"
        tinyint sku_allocation_enabled "维度B 默认0"
        varchar calc_batch_no "重算批次"
    }
```

**利润两维度**：维度A `allocation_policy` 只管 SITE 行 L2 合并范围；维度B `sku_allocation_enabled` 才决定 SKU 行是否分摊 SITE 的 L2（默认关，SKU 净利=自身 L1）。三档快照并存、多次重算各成批次。`v_ai_profit_site`/`v_ai_profit_sku` 物理隔离，禁止跨 `scope_type` 相加。

## 4. 补货 · 集成 · AI（E/F 组）

```mermaid
erDiagram
    MD_SKU ||--o{ RP_REPLENISH_SUGGESTION : "sku_id"
    MD_SITE ||--o{ RP_REPLENISH_SUGGESTION : "site_code"
    MD_WAREHOUSE ||--o{ RP_REPLENISH_SUGGESTION : "warehouse_id 目标仓"
    RP_REPLENISH_SUGGESTION ||--o| PUR_ORDER : "CONVERTED 转采购"
    RP_REPLENISH_SUGGESTION ||--o| INV_TRANSFER : "CONVERTED 转调拨"
    RP_REPLENISH_SUGGESTION ||--o| LOG_ASN : "CONVERTED 转ASN"

    MD_PARTNER_ACCOUNT ||--o{ INT_SYNC_TASK : "同步任务"
    MD_PARTNER_ACCOUNT ||--o{ INT_API_LOG : "全量API留痕 X-Request-Id"
    INT_WEBHOOK_EVENT ||--o| SAL_ORDER : "event_id 幂等入口"
    INT_EXPORT_CATEGORY ||--o{ INT_EXPORT_JOB : "export_category_code"
    INT_EXPORT_JOB ||--o| SAL_ORDER : "导入订单"

    SYS_USER ||--o| NOON_AUDIT_LOG : "user_id"
    AI_SUGGESTION ||--o| PUR_ORDER : "APPROVED 才转正式"
    AI_SUGGESTION ||--o| SAL_ORDER : "APPROVED 才转正式"
    NOON_RULE_CONFIG ||--o| AI_SUGGESTION : "ai_auto_review_enabled/rules"

    INT_EXPORT_CATEGORY {
        varchar export_category_code UK
        datetime discovered_at "发现层"
        datetime create_tested_at "权限层"
        datetime headers_tested_at "列头层"
        tinyint is_usable "生成列 两层均验证=1"
    }
    AI_SUGGESTION {
        varchar suggestion_no UK
        varchar scene
        json payload_json "暂存不进正式表"
        varchar status "PENDING/APPROVED/REJECTED/CONVERTED"
        varchar review_mode "MANUAL/AUTO 默认MANUAL"
        bigint reviewed_by
    }
```

**AI 写入治理**（用户决议 A）：AI 正常读 `v_ai_*` 只读视图；写入只落 `ai_suggestion` 暂存层，不进任何正式业务表；`PENDING` 不参与库存/利润/账单；审批（`MANUAL` 或命中 `ai_auto_review_rules` 的 `AUTO`）通过后才转正式单据。自动审核开关默认关（人工）。

## 5. 与若依的边界（P1-3）

| 归属 | 表 | 说明 |
|---|---|---|
| 若依框架 | `sys_user` / `sys_role` / `sys_user_role` / `sys_config` / `sys_dept` / `sys_menu` / `sys_oper_log` | v0.5 **不建不覆盖**；鉴权 `userId=1` 超管全站点，普通用户读 `sys_user_site` |
| 若依侧增量 | `sys_user_site` / `sys_user_site_audit` | WP1 已实现（含物理 FK），v0.5 只引用 `site_code` 同义 |
| Noon 业务 | `noon_rule_config*` / `noon_config_snapshot` / `noon_audit_log` / 全部 `md_/pur_/inv_/log_/sal_/fin_/fx_/rp_/int_/ai_` | 命名空间与若依隔离，`sys_audit_log` 更名 `noon_audit_log` |

## 6. 完整表清单映射（45 表，与 DDL 一一对应，图文一致性核对）

| 组 | 表（v0.5 DDL 中 45 张） |
|---|---|
| A 主数据 | `md_partner_account`、`md_store`、`md_site`、`md_warehouse`、`md_sku`、`md_partner_sku`、`md_supplier`、`md_sku_supplier` |
| A2 参数/快照 ★ | `noon_rule_config`、`noon_rule_config_lock`、`noon_rule_config_audit`、`noon_config_snapshot` |
| B 采购/库存 | `pur_order`、`pur_order_item`、`inv_batch`、`inv_batch_consumption`、`inv_stock_ledger`、`inv_stock_move`、`inv_inbound`、`inv_inbound_item`、`inv_transfer`、`inv_transfer_item`、`inv_stocktake`、`inv_stocktake_item` |
| C 头程 | `log_asn`、`log_asn_item`、`log_shipment_cost` |
| D 订单/财务 | `sal_order`、`sal_order_item`、`sal_return`、`fin_statement`、`fin_statement_line`、`fin_fee_dict`、`fin_payout`、`fin_expense_pool`、`fin_profit_snapshot`、`fx_rate` |
| E 补货 | `rp_replenish_suggestion` |
| F 集成/日志/AI | `int_sync_task`、`int_webhook_event`、`int_api_log`、`int_export_category`、`int_export_job`、`noon_audit_log`、`ai_suggestion` |

视图 5 个：`v_ai_sku_overview`、`v_ai_profit_site`、`v_ai_profit_sku`、`v_ai_statement_open`、`v_ai_replenish`（原 `v_ai_profit` 已拆分为 site/sku 两个，禁止跨 `scope_type` 相加）。

不在本 DDL、由若依管理的表：`sys_user`、`sys_role`、`sys_user_role`、`sys_config`、`sys_dept`、`sys_menu`、`sys_oper_log`、`sys_user_site`、`sys_user_site_audit`（后两张见 WP1）。

## 7. 待定（不虚构完成）

- 正式 Repository / 管理 API / 参数并发写测试（首次竞争、锁等待、回滚释放）。
- 业务写入快照的事务时机（`config_snapshot_batch_no` 字段已留，落库逻辑由 WP2 接入）。
- 追溯性更正 / 双时态；生产迁移脚本（旧 `sys_config`→`noon_rule_config`）；真实平台数据验证。
