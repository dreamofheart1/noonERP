# 采购数量守恒 v2 真库 E2E 报告（2026-11-23）

> 对应 `contract.md` §7–§11。MySQL 8.0.35 开发库 `noon_erp_dev`，前后端本机 8080/8081（经前端 `/dev-api` 代理）。真实单据，非夹具。

## 前置

- 后端新 jar（14:49 打包）重启加载 v2 代码；前端 dev server 热更新。
- 全量单测：`mvn -pl yxerp-noon,yxerp-admin -am test` → 465 用例，0 错误 0 失败，7 个 `MySqlAsnIT` opt-in skip。
- 前端构建：`npm run build:prod` 通过。

## E2E-1｜available-items 排除"发完明细"（初始态）

初始 AE 已收货明细：item1（收10/直发10）、item5（收10/直发6+4=10）。本地入库 0 单。

```
GET /noon/inventory/asn/available-items?siteCode=AE
→ code 200, data: []     # 两条明细均已发完，avail=0 被正确排除
```

## E2E-2｜新接口返回真实可发量

对 PO2/item2（原 CONFIRMED）收货 5 件（requestId=E2EV2-RCV-*，code 200）：

```
GET available-items?siteCode=AE
→ item2: qtyReceived=5, directShipped=0, inboundQty=0, avail=5
```

## E2E-3｜先本地入库 → 直发可发量下降

PO2/item2 本地入库 3 件（仓库 DEMO-WH-AE id=1，requestId=E2EV2-INB-*，code 200）：

```
GET available-items → item2: 已收5, 已直发0, 已入库3, 可发2
```

**双向超量拦截**（统一口径已生效）：

| 动作 | 请求量 | 结果 |
|---|---|---|
| 直发 ASN | 3（>可发2） | 400「直发量超过可发量…已收货=5，已建直发 ASN=0，已入库=3，可发=2，本次=3」 |
| 本地入库 | 3（累计6>可入库2） | 400「合格量超过该来源行可入库量…累计收货=5，已入库=3，已直发=0，可入库=2，本次=3」 |

## E2E-4｜先直发 → 本地入库被拒（另一顺序）

直发 2 件（=可发上限，ASN:AE:B7707F41，code 200）：

```
GET available-items → data: []     # item2 已收5=直发2+入库3，avail=0，从可选消失
本地入库 1 件 → 400「…已入库=3，已直发=2，可入库=0，本次=1」
```

两种先后顺序均无法双重占用，满足契约 §8。

## E2E-5｜浏览器 UI

- **四态台账**：SKU 编码（DEMO-SKU-002）、商品名称（Demo Pet Backpack）、仓库名称（Noon FBN AE 官方仓 / AE Demo Warehouse）正常显示；仓库筛选下拉（4 个仓库）选择"AE Demo Warehouse"后只剩 1 条（后端筛选生效）；顶部提示明确"系统内部库存，非 Noon 官方实时库存"；站点隔离与可用/在途口径不变。截图 `/tmp/noon-ledger-v2.png`。
- **ASN 新建**：默认采购直发；采购明细下拉显示后端权威额度文案「PO:AE:… · SKU1 · 已收1 · 已直发0 · 已入库0 · 可发≤1」（PO3/item3 收货 1 件后）；选中后发运量自动带入 1；输入 2 提交被前端拦截并提示「发运量不能超过可发额度 1」，弹窗保持打开。发完明细不再出现在下拉。截图 `/tmp/noon-asn-v2-avail.png`。

## 未验证 / 边界

- 并发压测未单独做（串行化依赖 `pur_order_item` 同一行 `FOR UPDATE`；H2 并发用例易抖动），单测已覆盖两种顺序 + 超 R 拒绝。
- 真库 IT（`MySqlAsnIT`）未注入隔离库 URL，opt-in skip；真库关键路径已由本报告 E2E 覆盖。
- DDL 索引迁移脚本 `yx-erp/sql/noon_m6_conservation_v2_index.sql` 已写好（幂等，两普通索引）；本次因远程开发库连接认证被拒（出口 IP 授权）未执行到库，需有权限时执行。schema v0.5 已同步两索引。
- 期间/分摊标记仍搁置不接财务；Noon 平台建仓/预约接口未验证；未连接生产、未部署、未推送。
