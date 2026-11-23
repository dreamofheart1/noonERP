# M12 · Seller Lab 三接口补齐验证（重点沙特站）

日期：2026-11-24  
范围：严格只读，小样本查询；未创建/修改/删除/生成报表，未暴露凭据/会话/客户明细。  
方法：服务账号 API Login 会话（Cookie 值不记录），Java HttpClient 字节 body + gzip 解压；输出仅统计、字段名、国家归属与集合重叠数。

---

## 结论速览

| 接口 | 站点参数 | 站点归属 | 分页 | 判定 |
|---|---|---|---|---|
| FBN 库存 | ❌ `country_code` 被忽略 | AE/SA/xx 均返回同一 19 条 UAE 仓数据 | 未完整验证 | **不能按站点隔离使用** |
| ASN 列表 | ✅ `country_code` 生效 | ae→AE / sa→SA，空码→0 行 | `next_token` 正常，两页 0 重叠 | 可隔离，分页可用 |
| Sales 明细 | ✅ `country_code` 生效 | ae→AE / sa→SA，空码→0 行 | `page` 生效但有漂移风险 | 可隔离，分页需防重复 |

会话机制：无 Cookie → **401**；重新 API Login → **200**。可正常重登，不依赖固定会话。

---

## 一、FBN 库存：站点参数无效（重要）

`POST .../fbn-inventory` 必填 `inventory_tab_name`（`all`/`saleable` 有效，`ALL` 报 Unknown）。

```text
country_code = ae / sa / xx  →  均 http 200，均 19 行
仓库集合：ae∩sa=19、ae∩xx=19（完全相同）
仓库码形态：AUH#OS / AUH#S / SHJMS#（均为 UAE 仓；# 为数字脱敏）
```

**结论**：该接口的 `country_code` 参数**被服务端忽略**，返回的是当前会话默认站点（UAE）库存，
无法用它读取沙特 FBN 库存。上一轮 M11 标注"SA 未验证"，本轮确认是**参数无效而非未验证**。
若需要 SA 库存，须另找站点切换机制（如 Seller Lab 站点上下文/其他接口），本次未绕过认证尝试。

字段与 Codex 浏览器观察一致：partner_sku / sku / warehouse_code / saleable / non_saleable / qty / title / image 等。

---

## 二、ASN 列表：站点隔离与分页均可用

`GET .../shipment/list?country_code=&page_size=5`

```text
ae → total 22、rows 5、国家列 [AE]、next_token 有
sa → total 12、rows 5、国家列 [SA]、next_token 有
xx → total 0、rows 0、国家列 []、无 next_token（空结果正确）
第二页：ae rows 5、重叠 asn_nr=0；sa rows 5、重叠 asn_nr=0
```

站点参数生效、空结果正确、分页不重不漏。字段含 `asn_nr`（平台 ASN 编号）、
status、country_code、dst_warehouse_code、expected_qty、qty_received、qty_putaway、updated_at、items。

**风险**：仍未确认这是官方支持接口；`asn_nr` 虽可解"平台编号不可得"，但不建议在生产依赖。

---

## 三、Sales 明细：站点有效，分页存在漂移

`POST .../sales-dashboard/list`，必填 `country_code`。

```text
ae → total 132（三次查询中出现 131/132）、rows 20、国家列 [AE]
sa → total 194、rows 20、国家列 [SA]
xx → total 0、rows 0（空结果正确）
AE∩SA 的 item_nr 交集 = 0（跨站不冲突）
```

### 稳定性/重复同步风险（重要）

```text
同参数连续 3 次：run0∩run1 = 20（完全一致），run1∩run2 = 0（完全不同）
中间 total 从 132 → 121（结果集边界发生变化）
page=2：ae 与 run0 重叠 16，sa 与 run0 重叠 0
offset/limit、page_num 参数：与 run0 重叠 20（即参数被忽略，回到第一页）
```

**判断**：`page` 参数生效，但**结果集随时间变化**（total 132→121、页边界漂移），
同一 `page` 在不同时刻可能返回完全不同的一页；`offset/limit`/`page_num` 不被识别。
因此：

- `item_nr` 在单次快照内唯一且跨站不冲突，可暂作列表展示编号（与用户 11-24 决定一致）；
- **不能假设"按 page 翻一遍"能稳定遍历全量**；重复同步必须按 `(country_code, item_nr)` 幂等，
  并处理"同一 item_nr 状态/时间戳更新"与"旧记录消失"的情况；
- 尚未验证同一 item_nr 跨时间的状态变化与唯一性长期稳定，不能宣称保证。

字段：item_nr / partner_sku / sku / status / fulfillment_model / country_code /
order_timestamp / shipment_timestamp / delivered_timestamp / offer_price / currency_code / awb_nr；无 order_nr。

---

## 四、会话失效与重登

```text
无 Cookie 调用 ASN  → http 401
重新 API Login      → 成功（cookieParts=3，值不记录）
重登后调用 ASN      → http 200，rows=1，total=22，国家列 [AE]
```

认证可用服务账号重建，不依赖固定浏览器会话。但 Cookie 有效期、刷新机制、
是否被 Seller Lab 前端域正式授权、使用条款均未确认；**非公开接口，不得称适合生产**。

---

## 五、未覆盖项

- FBN 库存 SA 站点数据（参数无效，未找到替代入口）。
- ASN/Sales 全量分页遍历的完整性与长期稳定性。
- 同一 item_nr 随时间状态变化、以及与其他导入源的重复入账关系。
- 非公开接口的正式支持、SLA 与授权条款。

未连接生产、未部署、未接入 ERP、未改业务数据。请 Codex 复核。
