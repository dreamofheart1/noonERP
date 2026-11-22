# M5 订单与退货 · 若依生成器采用对照

日期：2026-11-22。E2E 中实际运行若依生成器对 `sal_order`（tplCategory=crud, element-plus）与 `sal_return` 执行 importTable → 配置 → batchGenCode，导出骨架到 `generated/raw/generated.zip`（E2E 实跑证据）。本文件记录骨架采用与手写范围。

## 采用生成器产出
- **domain/字段映射**：`sal_order`/`sal_return` 的字段列表与 CRUD 骨架，作为列表/详情返回 record 的字段参考（orderNo/siteCode/skuId/qty/grossAmount/status；returnNo/orderNo/qty/grade/compensationAmount/returnLossAmount/refundAmount/status 等）。
- **前端 list/get 骨架**：列表页的搜索表单、el-table 列、分页、`@/utils/request` 调用模式，作为 `noon/sales/flow/index.vue` 的骨架来源，再按 M5 业务动作（订单导入/妥投/退货导入/红冲/分级处置多弹窗）扩展。

## 手写（生成器不适用，原因说明）
- **编排 service（SalesFlowService）**：跨 4 个内部闸门（intake/fifo/reversal/事件登记）+ 动作级幂等 + 事务边界 + 错误映射，是多服务编排，非单表 CRUD，生成器不适用。
- **分级处置（ReturnDispositionService）**：A 级建 FIFO 批次/B/C 级 RETURN_LOSS+qty_unsellable/赔偿单次 C-2b/跨期，是强事务领域逻辑，生成器不适用。
- **HTTP 写动作 Controller**：事件登记 + 闸门调用 + 409 隔离结果映射，生成器产出的单表 CRUD 不适用。
- **E2E/runtime IT/浏览器脚本**：验证脚本，生成器不涉及。

## 未使用生成器部分
- 菜单与权限：手工写 `noon_m5_sales_menu.sql`（3700 段），因为菜单需与若依 role/site 授权体系及 M5 动作权限精确对应，生成器产出的是单表菜单骨架不含动作权限细分。
- 迁移幂等表 `noon_m5_sales.sql`：动作级幂等表结构特殊（PK(request_id,action)+result_json），手写。

## 验证
E2E `verify-m5-sales-e2e.py` 的「生成器导入/配置/导出」check 为实跑断言（非文档声明）；若 E2E 报告该项 FAIL，则以报告为准，不得声称生成器已采用。
