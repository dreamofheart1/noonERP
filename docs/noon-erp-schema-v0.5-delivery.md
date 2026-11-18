# Noon ERP schema v0.5 交付说明

执行者：WorkBuddy　｜　日期：2026-11-18（UTC+8）　｜　基线：v0.4（45 表 + 4 视图，保留未覆盖）

## 1. 交付文件

| 文件 | 内容 |
|---|---|
| `docs/noon-erp-schema-v0.5.sql` | 完整 DDL：45 表 + 5 视图 + 初始化（若依共存库实测 72 表 = 若依 23 + WP1 2 + v0.5 45） |
| `docs/noon-erp-schema-v0.5-er.md` | ER 图（Mermaid，5 组关系）+ 45 表/5 视图完整映射 + 待定项 |
| `scripts/verify-schema-v05.py` | 可复现隔离 MySQL 验证脚本（一次性实例，不碰生产） |
| `docs/verification/schema-v05/verify-schema-v05-output.txt` | 实测报告：22/22 PASS |

## 2. 关键变更（对照审查报告 + 双方决议）

### 2.1 若依冲突（P1-3，用户决议）
- v0.5 **不再建** `sys_user`/`sys_role`/`sys_user_role`/`sys_config`，用户/角色/框架参数 100% 复用若依。
- `sys_config` → `noon_rule_config`（版本化业务规则，命名空间隔离）；`sys_audit_log` → `noon_audit_log`。
- 站点授权 `sys_user_site`/`sys_user_site_audit` 由 WP1 管理，v0.5 只引用 `site_code` 同义；超管 `userId=1` 全站点、空授权默认拒绝由框架侧实现。
- 实测确认：若依基础 → WP1 → v0.5 顺序执行无表名冲突、无覆盖。

### 2.2 版本化参数契约（对接阻塞项，与 Codex 框架侧对齐）
- `noon_rule_config`：append-only，`scope=GLOBAL|AE|SA`，`revoked_at`（撤销生效 UTC+8）、`revoked_by`、`status`（仅派生展示）。历史解析按业务时刻判 `[effective_from, effective_to)` 且 `业务时刻 < revoked_at`，**不**用当前 `status`/`revoked_at IS NULL` 无条件过滤。
- `noon_rule_config_lock`：`(config_key, scope)` 持久锁行，每 key 预置 GLOBAL/AE/SA 三行（15 键 × 3 = 45 行），解决首次写入无版本行时锁空集问题。
- `noon_rule_config_audit`：写/撤销审计，与版本行同事务，`actor_id` 用若依 user_id。
- `noon_config_snapshot`：可信快照（`version_id`、`requested_site`、`resolved_scope`、`value_snapshot`、`business_time`、`resolved_at`、`snapshot_source`），业务同事务落库，重算只读快照不查当前配置。`config_snapshot_batch_no` 字段已加到 pur_order / sal_order / sal_return / fin_statement / fin_profit_snapshot / rp_replenish_suggestion / ai_suggestion（7 张）。
- 初始化 15 个业务参数（含 `sku_allocation_enabled=0`、`profit_allocation_basis=REVENUE_SHARE`、`order_idempotent_mode=FINGERPRINT_ACK`、AI 自动审核开关默认 `false`、规则 `[]`）。

### 2.3 审查项修复
| 编号 | 处理 |
|---|---|
| P0-1 | `inv_batch_consumption` 加非空 `biz_event_key` + `uk_consume_biz_event(biz_event_key, batch_id)` 复合唯一键：同事件同批次只过账一次（防重复妥投双计 COGS），同事件跨批次允许（FIFO 正常形态）；`source_event_id` 降为追溯索引，事件级去重在 webhook/sal_order 指纹层 |
| P1-1 | 外币表 `fx_rate` 去掉 `DEFAULT 1` 改 `NULL`（sal_order / fin_payout / log_shipment_cost / fin_expense_pool / pur_order_item / inv_batch），`fx_rate` 表 `rate>0` CHECK；归一缺失报错 |
| P1-2 | `v_ai_profit` 拆为 `v_ai_profit_site` / `v_ai_profit_sku`，补 `sku_allocation_enabled`，禁止跨 `scope_type` 相加 |
| P1-4 | 3 处初始化改 MySQL 8.0.20+ 行别名 `AS new ON DUPLICATE KEY UPDATE`，实测 MySQL 8.4.7 通过 |
| P2-1 | 加 20+ CHECK：数量非负、`sign∈{-1,1}`、枚举、区间 `effective_to>effective_from`、scope、状态等 |
| P2-2 | `sal_return` 加非空 `biz_return_key` + 唯一键，`source_return_no` 可空 |
| P2-3 | PRD C-5 案例数字属文档层，本次不动 PRD（已记协作，另开工包） |
| P2-4 | 文档明确所有 `amount` 为原币、归一在查询层按 `fx_rate` 做（DDL 头部约定 + 字段注释） |
| P2-5 | `int_export_category.is_usable` 改 GENERATED STORED 列，实测两层验证后自动 0→1 |

### 2.3.1 Codex 反例审查后的 6 项补修（2026-11-18 二轮）
Codex 用 `scripts/reproduce-schema-v05-review.py` 提交 7 条反例，全部成功复现。经逐条核对，6 条为真实缺陷并已修复（第 7 条见下）：
| 反例 | 根因 | 修复 |
|---|---|---|
| 同销售跨批次被拒 | 幂等键仅 `biz_event_key` 全局唯一，FIFO 跨批次第二行被误拒 | 唯一键改 `(biz_event_key, batch_id)` 复合；`source_event_id` 降索引 |
| 妥投10退货2被拒 | CHECK 把 returned 与 delivered 相加比较 qty | 改 `delivered+cancelled<=qty` 且 `returned<=delivered`（退货是妥投子集） |
| 同订单行重复插入 | `platform_item_no` 仅普通索引 | 加 `uk_platform_item(platform_item_no)` 唯一键（可空多 NULL 不冲突） |
| 跨站快照被允许 | CHECK 仅校验 scope 值域 | 加 `chk_snap_owner`：`resolved_scope=GLOBAL 或 =requested_site` |
| 快照可原地改 | 声称不可变但无强制 | `trg_snapshot_immutable` BEFORE UPDATE SIGNAL；更正须插新快照 |
| AI 未审批直接转正 | INSERT 可写 `CONVERTED/AUTO` | `trg_ai_suggestion_insert_gate`：新建强制 PENDING/MANUAL，AUTO 仅审核 UPDATE 置位 |

第 7 条「同订单平台行首次插入」FAIL 属反例脚本用例耦合：同脚本前一步已用相同 `ITEM1` 主键插入妥投退货行，导致"首次"插入被新唯一键拦截——这是修复生效的旁证，不是新缺陷。反例脚本回跑证据：`docs/verification/schema-v05/reproduce-review-after-fix-output.txt`（PASS=22 FAIL=7，7 条 REPRO 全数无法再复现）。

### 2.4 决议固化
- **时区**：所有业务时刻 UTC+8；`sal_order`/`sal_return`/`fin_statement` 加 `*_raw_text`/`*_raw_tz` 保留平台原始截止瞬间（对应 Codex `BusinessTimestamp` 契约）。
- **AI 写入治理**：`ai_suggestion` 为唯一 AI 可写暂存层（不进正式表），`status=PENDING/APPROVED/REJECTED/CONVERTED`，`review_mode=MANUAL/AUTO`（默认人工），`ai_auto_review_enabled=false`、`ai_auto_review_rules=[]` 落参数表，审批通过才转正式单据。
- **利润两维度**：维度A `allocation_policy`（NONE/PARTIAL/FULL，仅 SITE 行 L2 合并）× 维度B `sku_allocation_enabled`（默认 0，才决定 SKU 分摊，ΣSKU=SITE）独立落地于 `fin_profit_snapshot`，唯一键含两维度 + `calc_batch_no`。

## 3. 验证（可复现）

```bash
cd /Users/dream/Work/noonERP
python3 scripts/verify-schema-v05.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin
```

实测环境：MySQL 8.4.7（隔离一次性实例，端口随机，结束自动关闭，不碰生产）。
结果：**31/31 PASS**（22 项原有断言 + 9 项 Codex 反例修复断言：跨批次允许、妥投10退货2合法、退货>妥投拦截、平台行重复拦截、跨站快照拦截、GLOBAL 回退允许、快照 UPDATE 拦截、AI 直接转正拦截、AI 新建 PENDING 允许；另含三方共存、45 表 5 视图、初始化、CHECK、生成列、视图拆分、重复执行幂等）。
报告：`docs/verification/schema-v05/verify-schema-v05-output.txt`。
反例回跑：`python3 scripts/reproduce-schema-v05-review.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin` → 7 条 REPRO 全部无法再复现（PASS=22 FAIL=7，FAIL 为预期）；证据 `docs/verification/schema-v05/reproduce-review-after-fix-output.txt`。

一致性核对：45 表 + 5 视图全部在 ER 文档映射中出现，`ER_SQL_CONSISTENCY_OK`。

## 4. 需要 Codex 处理的对接事项

1. **Repository 实现**：`noon_rule_config` 历史查询（键 + 真实站点/GLOBAL 完整候选，不按当前状态过滤）、锁行 `SELECT ... FOR UPDATE`、审计同事务；框架侧 `VersionedConfigReader`/`ConfigIntervalPolicy` 已就位但未接表。
2. **并发测试**：隔离 MySQL 并发覆盖首次写入竞争、锁等待、回滚释放（schema 已备锁行表，测试待做）。
3. **管理 API**：参数写/撤销、AI 自动审核规则配置（`ai_auto_review_rules`）、审核转正接口。
4. **业务落快照**：7 张业务表的 `config_snapshot_batch_no` 写入时机与事务边界（WP2 接入）。
5. **站点授权读取**：`sys_user_site` 授予范围 + `userId=1` 超管全站点，接入 `SiteAuthGuard`。
6. **PRD/原型同步**：P2-3 C-5 案例数字、权限治理章节增补 AI 闸门（另开工包）。

## 5. 边界与待定（不虚构完成）

- 正式 Repository、管理 API、MySQL 并发测试、追溯性更正/双时态、生产迁移脚本（旧 `sys_config`→`noon_rule_config`）、真实平台数据验证均未实现。
- 未对任何生产库写操作、未部署；verification 用一次性实例结束即销毁。
- 权限相关表结构与若依源码逐字段核对以 `yx-erp/sql/ry_20260320.sql` 为准；若若依版本变更需重新核对命名空间。
