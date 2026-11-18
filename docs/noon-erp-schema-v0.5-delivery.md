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

### 2.3.2 Codex followup 二轮审查后的 5 项补修（2026-11-18 三轮）
Codex 提交 `scripts/reproduce-schema-v05-followup.py`（42 项，独立键避免 `ITEM1` 耦合），独立复跑 42/42 一致，报告 5 类风险全部真实。修复如下：
| 风险 | 修复 |
|---|---|
| 多次部分妥投/退货冲回同一批次被拒 | 幂等键语义从「订单行/原消耗 ID」改为「每次实际过账事件 ID」：`SALE:`+delivery_event_id、`REV:`+return_event_id；`(biz_event_key,batch_id)` 复合唯一同时保证跨批次多行、重传拦截、不同事件可再消费同批。事件 ID 来源与 NEEDS_REVIEW 降级、累计守恒需应用行锁事务——写入 DDL 注释，明确 DDL 只管单次幂等 |
| 平台行全表唯一过严 | `uk_platform_item` 改 `(order_no, platform_item_no)`；`uk_return_source` 改 `(order_no, source_return_no)`，均注明待平台样本证实后再收紧 |
| 快照可物理 DELETE | 加 `trg_snapshot_no_delete` BEFORE DELETE SIGNAL；归档仅走受控迁移，应用账号无 UPDATE/DELETE 权限，触发器为第二道防线 |
| AI 未审核可直接 UPDATE 转正 | 加 `trg_ai_suggestion_update_gate`：审批态变更须 reviewed_by+reviewed_at、AUTO 须 auto_rule_ref、CONVERTED 须审批依据、终态不可回退 PENDING；完整审批闭环仍需服务端鉴权/审计/行锁（已在注释写明） |

**辨析**：followup 的 `REPRO 第二次部分妥投/退货被拒` 两条在修复后仍显示 PASS，因其脚本构造用了**同一 biz_event_key**（模拟"同事件重传"，按新契约本就该拒）。真正的多次部分动作应生成不同事件 key，已由本脚本 F1b/F2b 正向断言证明可行。回跑证据 `docs/verification/schema-v05/followup-after-fix-output.txt`（PASS=39 FAIL=3：快照删除、AI 越权转正、平台行跨订单复用三条风险已翻转为 FAIL=不再复现）。

### 2.3.3 Codex 三轮复核后的 2 类补修（2026-11-18 四轮）
Codex 提交 `scripts/reproduce-schema-v05-third-review.py`（63 项：WorkBuddy 45 基线 + 18 项独立观察），独立复跑 63/63 一致。它同时交付真实参数历史只读 Repository（`MyBatisRuleConfigRepository` + `RuleConfigHistoryMapper.xml`，全历史不按当前状态过滤、`useCache=false`、站点守卫前置）——本轮独立复核通过（`--config-history` 17 项 MySQL 原生测试 + 20 项运行断言全绿），但只读组件不等于写入/并发闭环。需要 WorkBuddy 修订的两类 P1：

| 风险 | 修复 |
|---|---|
| 跨站同订单号的子表唯一键冲突：`sal_order` 以 `(order_no,site_code)` 唯一允许 AE/SA 同号，但 `sal_order_item.uk_platform_item(order_no,...)`、`sal_return.uk_return_source(order_no,...)` 只按平台订单号去重，误拒跨站同号 | 锚点改为可信内部身份：`uk_platform_item(sal_order_id, platform_item_no)`（自增主键天然区分站点与订单实例）；`sal_return` 新增可空 `site_code`，`uk_return_source(site_code, order_no, source_return_no)`。同站同号同源/同行拦截、跨站同号可区分。平台号真实作用域未证实，不虚构全局唯一；站点归属一致性由应用层 `requireSite` + 父订单校验 |
| AI 终态检查不完整：`REJECTED→APPROVED/CONVERTED` 可改判，`auto_rule_ref` 任意非空即可 AUTO/CONVERTED | `trg_ai_suggestion_update_gate` 改为显式状态转移白名单：仅 `PENDING→APPROVED/REJECTED`、`APPROVED→CONVERTED` 及同态补充审核字段合法；`REJECTED`/`CONVERTED` 终态不可改判，进入审批态必须 reviewed_by+reviewed_at，CONVERTED 须审批依据。`review_mode=AUTO` 须 auto_rule_ref；规则是否真实命中 `ai_auto_review_rules` 由服务端校验（触发器不查配置表，不虚构规则命中）。完整审批服务/主体权限仍未实现 |

**未采纳为 DDL 缺陷**：累计退货红冲 4 > 原消耗 3 仍被 DDL 允许——Codex 认同这是应用层行锁 `SUM` 事务职责，DDL 只管单次事件幂等；普通账号快照删除被权限拒绝（1142）验证了测试账号权限，不代表真实应用/运维高权限已配置，这两项保留为应用层待办。

**验证**：`python3 scripts/verify-schema-v05.py ...` → **55/55 PASS**（45 基线 + 10 项三轮正向断言 G1a-b/G2a-b/G3a-f）；`reproduce-schema-v05-third-review.py` 回跑 → PASS=59 FAIL=4，四条 OBSERVED 风险全部翻转为 FAIL：跨站同号平台行、跨站退货来源号、驳回终态改判、规则引用缺失直接转正。`docs/verification/schema-v05/verify-schema-v05-output.txt`。

### 2.3.4 Codex 四轮复核后的 3 项补修 + 参数写入框架（2026-11-18 五轮）
Codex 提交 `scripts/reproduce-schema-v05-fourth-review.py`（72 项：55 基线 + 17 观察），独立复跑 72/72 一致；另交付 `RuleConfigWriteService`（超管参数写事务）与 `MySqlRuleConfigWriteIT`。本轮独立复核：四轮脚本 72/72、`--config-write` 运行时 21 项 MySQL + 20 项运行断言全绿、默认 Maven 单元测试 29/0。写入服务代码审核通过：`lock()` 用 `INSERT ... ON DUPLICATE KEY` + `SELECT FOR UPDATE` 处理首次写竞争，`create`/`revoke` 同事务写审计并回滚，`now()` 用 Asia/Shanghai 毫秒精度，仅超管可写且 GLOBAL 不因站点授权放行。需要 WorkBuddy 修订的三类 schema 风险：

| 风险 | 判定 | 修复 |
|---|---|---|
| 退货 `site_code=NULL` 唯一键失效 | **认同，真缺陷**（三轮可空 site_code 引入）：MySQL 中 NULL<>NULL，两条 NULL 站点同订单同来源号可重复落库 | `sal_return` 加生成列 `site_key=COALESCE(site_code,'_UNSET_')`，唯一键改 `uk_return_source(site_key,order_no,source_return_no)`。`_UNSET_` 非真实站点，落正式表前应用须按父订单回填站点并人工核对；真实幂等仍以 biz_return_key 为准 |
| 父子真实身份一致性未受 DDL 约束 | **部分认同**："子行 order_no 与父不一致""SA 退货引用 AE 订单行"属可验证关联不变量；但 Codex 明确其实验用隔离库 root，不证明应用 API 可越权（API 未实现） | 加 `trg_sal_item_parent_check`/`trg_sal_return_parent_check`：父订单/关联行真实存在时强制 order_no、站点一致，父不存在则放行（兼容历史导入与隔离裸插，不破坏基线）。站点归属与 requireSite 交服务端，DDL 只封"引用真实父记录却声明矛盾身份" |
| AI 审核载荷/审核人同态可篡改 | **认同，真缺陷**：正常批 PENDING→APPROVED 后同态改 payload qty、reviewed_by，再转 CONVERTED 可载荷掉包 | `trg_ai_suggestion_update_gate` 增不可变规则：OLD 非 PENDING 时 payload_json/reviewed_by/reviewed_at/auto_rule_ref/site_code/source_agent/scene 均不可改（NULL-safe `<=>`），review_note 允许补充审计。隔离 AI 账号仅 SELECT/INSERT 的 UPDATE 1142 只证明该账号权限，不代表真实部署 |

**未采纳为 DDL 缺陷**：直接 SQL 累计红冲超原消耗（应用层行锁 SUM 事务职责，Codex 已认同）；平台行/退货号真实作用域无样本，本轮只用 schema 允许的假设案例，不宣称已验证的 Noon 事实。

**验证**：`verify-schema-v05.py` → **68/68 PASS**（55 基线 + 13 项四轮断言 H1a-b/H2a-d/H3a-e）；`reproduce-schema-v05-fourth-review.py` 回跑 → PASS=65 FAIL=7，7 条 OBSERVED 风险全部翻转为 FAIL（子行 order_no 伪造、NULL 来源重复、SA 退货引用 AE 行、载荷/审核人同态篡改 4 条、篡改后转正、终态改审核人）。证据 `docs/verification/schema-v05/fourth-review-after-fix-output.txt`。

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
结果：**68/68 PASS**（22 项原有 + 9 项一轮反例修复 + 14 项二轮 followup 修复 + 10 项三轮修复断言 + 13 项四轮修复断言 H1a-b/H2a-d/H3a-e：NULL 站点归一、父子身份一致、AI 审核字段不可变；另含三方共存、45 表 5 视图、初始化、CHECK、生成列、视图拆分、重复执行幂等）。
报告：`docs/verification/schema-v05/verify-schema-v05-output.txt`。
反例回跑：`python3 scripts/reproduce-schema-v05-review.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin` → 7 条 REPRO 全部无法再复现（PASS=22 FAIL=7）；证据 `docs/verification/schema-v05/reproduce-review-after-fix-output.txt`。
followup 回跑：`python3 scripts/reproduce-schema-v05-followup.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin` → PASS=39 FAIL=3（快照删除/AI 越权转正/平台行跨订单复用三条不再复现；另两条同 key 场景见 §2.3.2 辨析）；证据 `docs/verification/schema-v05/followup-after-fix-output.txt`。
三轮回跑：`python3 scripts/reproduce-schema-v05-third-review.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin` → PASS=59 FAIL=4（跨站同号平台行/退货来源号、驳回终态改判、规则缺失直接转正四条 OBSERVED 全部不再复现）；证据见本报告同目录 `third-review-after-fix` 说明。
四轮回跑：`python3 scripts/reproduce-schema-v05-fourth-review.py --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin` → PASS=65 FAIL=7（7 条 OBSERVED 风险全部不再复现，详见 §2.3.4）；证据 `docs/verification/schema-v05/fourth-review-after-fix-output.txt`。

一致性核对：45 表 + 5 视图全部在 ER 文档映射中出现，`ER_SQL_CONSISTENCY_OK`。

## 4. 需要 Codex 处理的对接事项

1. **Repository 实现（框架侧已完成，待接真实场景）**：Codex 已交付 `MyBatisRuleConfigRepository`（只读全历史、不按当前状态过滤）与 `RuleConfigWriteService`（超管写事务、锁行当前读、审计同事务）；均经隔离 MySQL 集成测试。尚未开放 Controller/管理 API，未接真实业务表快照写入。
2. **并发测试（框架侧已完成）**：`MySqlRuleConfigWriteIT` 已覆盖首次同键竞争只一版本提交、`data_lock_waits` 实测锁等待、审计失败回滚、撤销回滚与越权拒绝。管理 API 层与真实业务并发仍需后续接入。
3. **管理 API**：参数写/撤销、AI 自动审核规则配置（`ai_auto_review_rules`）、审核转正接口。
4. **业务落快照**：7 张业务表的 `config_snapshot_batch_no` 写入时机与事务边界（WP2 接入）。
5. **站点授权读取**：`sys_user_site` 授予范围 + `userId=1` 超管全站点，接入 `SiteAuthGuard`。
6. **PRD/原型同步**：P2-3 C-5 案例数字、权限治理章节增补 AI 闸门（另开工包）。

## 5. 边界与待定（不虚构完成）

- 正式 Repository、管理 API、MySQL 并发测试、追溯性更正/双时态、生产迁移脚本（旧 `sys_config`→`noon_rule_config`）、真实平台数据验证均未实现。
- 未对任何生产库写操作、未部署；verification 用一次性实例结束即销毁。
- 权限相关表结构与若依源码逐字段核对以 `yx-erp/sql/ry_20260320.sql` 为准；若若依版本变更需重新核对命名空间。
