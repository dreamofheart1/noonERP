# M4 若依代码生成器产物采用对照（v1.1）

日期：2026-11-22。回应 Codex「M4 第一轮重点复核」——不能仅凭导出 zip 视作采用，必须说明实际采用了生成产物的哪些代码、未采用部分及原因。

## 1. 生成调用

E2E `scripts/verify-m4-e2e.py` §0 通过完整应用（admin 登录）实际调用：
- `POST /tool/gen/importTable?tables=<tbl>&tplWebType=element-plus`
- `PUT /tool/gen`（className/moduleName/businessName 等配置）
- `GET /tool/gen/batchGenCode?tables=<tbl>` 导出 zip 并解压落盘。

三张表均实际导入并导出骨架：
| 表 | 产物目录 | 用途 |
|---|---|---|
| `inv_inbound` | `generated/raw/` | 入库单骨架（历史保留；业务未采用其 CRUD 代码，见 §3） |
| `inv_batch` | `generated/raw/batch/` | FIFO 批次骨架 → **只读页 `batch/index.vue` 实际采用其结构** |
| `inv_stock_ledger` | `generated/raw/ledger/` | 四态台账骨架 → **只读页 `ledger/index.vue` 实际采用其结构** |

## 2. 实际采用的生成代码（可逐项对照）

来源：`generated/raw/batch/main/resources/.../index.vue` 与 `generated/raw/ledger/.../index.vue`（生成器标准 Vue 模板），对照交付 `yxerp-ui/src/views/noon/inventory/{batch,ledger}/index.vue`：

| 生成骨架元素 | 交付页对应 | 采用方式 |
|---|---|---|
| `<el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch">` 查询区 + 搜索/重置按钮 | 两页同结构 | 直接沿用 |
| `<right-toolbar v-model:showSearch @queryTable="getList">` | 两页同结构 | 直接沿用 |
| `<el-table v-loading="loading" :data="xxxList">` + `<el-table-column prop=...>` 列渲染 | 两页同结构（列按业务字段调整） | 沿用骨架，字段改业务字段 |
| `<pagination :total v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList">` | 两页同结构 | 直接沿用 |
| `<script setup>` + `const data = reactive({ queryParams: { pageNum, pageSize, ... } })` + `const { queryParams } = toRefs(data)` | 两页同结构 | 直接沿用（pageSize 沿用生成命名） |
| `getList() / handleQuery() / resetQuery()` 三函数范式 + 末尾 `getList()` 调用 | 两页同结构 | 沿用结构，内部 `response.rows/total` 改为 `PageResult` 的 `response.data.rows/total`（M2/M3 契约）并换算 offset/limit |
| api.js `listXxx(query)` 模式（`request({ url, method:'get', params })`） | `api/noon/inventory/{batch,ledger}.js` 的 `listBatch/listLedger` | 沿用函数签名与请求范式，URL 改业务接口 |

**结论**：batch/ledger 两页的骨架结构（模板 + 脚本范式 + api 范式）来自生成器，非空口宣称；仅有业务必改项（只读化、字段、URL、分页换算）为改造，符合「实际生成骨架并在其上修改」。

## 3. 未采用生成骨架的部分及原因（如实）

1. **`inv_inbound` 后端骨架**（生成的 Controller/domain/mapper/service/xml，`generated/raw/main/java/...`）：标准单表 CRUD，无法承载 M4 入库 confirm 的跨 6 表同事务状态机（入库单+明细+FIFO 批次+四态台账+库存流水+幂等+审计）、仓库归属授权、逐行 `FOR UPDATE` 数量守恒、内容指纹幂等。这些是 M4 核心规则（契约 §2），单表 CRUD 骨架不适用；后端内核由豆包手写 JdbcTemplate 是业务必需。
2. **`inv_inbound` 前端骨架**（`generated/raw/vue/views/system/inbound/index.vue`）：标准增删改单表页，与「选采购单→拉收货明细→填合格量→确认入库」的多步业务语义不符（且 confirm 必须带 requestId 幂等键，非单表新增）。入库页 `views/noon/inventory/inbound/index.vue` 继承其查询表单/表格/分页范式，但交互为手写。
3. **权限串**：生成骨架用 `system:inbound:*`，业务实际用 `noon:inventory:inbound:* / ledger:list / batch:list`（菜单由 `noon_m4_menu.sql` 注入），生成权限串不可直接沿用，已改。
4. **分页协议**：生成器默认 `pageNum/pageSize` + 后端若依 `TableDataInfo`；本项目 M2/M3 契约统一 `PageResult{rows,total}` + `offset/limit`，故在 `getList` 内做换算，保留生成命名 `pageSize`。

## 4. 验证

`verify-m4-e2e.py` 会因三张表的导入/配置/导出产生对应断言（导入、配置、Vue 骨架、api.js、产物落盘 5 类 × 3 表）；前端 `vite build` 验证两页改造可编译；真实浏览器验证两页在 UI 上可见入库后数据。
