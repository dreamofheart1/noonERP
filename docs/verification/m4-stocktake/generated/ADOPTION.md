# M4 第三轮第一批 · 盘点闭环「若依代码生成器采用对照」

日期：2026-11-22。按项目约定（`docs/development-modules.md` §6）：从 M4 起一般须实际运行若依生成器生成前后端骨架，再结合现有模块/权限/业务规则调整；不适用的部分如实说明。

## 生成范围（E2E 实际调用）

`scripts/verify-m4-stocktake-e2e.py` 在隔离真库环境中通过若依 `/tool/gen` 实际执行：

| 表 | 操作 | 产物落盘 |
|---|---|---|
| `inv_stocktake` | `importTable` → 配置（`com.yx.noon` / `inventory` / `stocktake`）→ `batchGenCode` | `generated/raw/generated.zip` + 解包文件 |

生成的是 element-plus 风格的 `index.vue` / api / Java CRUD 骨架，报告见 `e2e-output.txt` 的「生成器导入/配置/导出」三项 PASS。

## 采用与手写对照

| 部件 | 来源 | 说明 |
|---|---|---|
| 盘点页查询区（el-form inline + 搜索/重置 + right-toolbar） | **采用生成骨架** | `views/noon/inventory/stocktake/index.vue` 的查询区、表格列布局、pagination 沿用生成器结构 |
| 列表字段映射（stocktakeNo/warehouseId/siteCode/status/createdAt） | **采用生成骨架** | prop 命名与生成骨架一致，便于后续按生成器结果维护 |
| API 函数结构（list/get/create） | **采用生成骨架范式** | `api/noon/inventory/stocktake.js` 的 request 封装沿用生成范式 |
| 新建盘点弹窗（动态明细行 + SKU/批次/实盘量/事件号 + 幂等 requestId） | **手写** | 生成器只产单表 CRUD，无法表达「一单多行 + 实时台账基线 + 幂等 requestId 本地持久化」 |
| 应用盘点弹窗（账面/实盘/差异确认 + result 回查防重） | **手写** | 跨表过账动作，非生成范畴 |
| 盘点服务 `com.yx.noon.stocktake.*`（create/apply/resultOf/list/get） | **手写** | 跨 6 张表事务（inv_stocktake/item、inv_batch、inv_batch_consumption、inv_stock_ledger、inv_stock_move、幂等表、审计），生成器单表 CRUD 不适用 |
| 迁移 DDL（盘点幂等表）与菜单 SQL | **手写** | 遵循项目已有 M4 迁移/菜单约定 |
| E2E / H2 单测 / MySQL IT / 浏览器脚本 | **手写** | 验证脚手架，非生成范畴 |

## 未采用生成器的部分及原因

- **服务层**：生成器产出的是单表 CRUD（insert/selectById 等），本批核心是跨表状态机（create → apply，盘亏 FIFO 逐批消耗、盘盈 ADJUSTMENT 承接成本、四态台账与流水守恒、动作级幂等、同事务审计），沿用 M4 第一轮入库/第二轮调拨已获 Codex 认可的处理方式——跨表逻辑手写并说明原因。
- **`inv_stocktake_item` 明细**：作为主表子表随新建弹窗手写（生成器无法生成主子表联动 + 动态行）。
- **盘盈成本/事件键持久化**：按契约留白由后端裁决（result_json 快照），生成器不涉及。

## 验证

- 生成骨架确实由 E2E 在隔离真库环境调用若依生成器产出（非手工编写、非仅导出 zip 冒充采用）；
- 采用部分（查询区/表格/分页/API 范式）落在最终页面，手写部分（新建/应用弹窗、服务状态机）为生成骨架无法满足的业务逻辑；
- 最终页面经 `vite build`（E2E 内）与真实浏览器（新建→应用闭环）验证。
