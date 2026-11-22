# UI 对照原型调整 + 采购单 404 修复契约

> 任务来源：用户 / Codex「按原型调整界面并修复入口」（2026-11-22）。仅前端体验层 + 菜单路由种子修复，不改业务逻辑、后端接口，不连生产。原型数据均为浏览器内存模拟，不得冒充真实业务数据。

## §1 采购单 404 根因与修复

### 1.1 现象
本地演示「供应商与采购 → 采购单管理」点击进入若依 404 页；同组「供应商管理 / SKU 报价 / 收货记录」正常。

### 1.2 根因（证据链）
1. 复现：浏览器点击 `a[href='/noon-purchasing/order']` → 落地 404 整页。
2. vue-router 实况：67 条路由中 `/noon-purchasing/supplier|quotation|receipt` 均在，**`/noon-purchasing/order` 缺失**，而 `/noon-sales/order`（name=Order）在。
3. 对照本地源码构建：`order-CT-_V906.js` chunk 正常生成 → 排除组件编译/打包缺失。
4. 菜单 SQL：M3 采购单(3130) 与 M5 销售订单(3710) **path 均为 `order`、route_name 均为空**；若依 `getRouteName('', 'order') => 'Order'`（SysMenuServiceImpl:447-450），全局路由名重复；前端 `addRoute` 同名路由被覆盖，采购单路由丢失。
5. 排除：菜单可见说明 getRouters 返回了该项；同组其他页面正常说明目录路由/component 解析正常。唯一差异是 `order` path 跨模块重名。

### 1.3 修复（两条路径，均已验证）
- **数据层（根治）**：`yx-erp/sql/noon_menu_route_name_fix.sql`（幂等 UPDATE）——3130→`PurchaseOrder`、3710→`SalesOrder`；`noon_m3_menu.sql` 种子同步补 route_name（新装库正确）。
- **前端兜底（已装库不改也能用）**：`store/modules/permission.js` 增 `dedupeRouteNames`，对三份路由数据按完整路径生成全局唯一 name（不隐藏菜单、不改 path、不改权限）。

### 1.4 验证
- 隔离 MySQL 8.4：修复 SQL 执行前两条 route_name 空 → 执行后 `PurchaseOrder`/`SalesOrder`、uniq_names=2 → 第二次执行结果不变（幂等）。
- 浏览器端到端（修复后前端 + 隔离演示后端 60152，dev server 60237）：路由表 67→68，采购单路由注册（保留 name=Order，销售订单重命名为 NoonSalesOrder）；**从首页登录态点击「供应商与采购 → 采购单管理」正常打开页面**——面包屑「首页/供应商与采购/采购单管理」、页面提示「M3 隔离演示数据…」、搜索表单（采购单号/供应商ID/站点/状态/来源）与表格列完整，无 404。

## §2 原型对照改造（已完成：首页工作台 + 站点切换）

按用户"优先让 Noon ERP 工作台、站点切换和采购等主要流程贴近原型"要求，协调豆包按文件分工完成 P0 两项（前端体验层，不改业务逻辑/后端/接口/权限）。

### 2.1 改动文件与范围
| 文件 | 改动 | 红线遵守 |
|---|---|---|
| `views/index.vue` | 若依默认首页 → Noon ERP 工作台：数据口径提示条、欢迎/站点上下文、4 个真实 KPI + 4 个待接入/未实现 KPI、七环节业务闭环（已实现可点/未实现灰态）、闭环说明 | 无若依默认内容残留；无写死原型模拟值（286430/78210）；未实现标示 13 处；跳转用 router.push 真实路由 |
| `store/modules/site.js`（新增） | Pinia site store：siteCode(默认 ALL)、siteOptions 来自 listSite 真实授权站点、initSites()/setSite() | 不写死 AE/SA；setSite 只接受 ALL 或真实授权站点（防伪造）；接口失败降级空选项+ALL 不阻塞 |
| `layout/components/Navbar.vue` | 顶栏 `.right-menu` 加站点切换 el-select（ALL + 授权站点），onMounted 调 initSites() | tooltip 明示"仅收敛展示与查询范围，不改变权限"；数据隔离仍由服务端 SiteAuthGuard 保证 |

### 2.2 首页 KPI 真实数据来源
- 真实类（显示 list 接口 total，带站点上下文）：采购单 `listOrder`、供应商 `listSupplier`、销售订单 `listOrders`、退货单 `listReturns`；无权限显示占位、失败显示"—"，不伪造数字。
- 统计类（原型为模拟值，本实现统一"—"+状态标签）：本周销售额/L1 贡献毛利标"待接入统计接口"；AI 待复核/账单争议标"未实现"。跨站点 CNY 归一需汇率接口，不写死演示汇率（符合 FR-01.5）。

### 2.3 浏览器验证（隔离环境 dev server 60237 + 演示后端 60152）
- 首页正常渲染 Noon ERP 工作台，数据口径说明完整；4 个真实 KPI 显示 0（演示库确实 0 单，真实值）；4 个统计类 KPI 显示"—"+标签。
- 站点切换：下拉仅"全部站点 + AE"（演示库只授权 AE，**未伪造 SA**）；切换到 AE 后顶栏、首页"当前站点"、KPI"站点：AE"同步，KPI 请求带 siteCode 收敛。
- 采购单页面（见 §1.4）：从菜单点击正常打开，无 404。
- 前端 vite build 通过（豆包独立验证 + WorkBuddy 独立复跑均 EXIT=0）。

### 2.4 未搬入的原型元素与待决（不臆造、不标完成）
- 原型 8 步 DEMO 内存演示链、静态 KPI（¥286,430 等）、模拟 SKU/ORDERS/账单/利润/AI 数据：**整体不搬**（禁止模拟冒充真实）。
- 原型 11 组导航（财务/补货/报表/集成/系统）在若依侧无实现：本轮不伪造可点菜单，首页闭环图以灰态"未实现/规划中"标示；是否调整 sys_menu 分组贴近原型属用户决定（涉及菜单种子数据，本轮不动）。
- 站点选项对超管/普通用户的展示策略：本实现一律取 listSite 真实授权，不区分角色放行（安全优先）。

## §3 全局站点上下文接入 + 主要业务页原型化呈现（2026-11-22 续）

### 3.1 协作说明
原计划由豆包按文件分工编码；实际派发后 CLI 返回 `auth_unavailable: 无法连接身份验证服务`，`doubao auth status --verify` 同样失败，豆包会话未产生任何文件改动。为避免停工，WorkBuddy 直接实现本轮前端改动；未连接生产、未部署、未推送，未读取/输出任何密码。

### 3.2 统一站点上下文实现
新增 `src/composables/useSiteScope.js`：`siteFilter` 在 `ALL` 时返回 `null`（请求不传 `siteCode`），具体站点返回该 code；`siteLabel` 用于页面展示；`watch` 站点变化时由各列表自行重置页码并重拉真实接口。服务端 `SiteAuthGuard` 未改，前端不新增任何授权逻辑。

接入页面（10 个站点相关列表）：采购单、收货记录、FIFO 批次、四态台账、调拨、盘点、ASN、头程费用、销售/退货（两个 tab）、仓库。处理口径：
- 查询列表的站点选择器改为 disabled，并显示当前 `siteLabel`；值由 store 同步，不再允许页面内孤立选择造成与顶栏不一致。
- `getList()` 用 `siteFilter` 覆盖请求 `siteCode`；切换站点会重置 `pageNum=1` 并重新请求。
- 新增/导入弹窗的默认站点取当前站点；`ALL` 时仍保留原必填选择，不自动伪造站点。

全局主数据（店铺、站点、SKU、Partner SKU、供应商、SKU 报价）仅增加醒目提示：“全局主数据：本页不按站点过滤；仅授权用户可维护，站点级权限仍由服务端控制。”未给这些页面硬加站点过滤。

### 3.3 主要业务页原型化状态/操作呈现
在核心页增加只读说明条，内容来自真实业务状态机与已实现边界，不使用原型内存演示数据：
- 采购单：`DRAFT → CONFIRMED → PARTIAL_RECEIVED → RECEIVED → CLOSED`，仅当前状态可用操作。
- FIFO 批次：`received_at/batch_id` 出库；COGS 唯一来源 `inv_batch_consumption`。
- 四态台账：可用/在途/锁定/不可售；库存推送未实现。
- 调发/盘点/ASN/头程：分别标注在途拆批、快照闸门、ASN 状态机、费用分摊未实现。
- 销售/退货：`IMPORTED → DELIVERED → RETURNED/CANCELLED`，仅 DELIVERED 触发 FIFO；A/B/C 分级已接入，退货/申诉时效校验未实现。

### 3.4 验证
- `npm run build:prod -- --outDir /tmp/noon-ui-site-context-build --emptyOutDir false` 通过（Node 22.22.2）。
- 浏览器（http://127.0.0.1:8081/）实测：顶栏 ALL/AE/SA 切换后，四态台账、采购单、销售/退货页的提示条与 disabled 站点字段同步；SPA 菜单跳转保持站点上下文；ALL 显示“全部授权站点”。销售/退货两个 tab 的站点字段同步。供应商页显示全局主数据提示且无站点筛选。
- 证据：`browser/site-context-purchase-SA.png`（SA 状态下采购单页：提示条、disabled 站点字段、表格正常，无 404）。
- 用户/Codex 关注的“顶栏 AE、页面查 SA”错配已消除；服务端鉴权未改，仍为最终边界。

### 3.5 未声称完成
- 原型 DEMO 内存链、静态模拟 KPI/账单/AI 数据仍未搬入。
- 首页统计类 KPI（销售额/L1/AI/账单争议）仍标“待接入/未实现”。
- 退货/申诉时效、B/C 物理处置、分级触发/QA/改判作废、M6/M7/M9 均未因本轮 UI 改动变为完成。
- 导航分组：现有 M1–M5 菜单已覆盖原型主数据/采购/库存/物流/销售核心分组，本轮未新增财务/补货/报表/集成等未实现菜单。

## §4 SKU 核心流程打通：SKU → 采购 → 头程费用（2026-11-22 续）

### 4.1 需求与实现范围
按用户系统修改意见，以系统内 SKU 为管理核心，优先打通三大模块。实现沿用现有术语“头程费用”（用户笔误“投程费用”指同一模块）。

- **采购单号系统自动生成**：`OrderService.save()` 不再读取客户端 `orderNo`，服务端按 `PO:<SITE>:<yyyyMMdd>:<UUID8>` 生成（如 `PO:AE:20261122:CFAD6DAF`），靠 `uk_pur_no` 唯一键 + 3 次冲突重试保证唯一。新增单客户端传 `orderNo: null`；修改时仅作一致性校验。
- **供应商可选**：`pur_order.supplier_id` 改为可空（`noon_m3_purchase_optional_supplier.sql` 幂等迁移，开发库已执行两次幂等通过；`PUR_ORDER_SUPPLIER_NULLABLE=YES`）。新增 `PurchasingRefs.requireActiveSupplierIfPresent()`：null 放行，非 null 必须 ACTIVE；报价仍走严格 `requireActiveSupplier()`。前端供应商字段改为只读展示 + “选择供应商” + “清空”。
- **采购明细改为选择系统内商品**：移除 SKU ID 手填框，改为“选择系统内商品”弹窗，从真实 `listSku` 选择，选中带出 `skuCode/nameEn/nameAr` 并提交真实 `skuId`。SKU 管理页新增“转采购”，携带 `?skuId=` 跳转采购单并预选商品。
- **三大模块跳转**：采购单页新增“头程费用”跳转按钮；头程费用页标注流程边界。

### 4.2 服务端/DDL/测试改动清单
- `OrderService.java`：新增 `nextOrderNo()` 与冲突重试；`save()/update()/confirm()` 供应商可选；Java 17（本机 21.0.13）编译通过。
- `OrderInput.java`：JavaDoc 明确 orderNo 服务端生成、supplierId 可空。
- `PurchasingRefs.java`：新增 `requireActiveSupplierIfPresent()`，保留严格版给报价。
- `noon_m3_purchasing.sql` + `docs/noon-erp-schema-v0.5.sql`：`supplier_id` 改可空。
- `noon_m3_purchase_optional_supplier.sql`：幂等 ALTER 迁移脚本。
- `PurchasingServiceTest.java` / `MySqlPurchasingIT.java`：断言服务端生成单号、无供应商单保存确认、重复客户端单号不冲突。
- `logback.xml`：`log.path` 支持 `${LOG_PATH:-/home/ruoyi/logs}` 覆盖，解决本机启动失败。

### 4.3 端到端浏览器验证（真实接口 + 开发库）
环境：后端 8080 / 前端 8081，MySQL 8.0.35 开发库 `noon_erp_dev`，DEMO 数据 `DEMO-SKU-001/002`、`DEMO-SUP-001`。

1. **SKU → 采购**：SKU 管理页点“转采购”→ 进入 `?skuId=1`，弹窗明细自动带出 `DEMO-SKU-001 / Demo Bluetooth Radio`（ID 1），采购单号框 disabled（提示“保存后由系统自动生成”），供应商显示“可为空”+“选择供应商”。选供应商 AE 站点、填单价 88、提交 → 列表出现 `PO:AE:20261122:CFAD6DAF`，成本 88。API 核对详情：`supplierId:1`、`skuId:1`、`unitPrice:88`、`fxRate:1`、`totalAmount:88`。
2. **供应商可空 + 选择商品入口**：采购单页“新增”→ 明细“选择系统内商品”弹窗选 `DEMO-SKU-002` → 带出“DEMO-SKU-002 / Demo Pet Backpack”（ID 2）；供应商保持空、站点 AE、单价 66、提交 → 列表出现 `PO:AE:20261122:892CCA4F`，供应商“未指定”，成本 66。API 核对详情：`supplierId:null`、`skuId:2`、`totalAmount:66`。
3. **采购 → 头程费用**：采购单页“头程费用”按钮跳转到 `/noon-logistics/shipment-cost`，页面提示“基础流程：SKU 管理 → 采购单 → ASN 入仓 → 头程费用。当前费用行仅按 ASN 关联；采购单直连字段尚未实现。”

证据：
- `browser/sku-to-purchase-e2e.png`（采购单列表含两条服务端生成单号与 DEMO 明细）。
- `browser/sku-to-purchase-shipment-cost.png`（头程费用页流程提示）。
- API 详情查询：`/noon/purchasing/order/3` 与 `/4`（带 Admin-Token）返回上面 JSON 数据。

### 4.4 未声称完成 / 边界（§4 阶段性状态）
- 本节 §4 完成时采购单与头程费用尚无直连（只有 `asnId`），仅做跳转入口与边界提示。**该状态已由 §5 的「多选关联采购单」落地更新**：现通过关联表 `log_shipment_cost_po` 实现直连，详见 §5。
- 头程费用分摊/利润接入仍未实现（`allocationFlag` 仅标记）。
- 原型 DEMO 内存链、静态 KPI、M6/M7/M9 等既有未完成项状态不变。

## §5 头程费用多选关联采购单 + 两字段用途待核实（2026-11-22 续）

### 5.1 需求
用户：「新增投程费用支持多选关联多个采购单」+「把新增投程费用里的『期间』『分摊标记』两个字段列为待核实疑问，要求对方说明用途」。“投程费用”沿用现有“头程费用”术语。

### 5.2 关联模型与实现
- **多对多关联表** `log_shipment_cost_po`（`cost_id + po_id` 复合主键，`site_code` 冗余审计）：一笔费用可关联多个采购单、一个采购单可出现在多笔费用中。迁移 `yx-erp/sql/noon_m4_shipment_cost_po_link.sql`（幂等），schema v0.5 同步。与 `asn_id` 单关联并存。
- 后端：`ShipmentCostInput.poIds`（兼容旧 10 参构造器）；`poIds()` 校验去重升序、上限 200；每个采购单须存在且同站点（跨站/不存在 400，写入前）；纳入 CREATE 指纹（同键改选即 400 内容冲突，空 poIds 指纹逐字节兼容历史）；同事务写关联表 + 审计；list 批量装载 poIds（非 N+1），get/详情回显升序 poIds。
- 前端：新增弹窗「关联采购单」多选（真实 `listOrder` 按站点拉取，显示单号/金额/状态）；列表「关联采购单」列显示 N 单；详情显示关联单号；顶部流程提示改为「可多选关联当前站点采购单」。

### 5.3 验证
- H2 `ShipmentCostServiceTest` 12/12（新增 3 用例：多选去重回显+审计 / 跨站与不存在拒绝 / 同键改选冲突）；MySQL IT 建表与迁移同步；前端 build 通过。
- 开发库浏览器 E2E（MySQL 8.0.35）：新增费用选 PO id 1/3/5 → 列表「3 单」→ 详情 API `poIds:[1,3,5]` → 详情弹窗显示三单号；不存在 poId 返回 400。证据 `browser/shipment-cost-multi-po-detail.png`。

### 5.4 两字段用途待核实（请对方说明，未擅自改变语义）
1. **期间 `period`（CHAR(7) YYYY-MM）**：现为「归属期间，空取 UTC+8 当前业务月」，DDL 注释「L2 归集期间」，但费用只登记不归集。待明确：① 会计入账月 / 到货月 / 发运月？② 空值取当前月是否正确（还是应取 ASN 到货月/采购单下单月）？③ 是否需按期间汇总接口？明确前保留现状，不臆造归集逻辑。
2. **分摊标记 `allocation_flag`（NONE/PARTIAL/FULL）**：现为纯标记不实际分摊，默认 NONE。待明确：① PARTIAL/FULL 判定标准（金额比例/采购单数量/人工指定）？② 由谁设置、依据什么，是否需与「多选关联采购单」联动（FULL=全额摊到所关联采购单 / PARTIAL=按比例）？③ 最终消费方（M6 分摊 / 利润 / 财务）？明确前保持仅标记，不接任何成本计算。

### 5.5 未声称完成
期间归属口径与分摊规则待答复；费用在多个采购单间的分摊计算、利润接入、真实平台适配等仍未实现。未连接生产、未部署、未推送。
