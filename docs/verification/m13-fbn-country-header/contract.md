# M13 · FBN 库存站点请求头复验（修正 M12 结论）

日期：2026-11-24  
范围：严格只读，服务账号 API Login 会话 + 网页一致的站点请求头；未改店铺数据、未保存/输出 Cookie/令牌/敏感明细。  
背景：Codex 在已登录 Seller Lab 正常导航到沙特店铺后，观察到同一 `/api/v5/seller-lab/fbn-inventory` 接口，
站点通过请求头 `Country-Code: sa`（另有 `X-Locale: en-sa`）传递，而非 body 里的 `country_code`。

---

## 结论速览

| 项 | 结果 |
|---|---|
| 站点头 | ✅ **有效**。AE 头 → AUH/SHJ 仓（19 条），SA 头 → RUH 仓（20 条） |
| body 的 `country_code` | ❌ 无效（M12 观察成立），且**与头冲突时以头为准** |
| 无站点头 | 回退 AE（AUH/SHJ，19 条） |
| 未知站点头 `xx` | ⚠️ 回退到 **SA** 首屏（RUH，20 条）——参数校验缺失，需注意 |
| 分页 | ❌ `page` / `page_number` / `offset` / `current` 参数**全部无效**，始终返回第一页 |
| 权限隔离 | AE 只见 AE 仓、SA 只见 SA 仓，站点头层面隔离有效 |

**M12 修正**：M12 写"该接口无法读取沙特库存"**不准确**。正确结论是：
body 的 `country_code` 无效，但 `Country-Code: sa` 请求头**可以**读到真实 SA 库存（RUH 仓）。

---

## 一、站点头复验结果

用相同 body（`inventory_tab_name=all`、空 `filters`、分页），仅变更请求头：

```text
Country-Code: ae, X-Locale: en-ae  → 200，19 行，仓库前缀 [AUH, SHJ]，total_pages=1
Country-Code: sa, X-Locale: en-sa  → 200，20 行，仓库前缀 [RUH]，total_pages=2
无 Country-Code 头                → 200，19 行，仓库前缀 [AUH, SHJ]（回退 AE）
Country-Code: xx                  → 200，20 行，仓库前缀 [RUH]（回退 SA）
头=sa 且 body.country_code=ae     → 200，20 行，仓库前缀 [RUH]（以头为准）
```

- SA 返回 RUH 仓，与 Codex 网页导航观察到的一致；AE 返回 AUH/SHJ，与 M11/M12 一致。
- 与 M12 的 body 参数 19/19 完全相同形成对照：**站点由头决定，不是 body**。
- 未知码 `xx` 落到 SA 而非报错，说明服务端对 `Country-Code` 未做严格白名单校验；
  接入时必须在 ERP 侧自行限定 AE/SA，不能依赖平台拒绝非法值。

字段与页面样本一致：partner_sku / sku / warehouse_code / saleable / non_saleable / qty / title / image 等。

---

## 二、分页参数无效（新发现）

SA 首屏 `pagination` 显示 `items_per_page=20, page=1, total_pages=2`，但实际翻页参数都无效：

```text
page=1 / page=2 / page_number=2 / offset=20&limit=20 / current=2&size=20
→ 均 200，均 20 行，pagination.page 恒为 1
→ 五种 body 返回的 20 条与首屏集合完全相同（overlap=20）
```

**结论**：该接口虽报告 `total_pages=2`，但本轮测试的所有分页参数都未生效，
无法用它取到第二页。**不能据此宣称可完整遍历 FBN 库存**；未覆盖项需继续保留。

---

## 三、对既有结论的修正汇总

| 文档 | 原结论 | 修正 |
|---|---|---|
| M11 | FBN 库存 SA「未验证」 | M12 进一步发现 body 参数无效 |
| M12 | 该接口**无法读取沙特库存** | ❌ 不准确 → 用 `Country-Code: sa` 头**可以读取**真实 SA 库存（RUH 仓） |
| M12 | FBN 库存站点隔离不可用 | 站点头层面隔离有效；只是 body 参数无效、分页无效 |

M12 关于 ASN 与 Sales 的结论不受影响，保持不变。

---

## 四、局限

- 仍为非公开内部接口，正式支持、SLA、授权条款未确认；未接入 ERP。
- 未验证 `Country-Code` 与服务账号授权范围的严格关系（未知码回退 SA 说明校验宽松）。
- 分页无法翻页，全量库存遍历能力未验证。
- 未验证数据是否与 Seller Lab 页面逐字段一致（仅核对仓库前缀与首屏条数吻合）。
- 未输出 Cookie/令牌/SKU 明细；未修改任何平台数据。

请 Codex 复核。
