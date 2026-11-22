# M4 第三批 ASN 收货闭环 · 若依生成器采用对照

本文件记录本批对若依代码生成器的**实际使用**与采用情况（用户明确要求"按既定要求使用若依生成器"）。

## 生成器实调（E2E 内）

`scripts/verify-m4-asn-e2e.py` 在隔离 MySQL + 完整若依应用中实调：
1. `POST /tool/gen/importTable?tables=log_asn` —— 导入主表。
2. `PUT /tool/gen` —— 配置 crud / element-plus / 包名 com.yx.noon / 模块 inbound / 业务名 asn。
3. `GET /tool/gen/batchGenCode?tables=log_asn` —— 导出 ZIP 到 `generated/raw/generated.zip` 并解压。

导出骨架含 `Asn.java`(domain)、`AsnMapper.java/xml`、`AsnService.java`、`AsnController.java`、`asn/index.vue` 等标准 CRUD 文件。

## 采用对照

| 生成骨架 | 实际采用 | 说明 |
|---|---|---|
| `asn/index.vue` 列表 + 分页 + 搜索骨架 | ✅ 采用并扩展 | `yxerp-ui/src/views/noon/inbound/asn/index.vue` 保留生成的表格/分页/查询结构，扩展三动作弹窗（新建/封单/收货）与血缘详情。 |
| `AsnController` CRUD 骨架 | ⚠️ 重写 | ASN 是状态机（create→seal→receive）而非单表 CRUD，Controller 改为三个动作入口 + result/list/get；权限注解沿用生成口径 `noon:inventory:asn:*`。 |
| `AsnService`/`Mapper` 单表 CRUD | ⚠️ 替换为内核 | 库存守恒/四态台账/在途拆批/短少 ASN_SHORT/幂等不能由单表 CRUD 表达，改由 `com.yx.noon.asn.AsnService` 事务内核实现（豆包 owner）；Mapper 单表查询过时弃用。 |
| 菜单 SQL 骨架 | ✅ 采用 | `noon_m4_asn_menu.sql` 采用生成的目录/菜单/按钮三级结构与 perms 命名，段位 3600 避开既有。 |

## 未直接采用原因

ASN 的核心价值是库存四态守恒与 FIFO 血缘，属强事务领域逻辑，单表 CRUD 生成器只能产出字段级增删改查，无法表达"扣源仓→在途子批次→实收转 OPEN→短少记消耗→双边台账"的业务闭环。故采用生成器的**列表/查询/菜单骨架**（前端展示层可复用），动作层与内核由手写事务服务承担——与 M4 头程费用、盘点两批的采用口径一致。
