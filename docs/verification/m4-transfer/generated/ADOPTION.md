# M4 第二轮若依代码生成器产物采用对照

日期：2026-11-22。延续第一轮 `docs/verification/m4-inventory/generated/ADOPTION.md` 口径：不能仅凭导出 zip 视作采用，必须逐项说明采用了生成产物的哪些代码、未采用部分及原因。本轮依据契约 §7「生成器要求（M4 起新规）」。

## 1. 生成调用（实际执行）

E2E `scripts/verify-m4-transfer-e2e.py` §0 在完整应用（MySQL + Redis + 若依）中以超管登录，实际调用三步：

- `POST /tool/gen/importTable?tables=<tbl>&tplWebType=element-plus`
- `PUT /tool/gen`（className / moduleName / businessName / tplCategory=crud 等配置）
- `GET /tool/gen/batchGenCode?tables=<tbl>` 导出 zip 并解压落盘

| 表 | 产物目录 | 文件数 | 用途 |
|---|---|---|---|
| `inv_transfer` | `generated/raw/` | 9 | 调拨单骨架 → **列表页 `transfer/index.vue` 采用其查询/表格/分页/脚本范式** |
| `inv_transfer_item` | `generated/raw/item/` | 9 | 调拨明细骨架 → 发运/收货弹窗明细表格参考；后端 CRUD 骨架未采用（见 §3） |

每份 zip 含：Controller / domain / mapper 接口 / mapper XML / service 接口 / serviceImpl（后端 6）、`vue/api/system/*.js`、`vue/views/system/*/index.vue`、`*Menu.sql`。

## 2. 实际采用的生成代码（可逐项对照）

来源：`generated/raw/vue/views/system/transfer/index.vue` 与 `generated/raw/vue/api/system/transfer.js`，对照交付 `yxerp-ui/src/views/noon/inventory/transfer/index.vue` 与 `yxerp-ui/src/api/noon/inventory/transfer.js`：

| 生成骨架元素 | 交付页对应 | 采用方式 |
|---|---|---|
| `<el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch">` 查询区 + 搜索/重置按钮 | `transfer/index.vue` 站点查询区 | 直接沿用结构，字段改业务 `siteCode` |
| `<right-toolbar v-model:showSearch @queryTable="getList">` | 同页工具栏 | 直接沿用 |
| `<el-table v-loading="loading" :data="xxxList">` + `<el-table-column prop=...>` | 同页调拨单列表 | 沿用骨架，列改业务字段 |
| `<pagination :total v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList">` | 同页底部分页 | 直接沿用 |
| `<script setup>` + `const data = reactive({ queryParams: { pageNum, pageSize, ... } })` + `const { queryParams } = toRefs(data)` | 同页脚本 | 直接沿用 |
| `getList() / handleQuery() / resetQuery()` 三函数范式 + 末尾 `getList()` | 同页脚本 | 沿用结构，内部 `response.rows/total` 改为 `PageResult` 的 `response.data.rows/total`（M2/M3 契约）并换算 `offset/limit` |
| api.js `listXxx(query)`（`request({ url, method:'get', params })`） | `api/noon/inventory/transfer.js` 的 `listTransfer/getTransfer/shipTransfer/receiveTransfer` | 沿用请求范式与命名，URL 改业务接口 |
| `generated/raw/item/vue/views/system/item/index.vue` 的 `el-table` 行渲染 | 发运/收货弹窗内的明细表格 | 沿用列渲染范式，字段改 `itemId/skuId/batchNo/qtyShipped/qtyReceived` |

**结论**：调拨列表页的模板骨架、脚本范式、api 请求范式与明细表格渲染均来自生成器，非空口宣称；仅业务必改项（发运/收货动作、站点授权、字段、URL、分页换算）为改造，符合「实际生成骨架并在其上修改」。

## 3. 未采用生成骨架的部分及原因（如实）

1. **后端 CRUD 骨架**（`generated/raw/main/java/...` 与 `raw/item/main/java/...`）：生成器产出的是 `inv_transfer` / `inv_transfer_item` 单表增删改查，无法承载第二轮核心——`DRAFT→IN_TRANSIT→RECEIVED` 状态机、源批次 `FOR UPDATE` 拆批与血缘、四态台账双边守恒、`TRANSFER_SHORT` 短少事件键、内容指纹幂等与两仓站点授权。这些是契约 §2 的硬规则；服务内核由豆包以 JdbcTemplate 手写为业务必需。
2. **单表增删改前端页**：调拨单不由本页创建（契约 §8 第 10 条：第二轮只做 ship/receive/resultOf/list/get，DRAFT 单由种子或后续 create 契约产生）；明细也不独立成页，而在发运/收货弹窗中按行填写。生成的新增/修改/删除按钮与本轮语义不符，未采用。
3. **权限串**：生成骨架用 `system:transfer:*` / `system:item:*`；业务实际用 `noon:inventory:transfer:{list,ship,receive}`（菜单由 `noon_m4_transfer_menu.sql` 注入，3300 段），已改。
4. **分页协议**：生成器默认 `pageNum/pageSize` + 若依 `TableDataInfo`；本项目契约统一 `PageResult{rows,total}` + `offset/limit`，故在 `getList` 内换算，保留生成命名 `pageSize`。
5. **组件版本适配**：生成参数 `tplWebType=element-plus`，项目实际为 Vue2 + Element UI；`v-model:showSearch` 等 Vue3 写法在交付页改为 Vue2 兼容写法，属必要适配。

## 4. 验证

E2E 断言覆盖两表的导入、配置、导出（含 Vue 骨架落盘）、产物解压，详见 `docs/verification/m4-transfer/e2e-output.txt`；前端 `vite build` 验证改造页可编译；真实浏览器验证列表/发运/收货弹窗在 UI 上可用。
