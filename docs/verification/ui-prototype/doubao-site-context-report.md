# 全局站点上下文接入 + 主要业务页原型化呈现 报告

> 执行者：豆包 CLI（MainAgent）。时间：2026-11-22。范围：仅前端 `yx-erp/yxerp-ui`。
> 未改后端、SQL、菜单种子、`docs/agent-collaboration.md`、`config/secrets`；未读取或输出任何数据库密码；未构建生产包、未部署、未远程推送。
> 服务端 `SiteAuthGuard` 仍是唯一授权边界；本轮前端改动只收敛查询范围与展示，不新增任何授权判断。

## 1. 前置状态说明（避免重复认领）

工作区里已存在上一轮（WorkBuddy）的初步接入（见 `docs/verification/ui-prototype/contract.md` §3）。本轮在其基础上做复核、修复与补齐，重点修掉两处会让页面直接崩掉的问题，并把「顶栏 AE / 页面仍可选 SA」的错配收敛为只读展示。

## 2. 修改文件清单

| # | 文件 | 本轮改动 |
|---|---|---|
| 1 | `src/composables/useSiteScope.js` | 新增返回 `siteOptions`（来自 `siteStore.siteOptions`，即真实 `listSite` 授权站点），供弹窗下拉使用，避免前端写死 AE/SA |
| 2 | `src/views/noon/inbound/asn/index.vue` | 修复运行期 TDZ 崩溃（`siteFilter` 在 `data` 初始化前被引用）；新建 ASN 默认站点=当前站点、ALL 时置空必选、下拉取授权站点；搜索区站点改只读标签；状态机说明纠偏并标注 Slot 未实现；状态列中文映射 |
| 3 | `src/views/noon/logistics/shipmentCost/index.vue` | 同样修复 TDZ 崩溃；登记弹窗默认站点=当前站点、ALL 必选、下拉取授权站点；搜索区只读；提示条明确「仅登记、分摊未实现」 |
| 4 | `src/views/noon/purchasing/order/index.vue` | 搜索区站点改只读标签；新增/编辑弹窗站点默认取当前站点（ALL 必选），下拉取授权站点；`reset()` 调整为先清校验再写默认值，避免 `resetFields` 覆盖默认站点 |
| 5 | `src/views/noon/purchasing/receipt/index.vue` | 搜索区补当前站点只读标签，查询强制走 `siteFilter` |
| 6 | `src/views/noon/inventory/batch/index.vue` | 搜索区只读站点标签；删除写死的 `siteOptions=["AE","SA"]` |
| 7 | `src/views/noon/inventory/ledger/index.vue` | 同上；列名按四态口径改为「锁定 / 不可售」；提示条说明「由单据过账生成、本页只读、库存推送未接入」 |
| 8 | `src/views/noon/inventory/transfer/index.vue` | 搜索区只读；新建弹窗展示当前站点（不伪造可选站点）；建单请求带上当前 `siteCode`（ALL 时 `null`，后端仅做一致性校验）；状态列中文映射 |
| 9 | `src/views/noon/inventory/stocktake/index.vue` | 搜索区只读；新建弹窗展示当前站点；提示条改为 `DRAFT → APPLIED`、应用不可撤销；状态列中文映射 |
| 10 | `src/views/noon/inventory/inbound/index.vue` | （清单外补充）同属站点相关列表，一并接入 `siteStore`：搜索只读、查询带站点、新增默认当前站点、下拉取授权站点，消除同类错配 |
| 11 | `src/views/noon/sales/flow/index.vue` | 订单/退货两个 tab 的站点过滤改只读；订单导入、退货录入弹窗默认当前站点、ALL 必选、下拉取授权站点，并补站点必填校验；妥投/红冲/处置仍用行内真实站点 |
| 12 | `src/views/noon/masterdata/warehouse/index.vue` | 搜索区只读；新增/修改弹窗站点默认当前站点（ALL 必选）、下拉取授权站点 |
| 13 | `src/views/noon/masterdata/store/index.vue` | 全局主数据只读说明（措辞统一） |
| 14 | `src/views/noon/masterdata/site/index.vue` | 同上 |
| 15 | `src/views/noon/masterdata/sku/index.vue` | 同上 |
| 16 | `src/views/noon/masterdata/partnerSku/index.vue` | 同上（页内站点为记录属性筛选，未接入顶栏上下文） |
| 17 | `src/views/noon/purchasing/supplier/index.vue` | 「全局供应商主数据」只读说明 |
| 18 | `src/views/noon/purchasing/quotation/index.vue` | 「全局供应商报价」只读说明 |

未改动：任何 Java / SQL / `sys_menu` 种子 / 后端接口 / 权限配置。

## 3. 关键实现

- **统一入口**：所有站点相关列表用 `useSiteScope()`。`siteFilter` 在 `ALL` 时返回 `null`，请求不带 `siteCode`；具体站点返回该 code，`getList()` 用 `siteFilter` 覆盖请求参数。
- **切换即收敛**：`watch(siteStore.siteCode)` 里重置 `pageNum=1` 后重新拉真实列表接口（销售页两个 tab 各自重置）。
- **不写死站点**：删除页面内 `["AE","SA"]` 常量、`siteCode: 'AE'` 兜底与写死 `el-option`；搜索区统一改只读标签 `{{ siteLabel }}（顶栏切换）`，从根源消除「顶栏 AE、页面查 SA」。
- **弹窗默认站点**：新增/导入弹窗默认 `siteFilter`，`ALL` 时留空并要求用户从授权站点中选择（后端仍按仓库/单据真实站点鉴权）；编辑沿用单据真实站点值。
- **全局主数据不接站点过滤**：店铺/站点/SKU/Partner SKU/供应商/报价 6 页只加只读说明，不注入 `siteCode`，不改接口调用。
- **原型对齐（只做真实可实现部分）**：采购单 `DRAFT→CONFIRMED→PARTIAL_RECEIVED→RECEIVED→CLOSED` 且操作按状态显示（原有 `v-if`/`v-hasPermi` 保留，未新增权限）；ASN `DRAFT→SEALED→RECEIVED`，Slot 预约标注未实现；调拨 `DRAFT→IN_TRANSIT→RECEIVED`（发运/收货按钮按状态禁用）；盘点 `DRAFT→APPLIED`；四态台账只读；批次 FIFO 与 COGS 来源说明；销售 `IMPORTED→DELIVERED→RETURNED/CANCELLED`，退货 A/B/C 已接入、时效校验标注未实现。全部数据仍来自现有真实 API，未写入任何模拟数字。

## 4. 构建验证

```bash
cd yx-erp/yxerp-ui
npm run build:prod -- --outDir /tmp/noon-ui-site-context-build --emptyOutDir false
```

- 结果：`✓ built in 10.21s`，`EXIT=0`（Node v22.23.2 / npm 10.9.8）。
- 产物目录 `/tmp/noon-ui-site-context-build`（约 9.1 MB，含 `index.html`、`static/`），未清理、未覆盖项目内 `dist`。
- 说明：`package.json` 只有 `build:prod`，没有 `build` 脚本，因此用 `build:prod` 等价执行；参数与建议命令一致。
- 附加检查：对改动过的 SFC 逐个请求本地 dev server（`http://127.0.0.1:8081/src/...`）的 vite 转换产物，均返回 200，无编译报错。

## 5. 已修缺陷（本轮关键）

1. **运行期崩溃**：`inbound/asn`、`logistics/shipmentCost` 曾在 `data` 初始化里引用尚未声明的 `siteFilter`（TDZ，`ReferenceError`），页面会直接白屏。已调整声明顺序，默认站点改到打开弹窗时赋值。
2. **伪造默认站点**：两处及销售导单/退货录入的 `siteFilter.value || 'AE'` 兜底会在「全部授权站点」时静默填 AE，违反「ALL 时必须用户选择」；已全部改为 `null` + 必填校验。
3. **只读展示与真实授权脱节**：搜索区原 `disabled` 下拉仍挂着写死 AE/SA 选项、ALL 时显示占位「请选择站点」；已改为 `siteLabel` 只读标签。

## 6. 未完成 / 未验证（不声称完成）

- **浏览器端点击级验证未执行**：本环境的浏览器自动化后端返回 `BrowserUse sending window binding timed out`（重连一次仍失败），无法实际点开页面验证切换站点后的渲染与请求。因此本轮结论只到「构建通过 + vite 模块编译通过 + 代码级复核」，没有截图证据。
- 站点上下文存于 Pinia 内存，**刷新页面会回到 ALL**；未做持久化（如需可后续加 localStorage，但与「授权来自服务端」需一并评估）。
- 原型中 `SLOT_BOOKED`（Slot 预约）、库存推送（绝对值 set）、头程费用实际分摊、退货/申诉时效计算、B/C 物理处置、财务/补货/报表/集成等模块仍未实现，页面仅以文字标注「未实现/待接入」。
- 全局主数据页（`partnerSku`、`site`）页内仍有站点属性筛选的下拉且选项为静态 AE/SA；因这些是全局主数据、按任务要求不接站点过滤，本轮未改其选项来源。
- 未做 `npm run dev` 端到端联调（后端 8080、前端 8081 虽在监听，但没有可用的浏览器通道）。
