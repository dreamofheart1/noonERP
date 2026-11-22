# M4 头程费用登记 · 若依生成器采用对照

日期：2026-11-22。口径沿用 M4 前序批次：对适合的表**实际运行若依生成器**并在其上修改，生成产物与对照留存；不适合部分如实说明原因。本文件由 E2E `scripts/verify-m4-shipment-cost-e2e.py` 实调生成后补全产物清单。

## 生成范围

E2E 在隔离应用上真实调用：
1. `POST /tool/gen/importTable?tables=log_shipment_cost&tplWebType=element-plus`
2. `PUT /tool/gen`（配置 `className=ShipmentCost`、`moduleName=logistics`、`businessName=shipmentCost`）
3. `GET /tool/gen/batchGenCode?tables=log_shipment_cost`

生成产物落盘：`generated/raw/generated.zip` 及解压后的前后端骨架（约 9 文件：Mapper.java / Mapper.xml / Service.java / ServiceImpl.java / Controller.java / domain / api js / index.vue / sql 菜单模板）。

## 采用 / 手写清单

| 产物 | 处理 | 原因 |
|---|---|---|
| 列表页 `index.vue` 查询区 + 表格 + 分页结构 | **采用生成骨架并改写** | 标准 CRUD 列表，沿用生成的查询/表格/分页范式；按业务字段改为站点/类型/期间/ASN 过滤与费用字段列 |
| api `shipmentCost.js` 的 list/get 结构 | **采用生成范式** | 标准 REST 请求封装，改为 `/noon/inventory/shipment-cost/*` 路径 |
| 新增费用弹窗（siteCode/feeType/amount/fxRate/allocationFlag 等 + newRequestId 幂等 pending 重开） | **手写** | 生成器无「动作级幂等 + requestId 重开 result」能力，属业务定制 |
| `ShipmentCostService` 内核（create/list/get/result + 站点授权 + 幂等三态 + 审计） | **手写** | 生成器的 ServiceImpl 是单表 CRUD，无幂等/授权/审计/业务校验；沿用 M2-M4 既有服务模式（豆包实现） |
| 幂等表 `noon_m4_shipment_cost_request` | **手写 DDL** | 生成器只映射业务主表，幂等表是纯服务端机制，不暴露 CRUD |
| 费用主表 DDL | **不生成，复用 schema v0.5** | `log_shipment_cost` 已在 `docs/noon-erp-schema-v0.5.sql` 定义（含 fee_type/allocation_flag CHECK），生成器只读取表结构 |

## 验证结果

- 生成器实调成功（importTable + 配置 + batchGenCode 导出），产物见 `generated/raw/`。
- 前端 `vite build` 通过（E2E 内）。
- 完整 HTTP E2E + 真实浏览器结果见 `../e2e-output.txt`。

## 不采用生成器的部分

- **动作级幂等 + 内容指纹 + 三态**：生成器无此能力，全模块统一手写（与 M2/M3/M4 一致，已获 Codex 认可）。
- **allocation_flag 仅标记不实际分摊**：纯业务口径，生成器无法表达。
