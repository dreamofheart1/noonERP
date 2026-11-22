# M4 第三轮第二批「头程费用登记」豆包实现报告

日期：2026-11-22。Owner：豆包（后端内核）。契约：[contract.md](contract.md) v1（WorkBuddy owner，未改动）。

## 1. 实现清单（只新增 `com.yx.noon.shipment` + 测试 + 本报告）

| 文件 | 说明 |
|---|---|
| `yxerp-noon/src/main/java/com/yx/noon/shipment/ShipmentCostService.java` | 契约 §2 四方法：`create` / `resultOf` / `list` / `get`，签名逐字对齐；`JdbcTemplate` + `SiteAuthGuard`，无新依赖 |
| `ShipmentCostInput.java` | `record(Long asnId, String siteCode, String period, String feeType, BigDecimal amount, String currency, BigDecimal fxRate, String allocationFlag, String remark, String requestId)`，public |
| `ShipmentCostResult.java` | `record(long costId, String costNo, boolean replayed)`，public |
| `ShipmentCostRow.java` | 列表摘要：`id/costNo/asnId/siteCode/period/feeType/amount/currency/fxRate/allocationFlag/createdAt` |
| `ShipmentCostDetail.java` | 详情：列表字段 + `remark` |
| `CostQuery.java` | `record(String feeType, Long asnId, String period)` |
| `ShipmentCostRules.java` | 包内规则：站点/费用类型/金额/币种/汇率/期间/分摊标记/备注/requestId 校验、UTC+8 业务时刻、SHA-256 指纹、分页上限 |
| `ShipmentCostAudit.java` | `@Component`，同事务写 CREATE 审计（见 §2.4） |
| `src/test/java/com/yx/noon/shipment/ShipmentCostServiceTest.java` | H2（MySQL 模式、真 Spring 事务、真 MyBatis 站点 Mapper、真 `SiteAuthGuard`），9 个用例 |
| `src/test/java/com/yx/noon/shipment/MySqlShipmentCostIT.java` | 隔离 MySQL 真库 IT：真 DDL/schema v0.5 建表 + 正式迁移脚本，5 个用例 |

未改动：契约、`yx-erp/sql/noon_m4_shipment_cost.sql`、菜单、Controller、前端 api/页、E2E/runtime 脚本、协作文档。服务签名与 Controller 依赖完全一致（`PageResult` 复用 `com.yx.noon.masterdata.PageResult`）。

## 2. 裁决点（契约未定死或需说明选择的地方）

### 2.1 fx_rate 口径
- **CNY**：必须显式 `fxRate=1`；缺失、`0`、`2` 均 400（不兜底、不默认）。
- **外币**：必须 `> 0`；缺失/`0`/负数 400。
- 落库与指纹统一按 `DECIMAL(18,8)` 定长 8 位 HALF_UP（`1` → `1.00000000`，`3.6725` → `3.67250000`），避免 `1` 与 `1.0` 在指纹/展示上不一致。

### 2.2 period 空值
- **写库**：空/空白 → 取 **UTC+8 业务时刻当月** `YYYY-MM`（与 M3/M4 业务时刻口径一致，`Asia/Shanghai`，毫秒截断）。
- **指纹**：使用**入参归一化后的 period**（空 → 空串），不把"当前业务月"卷进指纹。理由：同一 requestId 在月末提交、下月重试时应判定"同内容重放"，而不是"内容不一致"；指纹必须与时间无关。
- 非空必须是严格 `YYYY-MM`（`2026-1`、`202611`、`2026-13`、`2026-00` 均 400）。

### 2.3 allocation_flag 语义
- 空/空白 → 默认 `NONE`；非空 ∈ `NONE/PARTIAL/FULL`（大小写归一），否则 400。
- **只落标记，不实际分摊**：不写 `inv_batch` 成本、不写 `inv_batch_consumption`、不改台账/COGS；`PARTIAL/FULL` 与 `NONE` 在库存侧完全等价。分摊算法留 M6（契约 §0/§9）。

### 2.4 审计选择（同事务 CREATE 审计）
- 契约允许"沿用现有模式"，本批迁移**只新增幂等表**、不新增审计表（DDL 由 WorkBuddy owner），因此**复用 M4 共享审计表 `noon_m4_inventory_audit`**，以 `biz_type='SHIPMENT_COST'`、`action='CREATE'`、`biz_id=费用行 id`、`detail` 摘要（单号/站点/期间/类型/金额/币种/汇率/分摊标记/ASN）、`operator_id=当前若依登录人` 区分；不新增 DDL、不越 owner 边界。
- `ShipmentCostAudit.record` 由 `create` 在**业务写入同一 `@Transactional` 内**调用：费用行/审计/`markDone` 任一失败整单回滚（PENDING 占位一并消失，可安全重试）；重放路径不写审计。
- 表为 append-only，应用侧无更新/删除入口（与既有 M4 模式一致）。

### 2.5 授权顺序（"授权先于判重"）
- `create`：`siteCode` 格式校验（空/非 AE-SA 400）→ `requireCurrentUserId()` → **`guard.requireSite`** → 业务字段校验 → ASN 同站点校验 → 指纹/判重/写入。即：**已登录但跨站点的请求在任何业务校验与幂等判重之前就 403**，不因后续字段非法而变成 400、不泄漏"该 requestId 是否已存在"。
- `resultOf`：先按幂等行真实 `site_code` 授权，**授权通过后才判 PENDING/DONE 并返回**；无权 403，非法/不存在/PENDING 返回 null，全程只读。
- `list`：指定站点先 `requireSite` 再单站过滤；未指定返回 `allowedSiteCodes()`（超管=AE+SA，普通=已授权），**空授权必须零行**（WHERE 至少一站点，绝不退化为全表）。
- `get`：先按 id 取费用行真实 `site_code`，再 `requireSite`；不存在 400，跨站 403。
- 带 `asnId`：`log_asn` 不存在 400；`site_code != 登记站点` 400（跨站不允许关联）。

### 2.6 幂等细节
- 指纹：`SHA-256("CREATE|" + asnId + "|" + siteCode + "|" + period + "|" + feeType + "|" + amount(4位) + "|" + currency + "|" + fxRate(8位) + "|" + allocationFlag + "|" + remark)`；空 asn/period/remark 按空串，币种/类型/站点大写、备注 trim、金额/汇率定长——同键任一业务字段变化即指纹不同 → 400「内容与原请求不一致」。
- 三态：`claimRequest` 以 `INSERT ... cost_id=0, status='PENDING'` 抢占（主键冲突即判重）；DONE 同内容重放返回 `replayed=true` 且零写入；PENDING 重试 400「正在处理中」；提交前 `markDone` 回填真实 `cost_id` + `result_json(CREATE, replayed=false)`，重放/`resultOf` 统一输出 `replayed=true`。
- 同键换站点 400（先判站点再判内容）；`result_json` 只缓存 `{costId,costNo}`，`resultOf` 会校验费用行仍存在。
- `cost_no = "SC:<SITE>:" + UUID8`（大写），落库撞 `uk_cost_no` 时换号重试（最多 3 次，实际概率极低）。

### 2.7 防伪造
- 入参 record 无 `id/costNo/status/createdAt` 分量，Jackson 绑定后这些 JSON 字段无处可落（结构性拒绝，非"校验后忽略"）；`created_at`/`cost_no`/主键全部服务端生成。
- 备注 trim 后 ≤512；`requestId` 8–64 位 `[A-Za-z0-9_-]`。

### 2.8 列表/分页
- `limit` 非法（<1）收敛为默认 20，上限 200；`offset<0` 400；`total` 与列表共用同一 WHERE，为真实总数；排序 `id DESC`。
- 过滤非法值同样 400（非法 feeType、asnId≤0、非法 period），不静默忽略。

## 3. 测试结果

| 套件 | 结果 | 说明 |
|---|---|---|
| `ShipmentCostServiceTest`（H2） | **9 / 9 通过** | 登记成功 + 单号格式 + 金额 4 位 HALF_UP + 汇率 8 位 + 空期间业务月 + CREATE 审计 + 库存/批次/台账/COGS 零写入；全部校验分支；伪造字段忽略；DONE 重放零写入 / 同键改内容 400 / 跨站点同键 400 / PENDING 400 + `cost_id=0` 占位 / DONE 回填真实 id / `resultOf` 命中且只读；越权站点 403、跨站 ASN 400、撤销授权后 `resultOf/get/list` 403、空授权零行、授权先于重放；列表真实 total + feeType/asnId/period 过滤 + 翻页不重不漏；详情；并发同 requestId 只登记一笔；`uk_cost_no` 唯一 |
| `MySqlShipmentCostIT`（隔离 MySQL 8.4.7） | **5 / 5 通过** | 真 schema v0.5 DDL + 本批正式迁移 + `noon_m4_inventory.sql`；迁移脚本二次执行幂等；真库 `chk_ship_cost_alloc` CHECK、`uk_cost_no`、幂等表 `request_id` 主键、`cost_id=0` 落库；真库登记（金额/汇率列宽、审计、库存零写入）；重放/内容冲突/`resultOf`；站点授权/跨站 ASN/撤销授权；**两线程并发同 requestId 只落 1 行、仅 1 条 CREATE 审计、无残留 PENDING** |
| `yxerp-noon` 全模块 `mvn test` | **184 / 184 通过**（0 失败 / 0 错误 / 0 跳过） | 含既有 purchasing 13、transfer 21、reversal 25、intake 45、stocktake 15、inventory 12、fifo 26、masterdata 18 与本批 shipment 9，无回归 |
| 含 `-am`（framework） | **213 通过** | framework 29 + noon 184 |

真库 IT 默认 **opt-in**：未注入 `NOON_MYSQL_TEST_PORT`（或 `NOON_MYSQL_TEST_URL`）时整体 skip，H2 套件不受影响；**全程未连生产**。

## 4. 复现命令

H2（本批单测）：

```bash
cd yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -B -ntp -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am \
  -Dtest=ShipmentCostServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

真库 IT（先启动一次性隔离 MySQL 8.4.7，root 密码随机，`--default-time-zone=+08:00`）：

```bash
cd yx-erp
NOON_MYSQL_TEST_PORT=<隔离实例端口> NOON_MYSQL_TEST_PASSWORD=<root 密码> \
/tmp/apache-maven-3.9.11/bin/mvn -B -ntp -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am \
  -Dtest=MySqlShipmentCostIT -Dsurefire.failIfNoSpecifiedTests=false test
```

IT 在端口模式下会在该实例内建一次性库 `noon_m4_ship_<uuid>` 并在结束时 drop；本报告数字来自 Mac 本机临时 `mysqld`（`/tmp/mysql-8.4.7-macos15-arm64`），实例目录在系统临时目录、跑完即销毁。

全模块：

```bash
cd yx-erp
/tmp/apache-maven-3.9.11/bin/mvn -B -ntp -o -s /tmp/noon-maven-settings.xml \
  -Dmaven.repo.local=/tmp/noon-m2 -pl yxerp-noon -am test
```

## 5. 边界与交由 WorkBuddy 的项

- 本批**未实际分摊**：`inv_batch` / `inv_stock_ledger` / `inv_stock_move` / `inv_batch_consumption` 在 H2 与真库均断言零写入；`allocation_flag` 仅标记。
- 只支持登记，不支持修改/作废（契约 §9 待业务确认）。
- `viewer` 角色无 create 权限属 Controller/权限注解层，由 WorkBuddy E2E 覆盖；本包只保证 service 层 `guard` 调用与 403 语义。
- 菜单、Controller、前端生成器采用、E2E/runtime 脚本、全量验收与协作文档由 WorkBuddy owner，本报告不代述其结论。
