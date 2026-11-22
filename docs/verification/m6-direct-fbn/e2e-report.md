# 采购直发 FBN · E2E 验证报告（2026-11-23）

环境：后端 8080 / 前端 8081，MySQL 8.0.35 开发库 `noon_erp_dev`，admin 登录（RuoYi 默认口令）。
数据前置：FBN 内部仓 `FBN-AE-OFFICIAL`（id=3）/`FBN-SA-OFFICIAL`（id=4）经仓库管理 API 创建（warehouse_type=FBN、is_fbn=1、ACTIVE）；采购单 PO5 确认后部分收货 10 件（item id=5、SKU2、unit_cost_cny=20），**未建任何本地仓批次/自有仓库存**。

## 1. 直发闭环（真实单据，非夹具）

| 步骤 | 操作 | 结果 |
|---|---|---|
| create | sourceMode=DIRECT_PURCHASE, purOrderItemId=5, qty=6, FBN-AE-OFFICIAL | ASN `ASN:AE:F156D4C7`，详情 sourceMode=DIRECT_PURCHASE、batchNo=`DP:5:422b571a`、purOrderItemId=5 |
| seal | 同键封单 | 在途子批次 id=2（IN_TRANSIT、成本20）；台账 FBN SKU2 **available=0、in_transit=6**（实收前无可用库存）；父批次 id=1 `DIRECT_PURCHASE/CLOSED/remaining=0` 不虚增可用 |
| receive | 实收6 | 台账 FBN SKU2 **available=6、in_transit=0**；子批次转 OPEN；ASN 头 RECEIVED |

成本可追溯：父/子批次 unit_cost=20 = `noon_m3_receipt.unit_cost_cny`；pur_order_item_id=5 贯穿。

## 2. 分批发运 / 短少 / 幂等

- **防超发**：已收10、已发6、可发4，本次发5 → 400「直发量超过可发量：已收货=10，已建直发 ASN=6，可发=4，本次=5」。
- **无收货拒绝**：未收货的 item6 直发 → 400「采购明细尚未收货，无法直发」。
- **跨站拒绝**：SA 站关联 AE 的 item5 → 400「采购明细属于站点 AE，与 ASN 站点 SA 不一致」。
- **分批发运**：第二张直发 ASN 发4（累计6+4=10=收货量）成功，`ASN:AE:F5F08B9A`。
- **幂等**：同 requestId 重复 seal → 首次 replayed=false、重放 replayed=true、同一 ASN。
- **短少**：第二张 ASN 发4、实收3、短少1（shortEventId=SHORT-E2E-001）→ FBN 可用累计 6+3=9（短少1不虚增）；消耗流水 `AS:...|ASN_SHORT|ASNSHORT:2:SHORT-E2E-001|batch4|20.0000`。

## 3. UI 验证

- ASN 列表来源列显示「采购直发」（两张单均正确）。
- 新建弹窗「来源模式」radio 可切本地仓批次/采购直发；直发模式明细表头变为「采购明细（已收货）/SKU/发运量」，采购明细下拉加载真实已收货项 `PO:AE:20261122:BE0414CB · SKU2 · 已收10 · 可发≤10`。
- 三页流程提示更新为直发主路径（采购单/头程费用/ASN）；仓库管理页标注 FBN 为系统内部记录。
- 证据：`direct-asn-ui.png`。

## 4. 测试与回归

- `AsnServiceTest` 17/17（豆包新增直发用例：建单→seal→receive 守恒、父批次不虚增可用、无收货/超发/跨站拒绝、重复幂等、本地仓路径回归）。
- 全量 `mvn -pl yxerp-noon -am test`：**458 测试全绿，零失败/零错误**（含各模块 H2 + 隔离 MySQL IT）。
- 前端 `npm run build:prod` 通过。
- **MySqlAsnIT 本次未单独执行**（opt-in，未注入隔离库环境变量）；真库关键路径已由本开发库 E2E 覆盖。

## 5. 未声称完成 / 缺口

- Noon 平台 FBN 建仓/自动预约接口未验证，当前均为系统内部人工登记，不声称平台已建仓/已预约。
- 期间/分摊标记按用户指令搁置，未接财务计算。
- 分批收货成本按契约 v1 暂取该 item 最早一笔收货；严格按收货行 FIFO 绑定需新增 `asn_item.receipt_id`，待用户/Codex 拍板。
- 直发父批次 currency=CNY、fx_rate=1（收货表只存 CNY 成本列），多币种采购直发需后续扩展。
