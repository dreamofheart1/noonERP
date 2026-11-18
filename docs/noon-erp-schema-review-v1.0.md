# Noon ERP 数据库设计全面审查报告 v1.0

执行者：WorkBuddy　｜　日期：2026-11-18　｜　审查基线：`docs/noon-erp-schema-v0.4.sql`（45 表 + 4 视图）
依据：`docs/noon-erp-prd-v1.0.md`（PRD v1.0.3）、`docs/noon-erp-requirements-v0.2.md`（Q1~Q14）、`docs/agent-collaboration.md` 用户指令
目的：引入若依（RuoYi）二开前的独立设计审查。

---

## 0. 结论摘要（先看这里）

设计整体**成熟、自洽**：45 表 + 4 视图与 PRD 分组完全对齐，FIFO 成本/退货红冲/双层利润/三档并存/订单幂等的口径在注释与文档间高度一致，前几轮 6+3 项修订已落地。但本轮发现 **1 个 P0、4 个 P1、5 个 P2** 问题，其中：

- **P0-1（必改）**：`inv_batch_consumption` 缺业务幂等唯一键，妥投事件重复投递会**重复扣 COGS**——这是 schema 声明与 DDL 不符，直接违背 FR-06.1b 验收标准。
- **P1-1**：`fx_rate DEFAULT 1` 与 NFR-12「汇率缺失报错而非用 1 兜底」直接冲突，10 处。
- **P1-2**：`v_ai_profit` 视图暴露 SITE/SKU 混合行且无隔离提示，AI 若 `SUM(net_profit)` 会重复计算（D-01 在库存视图修过、利润视图未同步）。
- **P1-3**：若依 5 张表同名冲突（`sys_user/role/user_role/config/audit_log`），二开前必须定归属。
- **P1-4**：10 处 `ON DUPLICATE KEY UPDATE ... VALUES()` 在 MySQL 8.0.20+ 属废弃语法，能跑但有告警、未来版本移除风险。
- 若依**版本/源码尚未获得**，本报告不假设其字段，只列待核对清单。

**测试环境声明**：本机无 MySQL 客户端、无 mysqld、无 Docker、无 sqlglot/pymysql，**无法实际执行 DDL 或实测约束**。已完成的是：静态结构校验 114/114 通过（见 `docs/verification/ddl_static_check.py` 与输出）、逐字段人工交叉核对。故本报告所有"可执行性"结论均为**静态推断**，不宣称 MySQL 8 已在隔离环境实跑。未对任何生产库执行写操作。

---

## 1. 问题清单（按严重性）

标记规则：【事实】= 已在文件逐行核对；【推断】= 基于规则推导未实跑；【待决策】= 需用户拍板。

### P0 — 上线前必须修

#### P0-1　消耗流水缺业务幂等唯一键，妥投重投会重复扣 COGS 【事实】

- **位置**：`schema-v0.4.sql` L251-276 `inv_batch_consumption`；L474 注释；`sal_order_item` L472-474 注释；PRD FR-06.1b（L386）。
- **矛盾**：注释 L474 白纸黑字写「消耗流水以 `(sal_order_item_id, move_type)` 唯一，保证同一行只过账一次」，FR-06.1b 验收标准是「重复投递妥投事件，COGS 不变」。但 DDL 实际唯一键只有 `uk_consumption_no (consumption_no)`（业务流水号，每次生成都不同），`sal_order_item_id` 仅是普通索引 `KEY idx_order_item`（L273），`move_type` 连索引都没有。
- **业务例子**：同一妥投事件（webhook 或 CSV 重传）被处理两次 → 生成两条 `consumption_no` 不同、但 `sal_order_item_id` 与 `move_type=SALE` 相同的消耗流水 → `COGS = Σ amount` 翻倍 → 该订单 L1 由 50 变 0（成本被扣两次）。现有 schema 层面**无任何约束阻止**。
- **影响**：资金/利润数据错误，直接违背 NFR-08 幂等与 FR-06.1b 验收。应用层若漏做幂等则无兜底。
- **建议（待决策：幂等粒度）**：
  - 方案 A（推荐，贴合注释）：加唯一键 `UNIQUE KEY uk_consume_once (sal_order_item_id, move_type, reversal_of_id)`——用 `reversal_of_id` 区分多笔红冲，避免同一订单行多次部分退货被误挡；`move_type=SALE` 时 `reversal_of_id` 为 NULL，MySQL 唯一键允许多个 NULL，故对 SALE 仍需另加防重键。
  - 方案 B：引入 `source_event_id`（webhook event_id / CSV 行指纹）列 + 唯一键，从事件源头去重，与 `int_webhook_event.event_id` 幂等链贯通。
  - 无论哪种，须让 DDL 与注释/PRD 三处一致，并补「重复投递 COGS 不变」的可测用例。
- **注意**：这是本轮唯一 P0，建议二开冻结 schema 前先定。

### P1 — 二开前强烈建议处理

#### P1-1　`fx_rate DEFAULT 1` 与 NFR-12「缺失报错而非用 1 兜底」冲突 【事实】

- **位置**：`schema-v0.4.sql` L217/235/425/451/583 等共 **10 处** `fx_rate DECIMAL(18,8) DEFAULT 1`；PRD FR-01.5（L314）与 NFR-12（L570）明确「汇率缺失报错而非用 1 兜底」。
- **业务例子**：AED 订单导入时若未取到当日汇率，字段默认落 1 → 系统把 100 AED 当 100 CNY 计入，跨站点归一完全失真且**无任何报错**。
- **影响**：与已确认需求直接矛盾；CNY 本币场景默认 1 合理，但外币表默认 1 是危险兜底。
- **建议（待决策）**：区分本币与外币——`pur_order_item`/`inv_batch`/`fin_payout` 等 CNY 场景可保留默认 1；`sal_order`/`log_shipment_cost`/`inv_batch_consumption` 等可能外币的表改为**允许 NULL + 应用层强制校验**（导入/过账前查 `fx_rate` 表，缺失则进异常清单）。或统一去掉 DEFAULT 1，由代码显式赋值。

#### P1-2　`v_ai_profit` 视图混合 SITE/SKU 行，AI 求和会重复计算 【事实+推断】

- **位置**：`schema-v0.4.sql` L857-862 `v_ai_profit`；对比 D-01 对 `v_ai_sku_overview` 的修复（L837-855）。
- **矛盾**：视图 `SELECT ... scope_type, sku_id, ... FROM fin_profit_snapshot WHERE allocation_policy IN (...)`，同时包含 `scope_type=SITE`（站点汇总）与 `scope_type=SKU`（明细）行，且**未 select `sku_allocation_enabled`**（维度B开关）。PRD FR-08.8/6.3 已明确 SITE 是汇总、SKU 是明细、维度B关闭时 SKU 净利=自身 L1。AI 若直接 `SUM(net_profit)` 会把汇总行与明细行相加，利润翻倍——与 D-01 已修复的库存视图放大问题同类，但利润视图未同步。
- **业务例子**：AE 站点 2026-11 快照有 1 条 SITE 行（净利 78,210）+ 2 条 SKU 行（46,926 + 31,284），AI 查询 `SELECT SUM(net_profit) FROM v_ai_profit WHERE period='2026-11'` 得 156,420，实际应为 78,210。
- **影响**：AI 外挂（首个场景就是利润/账单核查）读到虚高数据，误导决策。
- **建议**：① 视图补 select `sku_allocation_enabled`，让 AI 能判断口径；② 在视图定义加注释「同一 period+site+batch 下 SITE 行为汇总、SKU 行为明细，禁止跨 scope_type 相加」，与 D-01 注释对齐；③ 更稳妥：拆成 `v_ai_profit_site` / `v_ai_profit_sku` 两个视图，物理隔离粒度。

#### P1-3　若依 5 张表同名冲突，二开前必须定归属 【事实 + 待决策】

- **位置**：`schema-v0.4.sql` L176 `sys_config`、L774 `sys_user`、L785 `sys_role`、L793 `sys_user_role`、L800 `sys_audit_log`。
- **事实**：这 5 个表名与 RuoYi 默认表名**完全重合**。若依自带 `sys_user`(user_id/user_name/nickname/password/dept_id...)、`sys_role`、`sys_user_role`、`sys_config`(config_id/config_name/config_key/config_value...)、`sys_oper_log`/`sys_loginlog`。本 schema 的同名表字段定义**与若依不同**（如本 `sys_config` 是 `(id, config_key, config_value, scope_key, effective_from, remark)`，若依是另一套）。
- **业务例子**：引入若依后若直接执行双方 DDL，`CREATE TABLE IF NOT EXISTS sys_user` 会静默跳过（表已存在），系统随后按本 schema 字段 `password_hash`/`site_scope` 写入真实若依表 → 字段不存在报错，或更糟：写进若依的兼容列造成权限/数据污染。
- **影响**：单体二开的表空间冲突，属于阻塞级集成风险。
- **建议（待用户决策，需先拿到若依版本/源码）**：三选一——
  - A：本 ERP 全部业务表加统一前缀（如 `erp_` 或 `noon_`），与若依 `sys_*` 物理隔离（推荐，改动集中在重命名）；
  - B：用户/角色/权限/审计**完全复用若依**，删除本 schema 的 5 张表，业务表 `operator_id` 外键指向若依 `sys_user.user_id`，`site_scope` 等扩展字段迁移到若依用户扩展表；
  - C：若依改名其系统表前缀再共存（侵入若依，通常不推荐）。
- **边界**：尚未获得若依具体版本（RuoYi-Vue / RuoYi-Cloud / 其他）与源码，**本报告不假设若依字段**，仅列为待核对清单（见第 2 节）。

#### P1-4　`ON DUPLICATE KEY UPDATE ... VALUES()` 为 MySQL 8.0.20+ 废弃语法 【推断】

- **位置**：`schema-v0.4.sql` L904、L923（2 处初始化幂等写）。
- **事实/推断**：`INSERT ... ON DUPLICATE KEY UPDATE col = VALUES(col)` 在 MySQL 8.0.20 起被标记 deprecated（官方建议改用 `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE col = new.col` 的行别名写法）。当前版本**仍可执行但产生告警**，未来大版本可能移除。
- **影响**：可预见的兼容性债务；不影响当前功能。
- **建议**：二开选定 MySQL 具体小版本后，若 ≥8.0.20 改为行别名写法；若锁定 8.0.19 及以下可保留。属低风险清理项。

### P2 — 建议改进（非阻塞）

#### P2-1　全表零 CHECK 约束 【事实】
- `schema-v0.4.sql` 全文 `CHECK (` 出现 **0 次**。数量类字段（`qty_*`、`qty_remaining`、`consumption.qty`）、枚举类（`status`、`grade`、`move_type`、`allocation_policy`）、`sign`（应仅 ±1）均无数据库层校验。
- 【推断】业务例子：`inv_batch.qty_remaining` 被写成负数、`cross_period` 写成 2、`sign` 写成 0，数据库均接受。当前依赖应用层自律。
- 建议：对 `sign IN (-1,1)`、`qty_* >= 0`、`allocation_policy IN ('NONE','PARTIAL','FULL')` 等关键枚举加 CHECK（MySQL 8.0.16+ 支持）。与"无物理外键"约定不冲突。

#### P2-2　`sal_return` 无业务幂等键，重复退货无约束 【事实】
- L493-516 `sal_return` 唯一键仅 `uk_return_no (return_no)`。同一 `order_no` + `sku_id` + `sal_order_item_id` 可重复插入不同 return_no 的退货单。
- 【推断】配合 P0-1，重复退货 + 重复红冲会叠加损失。建议加 `(sal_order_item_id, return_no)` 或引入平台退货单号幂等键；至少加 `KEY` 便于查重。

#### P2-3　PRD C-5 案例数字与修订后口径不一致（文档层面） 【事实】
- PRD L366 C-5 案例表写「发运 40 后父批次 `qty_remaining=60`」，但修订后的口径（v0.4 注释 L237-242、原型演示、本轮已验证）是：采购 100 中**只到货 40 建批**，父批 `BAT-001(40)` 发运后 qty=0，在途子批 40，目的仓收 38。PRD 表格仍停留在「一次到货 100、父批留 60」的旧演示。
- 影响：不属 DDL 缺陷，但案例数字与 PRD 前文 FR-04.8「部分到货」及原型冲突，易误导开发与验收。建议统一 PRD C-5 案例为「到货 40 / 未收 60 / 父批发运后 0」的当前口径。

#### P2-4　金额字段 `currency` 与 `amount` 的配对约束缺失 【推断】
- 多张金额表（`fin_statement`、`fin_payout`、`sal_order`、`fin_expense_pool`）各有 `amount` + `currency`，但跨站点归一依赖 `fx_rate`，DDL 无"归一后金额"字段也无"币种必须为受支持枚举"约束。当前口径是展示/报表层归一（NFR-12），DDL 只存原币。属设计取舍，建议文档明确"所有 amount 均原币、归一在查询时按 fx_rate 做"，避免二开时误加总。

#### P2-5　`int_export_category.is_usable` 为派生列但无生成列/触发器保证 【事实】
- L753 注释「is_usable 由三层派生：仅当 create_tested_at 与 headers_tested_at 均非空才 =1」，但 `is_usable` 是普通 `TINYINT DEFAULT 0`，靠应用层 UPDATE 维护。
- 【推断】若验证了两层却忘置 `is_usable=1`，导出任务仍被 `int_export_job` 状态机挡住（L765）。建议改为 GENERATED 列或至少写入事务里同步置位，避免状态漂移。

---

## 2. 若依二开待核对清单（不假设框架字段）

拿到若依**具体版本与源码后**逐项确认，本报告不做预设：

| # | 核对项 | 本 schema 现状 | 需确认 |
|---|---|---|---|
| 1 | 用户表归属 | `sys_user`（password_hash / site_scope / status） | 若依 `sys_user` 字段结构；是否复用、扩展还是前缀隔离 |
| 2 | 角色/权限归属 | `sys_role`(permissions JSON) + `sys_user_role` | 若依 RBAC（含 menu/post/dept）与本系统简单 RBAC 如何合并 |
| 3 | 审计日志归属 | `sys_audit_log`(before/after JSON) | 若依 `sys_oper_log`/`sys_loginlog` 是否覆盖；保留哪套 |
| 4 | `sys_config` 归属 | 平台业务参数（allocation_policy/vat_mode...） | 若依 `sys_config` 是系统参数，语义不同，必须隔离 |
| 5 | 主键类型 | 全部 `BIGINT AUTO_INCREMENT` | 若依是否一致（通常 BIGINT），关联外键类型是否匹配 |
| 6 | 时间/时区 | 全部 `DATETIME(3)` GMT+8（D-03） | 若依默认 `datetime` 无小数秒；统一策略 |
| 7 | 逻辑删除/租户 | 本 schema 无 del_flag/tenant_id | 若依是否启用租户/逻辑删除，业务表是否需补列 |
| 8 | 代码生成 | 无 | 若依代码生成的字段命名/类型映射是否兼容 DECIMAL(18,4)/JSON |

---

## 3. 已执行验证与证据

| 检查 | 方法 | 结果 | 证据 |
|---|---|---|---|
| 表/视图数量与 PRD 一致 | Python 正则统计 | 45 表 / 4 视图，无重名 ✓ | `docs/verification/ddl_static_check-output.txt` |
| 结构静态校验 | 括号平衡、主键/ENGINE 存在、索引字段存在、视图引用表存在、INSERT 字段匹配 | 114/114 ✓ | 同上 + `ddl_static_check.py` |
| 消耗流水幂等键 | 逐行核对 DDL 唯一键 vs 注释/PRD | **缺失**（P0-1） | 本报告 §1 |
| 汇率默认值口径 | 全文计数 + NFR-12 对照 | 10 处 DEFAULT 1，冲突（P1-1） | 本报告 §1 |
| 若依表名重叠 | 表名集合比对 | 5 张同名（P1-3） | 本报告 §2 |
| MySQL 8 实际执行 | 尝试探测 mysql/mysqld/docker/sqlglot | **环境不支持，未执行** | 见 §0 测试环境声明 |

复现命令：
```bash
cd /Users/dream/Work/noonERP
python3 docs/verification/ddl_static_check.py
```

---

## 4. 剩余风险与边界

- **DDL 未在 MySQL 8 实跑**：本机无数据库环境，全部可执行性结论为静态推断。上生产前须在隔离 MySQL 8 实例建库实测（尤其 JSON 列、DECIMAL 精度、`ON DUPLICATE KEY` 语法、视图 `ONLY_FULL_GROUP_BY` 兼容性）。
- **未对生产库做任何写操作**。
- 若依版本/源码未知，第 2 节仅为待核对清单，不是结论。
- 本轮未修改任何 schema 文件——用户指令是"审查并给结论"，是否调整设计待用户授权。

## 5. 需要用户拍板的问题

1. **P0-1 幂等方案**：选 A（业务唯一键）还是 B（source_event_id 事件去重）？
2. **P1-1 汇率兜底**：外币表去掉 DEFAULT 1 改报错，还是保留？
3. **P1-2 利润视图**：补字段+注释即可，还是拆成 site/sku 两视图？
4. **P1-3 若依归属**：前缀隔离 / 复用若依 / 若依改名？需先提供若依版本与源码。
5. **P1-4 MySQL 小版本**：锁定 8.0.19- 还是 8.0.20+（决定是否改 VALUES 语法）。
6. 是否授权我按上述决议**修改 schema 生成 v0.5**？本轮未改任何设计文件。
