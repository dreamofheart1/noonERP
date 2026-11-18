# Noon ERP · 系统原型设计说明书 v1.2

> 配套 PRD：`docs/noon-erp-prd-v1.0.md`（v1.0.3 基线）
> 配套 DDL：`docs/noon-erp-schema-v0.4.sql`（45 表 + 4 视图，Codex 评审 6 项修订）
> 配套可点击原型：`prototype/index.html`（36 页全覆盖，可直接评审）
> 配套 Token：`prototype/figma-tokens.json`（W3C Design Tokens 格式，可导入 Figma Tokens 插件）
> 生成时间：2026-11-15 22:50 (GMT+8)；修订 2026-11-16 00:20、2026-11-17 22:10 (GMT+8)
> **v1.1 修订**：页头操作按钮改为按页面能力渲染，取消 36 页通用的「筛选 / 导出 / 新建单据」三件套，详见 §5.1。
> **v1.2 修订（Codex C-6）**：新增 §13 模拟边界与端到端内存联动；筛选真实过滤、详情按点击行取数、利润从联动结果计算；P-34 类别码以实测原始响应重写。Token 文件不变。

---

## 0. 这份交付是什么、怎么用在 Figma 里

| 交付物 | 作用 | 在 Figma 里怎么用 |
| --- | --- | --- |
| **本说明书** | 设计系统、Frame 骨架、组件清单、页面清单、连线表、开发标注 | 建文件的施工图，按 §4 先搭壳、按 §6 逐屏填 |
| `prototype/index.html` | 高保真可点击原型，36 页全部有真实字段与模拟数据 | 评审/走查用；也可逐屏截图贴进 Figma 做像素参考 |
| `prototype/figma-tokens.json` | 颜色 / 文字 / 间距 / 圆角 / 阴影 token | Figma Tokens 插件导入 → 一键生成 Color & Text Styles |

> **为什么不是直接给 `.fig` 文件**：当前会话没有连接可写入的 Figma 画布（已核查可用连接器，无 Figma 类），所以按 Figma 官方工作流把「可 1:1 复刻的规范 + 可直接点击的原型」都做实。你或设计师拿到这三份，在 Figma 里按 §10 的文件结构建页、导入 token、照 §4 搭 Auto Layout 骨架即可，不需要重新做信息架构。

**为什么先定 token**：颜色、字号、间距先统一成变量，后面 36 个页面改主色只改一处，不会出现「同一个按钮三种蓝」；这就是 Figma 里 Component + Style 的价值。

---

## 1. 设计原则（从 PRD 硬约束翻译过来的界面规则）

| # | PRD 约束 | 界面必须体现 | 违反后果 |
| --- | --- | --- | --- |
| D-1 | FBN 单模式 + 一店两站 | 全局站点切换 `全部 / AE / SA` 常驻顶栏；任何列表默认带 `site_code` 列 | 双站点数据混读 |
| D-2 | **四通道**（API/CSV/WEBHOOK/MANUAL） | 每条外部来源数据带 `SourceChannel` 徽标；集成页有「通道降级」开关 | 看不出数据是否可信 |
| D-3 | **FIFO 批次成本 + L2 费用池物理分离** | 批次页只显示 `unit_cost`（采购价）；费用池单独一页；利润页显式「L1 不含 L2」 | 成本重复计入 |
| D-4 | **15 天争议倒计时** | 工作台 + 周账单 + 差异清单三处都显示倒计时；≤3 天红色高亮，不可关闭 | 资金损失 |
| D-5 | **AI 只读、只写建议单** | AI 建议页的「通过/驳回」是**唯一**人工闸门；复核前 `matched` 显示「未改动」 | AI 越权改账 |
| D-6 | **`vat_mode=IGNORE` 毛利虚高** | 每张利润报表固定显示 VAT 风险条，无关闭按钮（FR-08.7） | 误读毛利 |
| D-7 | 三档口径并存 | 利润页 `NONE / PARTIAL / FULL` 分段控件，切换不改历史快照 | 口径被覆盖 |
| D-8 | 原始字段不删减 | 详情抽屉固定含「原始报文 raw_payload」折叠区 | 无法对账/提 Case |
| D-9 | 无审批流、无移动端、无刊登/广告/VAT 申报 | 界面**不出现**这些入口；预留项只以灰色 `Later` 标记 | 范围蔓延 |
| D-10 | 中国区习惯 | 涨/正向用红、跌/负向用绿；金额 ¥ / AED / SAR 明确标注币种，跨站点先归一 CNY | 数字误读 |

---

## 2. 画布与断点

| 项 | 规格 | 说明 |
| --- | --- | --- |
| 主 Frame | **1440 × 900**（Desktop / 1440） | 云端 Web 端、≤10 人、Chrome/Edge（NFR-02） |
| 内容安全宽 | 1440 − 侧栏 224 − 左右 padding 24×2 = **1168** | 表格最小可读宽 |
| 最小断点 | **1280 × 800** | 低于此宽侧栏收成图标条（56px），表格横向滚动 |
| 不做 | 移动端 / 平板（Q10 明确不用） | 响应式只到 1280，不建 375/768 Frame |
| 栅格 | **8pt**，12 列，gutter 16，margin 24 | 所有间距取 4/8/12/16/24/32 |
| 单位 | 所有尺寸整数、无半像素 | 导出 2x 切图 |

**Frame 命名**：`P-xx / 模块 / 页面名 / 状态`，例 `P-18 / 财务 / 周账单 / 倒计时≤3天`。
一个页面一个顶层 Frame；状态变体用 **Component Variant**，不复制整页。

---

## 3. 设计系统 Token

### 3.1 颜色（`Color/`）

| Token | 值 | 用途 |
| --- | --- | --- |
| `color/bg/app` | `#F5F7FA` | 应用底色 |
| `color/bg/surface` | `#FFFFFF` | 卡片、表格、抽屉 |
| `color/bg/subtle` | `#F8FAFC` | 表头、只读区、代码块 |
| `color/bg/sidebar` | `#111A2E` | 侧栏（深色，压住信息层级） |
| `color/border/default` | `#E4E8EF` | 分割线、输入框描边 |
| `color/border/strong` | `#CBD3E0` | 悬浮态、表格外框 |
| `color/text/primary` | `#1D2433` | 主文案、金额 |
| `color/text/secondary` | `#5A6478` | 次要文案、表头 |
| `color/text/muted` | `#8B95A7` | 占位、辅助说明 |
| `color/text/inverse` | `#FFFFFF` | 深底上的字 |
| `color/brand/primary` | `#2B5CE6` | 主按钮、选中态、链接 |
| `color/brand/primary-weak` | `#EEF3FF` | 选中行、弱底色 |
| `color/brand/accent` | `#FFC72C` | noon 黄，仅用于品牌标识与关键强调，**不做大面积底色** |
| `color/semantic/danger` | `#E5484D` | 逾期、亏损、DEAD、争议 ≤3 天 |
| `color/semantic/warning` | `#F5A524` | 预警、待复核、库龄超期 |
| `color/semantic/success` | `#16A34A` | 已闭合、MATCHED、SUCCESS |
| `color/semantic/info` | `#2B5CE6` | 进行中、IN_TRANSIT |
| `color/data/up` | `#D93025` | **涨 / 正向变化（红）** |
| `color/data/down` | `#0F9D58` | **跌 / 负向变化（绿）** |
| `color/tag/api` | `#EEF3FF` / `#2B5CE6` | SourceChannel = API |
| `color/tag/csv` | `#F1F5F9` / `#5A6478` | EXPORT / CSV |
| `color/tag/webhook` | `#F3EDFF` / `#7C4DFF` | WEBHOOK |
| `color/tag/manual` | `#FFF6E5` / `#B26B00` | MANUAL |

### 3.2 文字（`Type/`）

| Style | Font | Size / Line | Weight | 用途 |
| --- | --- | --- | --- | --- |
| `Display/24` | Inter / 思源黑体 | 24 / 32 | 600 | 页面 H1 |
| `Title/18` | 同上 | 18 / 26 | 600 | 卡片标题、抽屉标题 |
| `Body/14` | 同上 | 14 / 22 | 400 | 正文、表格单元格 |
| `Body/14-semibold` | 同上 | 14 / 22 | 600 | 表头、强调字段 |
| `Caption/12` | 同上 | 12 / 18 | 400 | 辅助说明、时间戳、徽标文字 |
| `Numeric/20` | **等宽数字** | 20 / 28 | 600 | KPI 数字（`font-variant-numeric: tabular-nums`） |
| `Numeric/14` | 等宽数字 | 14 / 22 | 500 | 金额列，右对齐 |
| `Code/12` | JetBrains Mono | 12 / 18 | 400 | `raw_payload` / `request_id` / 单号 |

> 阿语字段（EN/AR 名称）用 `dir="rtl"` 独立一行展示，**不与英文挤在同一行**；字体 fallback 到 Noto Sans Arabic。

### 3.3 间距 / 圆角 / 阴影 / 层级

| Token | 值 | 用途 |
| --- | --- | --- |
| `space/1..8` | 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 | 8pt 栅格 |
| `radius/sm` | 4 | 徽标、输入框 |
| `radius/md` | 8 | 按钮、卡片 |
| `radius/lg` | 12 | 抽屉、大卡片 |
| `elevation/1` | `0 1px 2px rgba(17,26,46,.06)` | 卡片 |
| `elevation/2` | `0 8px 24px rgba(17,26,46,.12)` | 抽屉、下拉 |
| `z` | base 0 / sticky 10 / sidebar 20 / drawer 40 / toast 60 / modal 80 | 抽屉盖表格、Toast 盖抽屉 |

---

## 4. Auto Layout 骨架（先搭这 6 个，后面所有页面都是它们的组合）

```
① AppShell / 1440×900   [Horizontal, gap 0, padding 0]
   ├─ Sidebar / 224×900  [Vertical, padding 16/12, gap 4]   ← Fixed
   └─ Main / 1216×900    [Vertical, padding 0, gap 0]       ← Fill container
        ├─ Topbar / h=56 [Horizontal, padding 0/24, gap 16, space-between]
        └─ Content / Fill [Vertical, padding 24, gap 16, scroll]

② PageHeader / Fill×72  [Vertical, gap 4]
   ├─ Breadcrumb (Caption/12)   └─ Row [H1 + 操作按钮组, space-between]
   └─ 操作组按 §5.1 能力矩阵渲染：最多 1 个主操作 + 可选「导出」；
      无操作页显示 Caption/11 只读原因；**不放「筛选」、不放空按钮组**

③ FilterBar / Fill×auto [Horizontal, wrap, gap 8, padding 12]
   站点徽标（只读，由顶栏切换）+ 预设组合 期间 / 状态 / 关键词 + [应用筛选] [重置]
   预设：doc / master / flow / lookup / none（§5.1）

④ KpiRow / Fill×104     [Horizontal, gap 16]  → 4 × KpiCard [Fill, Vertical, padding 16, gap 8]

⑤ DataTable / Fill×auto [Vertical, gap 0]
   ├─ TableToolbar (结果数 / 来源说明)  ← 不放「导出」，导出统一在 PageHeader
   ├─ HeaderRow / h=40 [Horizontal, 各列 Fill/Fixed]
   ├─ DataRow / h=48   [Hover / Selected / Danger 三种 Variant]
   └─ Pagination / h=56

⑥ DetailDrawer / 480×900 [Vertical, padding 24, gap 16]  ← 从右侧滑入
   ├─ 标题 + 状态徽标 + 关闭
   ├─ 状态机 Stepper（横向）
   ├─ 字段区（两列 Grid）
   ├─ 关联单据列表
   └─ 折叠区「原始报文 raw_payload」+「审计链」
```

**为什么全用 Auto Layout**：表格行数变化、抽屉文字换行、卡片数量增减时不会塌版；这是 Figma 里最省返工的一步（经验库已验证）。

---

## 5. 组件库清单（命名 = `类别/组件/变体`）

| # | 组件 | Variants | 备注 |
| --- | --- | --- | --- |
| C-01 | `Nav/SidebarGroup` | `expanded / collapsed` | 11 组，当前组高亮 |
| C-02 | `Nav/SidebarItem` | `default / hover / active / disabled(Later)` | 灰色 Later = FR-15 预留不做项 |
| C-03 | `Nav/SiteSwitch` | `ALL / AE / SA` | 分段控件，全局常驻 |
| C-04 | `Button/Primary·Secondary·Ghost·Danger` | `default/hover/loading/disabled` | h=32 / 36，radius 8 |
| C-05 | `Tag/Status` | 9 状态机 × 各自枚举（§8.1） | 颜色由语义 token 驱动 |
| C-06 | `Tag/SourceChannel` | `API / EXPORT / WEBHOOK / MANUAL` | 四通道必现 |
| C-07 | `Tag/Site` | `AE(AED) / SA(SAR)` | — |
| C-08 | `Data/KpiCard` | `normal / warning / danger` | 数字等宽、带环比 |
| C-09 | `Data/Table` | `default / empty / error / loading` | 金额列右对齐 |
| C-10 | `Data/CountdownBadge` | `>3d / ≤3d / expired` | **D-4 专用组件**，≤3d 强制红 |
| C-11 | `Feedback/AlertBar` | `info / warning / danger` | VAT 风险条用 warning 且**无关闭按钮** |
| C-12 | `Feedback/Toast` | `success / error` | 所有写操作结果 |
| C-13 | `Form/Input·Select·DatePicker·Switch·Checkbox` | `default/focus/error/disabled` | 必填 `*` |
| C-14 | `Overlay/DetailDrawer` | `480w` | 固定含 raw_payload 折叠区 |
| C-15 | `Overlay/ConfirmModal` | `danger / normal` | 状态机推进、AI 复核需二次确认 |
| C-16 | `Data/StatusStepper` | 9 套状态序列 | 当前态实心、已达态勾、未达态灰 |
| C-17 | `Chart/Bar·Line·Donut` | 纯 SVG | 淡色网格、无 3D、标注币种 |
| C-18 | `Data/SegmentControl` | `NONE / PARTIAL / FULL` | **D-7 利润口径专用** |
| C-19 | `AI/SuggestionCard` | `PENDING / APPROVED / REJECTED` | 含 confidence、证据字段、复核人 |
| C-20 | `Integration/ChannelSwitch` | `on / off / degraded` | 关闭 API 后显示「CSV 仍可用」 |
| C-21 | `Action/PageActions` | `primary+export / primary-only / export-only / readonly-note / blocked` | **v1.1 新增**，五种页头组合见 §5.1；主按钮文案按业务对象命名 |
| C-22 | `Form/FilterPreset` | `doc / master / flow / lookup / none` | **v1.1 新增**，站点徽标只读；站点筛选由顶栏统一控制 |

**命名规范**：`类别 / 组件名 / 状态`，主件放 `Components` 页，页面只放实例；禁止在页面里 Detach Instance。

### 5.1 页头操作按钮能力矩阵（v1.1 修订，评审重点）

**原问题**：v1.0 把 `PageHeader` 的 actions 做成固定三按钮模板，同时 `DataTable` 工具区默认带「导出」，于是 36 个页面 × 2 处重复出现「筛选 / 导出 / 新建单据」。语义上更严重：**工作台、FIFO 批次流水、API 日志、AI 复核页**这些只读派生数据也挂了「新建单据」，而订单、四态台账本就不允许手工新建；表格区还留着一个与顶栏重复、点了不生效的站点下拉。

**修订规则**（Figma 建稿与前端实现共用）：

| # | 规则 | 违反后果 |
| --- | --- | --- |
| R-1 | 页头**不放筛选**；筛选是表格区职责，站点由顶栏全局切换，FilterBar 里的站点只做只读徽标 | 两套站点筛选打架、出现假控件 |
| R-2 | 主操作最多 1 个，**文案必须写业务对象**（新建采购单 / 导入周账单 CSV / 运行补货计算），禁止泛化的「新建单据」 | 用户不知道会创建什么，误以为所有页都能建单 |
| R-3 | 只读 / 派生 / 由上游生成的页面**不给新建入口**，改显示 Caption/11 只读原因 | 看起来像漏做，或诱导手工改台账 |
| R-4 | 「导出」按页能力出现，且**全页只出现一次**（页头）；表格工具区不再各自放导出 | 一页多个导出、导出对象不明 |
| R-5 | 筛选字段按四类预设组合；字典 / 矩阵 / 参数页用 `lookup` 或 `none`，不再所有表都套「站点+期间+状态+关键词」 | 配置表出现无意义的「期间 / 状态」筛选 |
| R-6 | 主操作带 RBAC `data-only`；角色无权时隐藏，**页头按钮全部隐藏时**（如 P-28 切到 AGENT）补一条权限说明，仍有导出按钮则不补；权限未开通用 `blocked` 禁用态 + 原因（P-34 / O-07） | AI / 客服角色看到不可用的写入口，或页头留一块不明所以的空白 |

**筛选预设**：`doc` = 期间 + 状态 + 关键词；`master` = 状态 + 关键词；`flow` = 期间 + 关键词；`lookup` = 仅关键词；`none` = 不渲染筛选栏。四类都带只读徽标「站点：全部 / AE / SA · 顶栏切换」。

**36 页能力矩阵**（与 `prototype/index.html` 的 `PAGE_CAP` 常量逐行对应，改配置即可同步）：

| 页 | 页头主操作 | 导出 | 筛选 | 依据 / 只读原因 |
| --- | --- | --- | --- | --- |
| P-01 | — | — | flow | 概览看板：数据由下游单据汇总，只读 |
| P-02 | 新建 SKU | ✓ | master | 新建后 DRAFT，EN/AR 双语与 category_code 必填 |
| P-03 | 新增映射 | ✓ | master | partner_sku 与 sku_id 按站点一一对应 |
| P-04 | 新增供应商 | ✓ | master | 报价按 effective_from 版本化 |
| P-05 | — | — | lookup | 仓清单来自 ListWarehouses 只读回读（N-22） |
| P-06 | — | — | lookup | 系统参数行内编辑并留版本 |
| P-07 | 新建采购单 | ✓ | doc | DRAFT → CONFIRMED 校验明细与有效报价 |
| P-08 | 登记到货 | ✓ | doc | 收货生成 inv_batch，FIFO 按 received_at 排序 |
| P-09 | — | ✓ | flow | FIFO 批次与消耗流水由出入库驱动，禁止手工新增 |
| P-10 | — | ✓ | master | 四态台账由单据过账生成，不允许直接改数量 |
| P-11 | 新建调拨单 | ✓ | doc | 双写 inv_stock_move，跨站点不合并 |
| P-12 | 新建盘点单 | ✓ | doc | 盈亏生成调整流水并留审计链 |
| P-13 | — | ✓ | flow | 库存变动流水为只读审计视图 |
| P-14 | 新建 ASN | ✓ | doc | DRAFT 不可约 Slot，必须先 SEALED（N-09） |
| P-15 | — | ✓ | doc | 收货差异由 ASN 比对自动生成 |
| P-16 | 登记头程费用 | ✓ | doc | 费用进 L2 池，不摊入 SKU（Q8） |
| P-17 | 导入订单 CSV | ✓ | doc | 订单来自平台，只能导入不能新建；FR-13.2 一致性校验 |
| P-18 | 登记申诉 | ✓ | doc | 申诉窗口按场景覆盖，逾期不受理 |
| P-19 | — | ✓ | master | Offer 健康只读回读，本版不刊登不调价 |
| P-20 | 导入周账单 CSV | ✓ | doc | 导入后启动 15 天争议倒计时（FR-07.3） |
| P-21 | 导入仓储账单 CSV | ✓ | doc | 月度仓储账单与周账单分开核对 |
| P-22 | — | — | doc | 专属「导出差异清单」在下方，页头不重复 |
| P-23 | 登记回款 | ✓ | doc | 与 statement 勾稽，写 Bank Transaction ID |
| P-24 | 新增费率版本 | ✓ | lookup | 类目 × 站点 × 履约 × 生效日版本化 |
| P-25 | 登记 L2 费用 | ✓ | flow | 勾选 included_in_partial 决定 PARTIAL 口径 |
| P-26 | — | ✓ | flow | 页面内「全量重算」按 RBAC 授权，页头不重复 |
| P-27 | 运行补货计算 | ✓ | doc | 只生成 DRAFT 建议单，转单需人工复核 |
| P-28 | 新增大促系数 | — | lookup | 按站点生效，直接影响 suggested_qty |
| P-29 | — | ✓ | master | 预警由规则引擎生成 |
| P-30 | — | — | lookup | 报表中心为目录页，导出在各报表内执行 |
| P-31 | 立即同步 | ✓ | flow | 按四通道降级，失败进 int_sync_task |
| P-32 | — | ✓ | flow | API 日志为只读审计视图 |
| P-33 | — | ✓ | flow | Webhook 事件只读；无 FBN 订单事件（N-24） |
| P-34 | 新建导出任务（禁用） | ✓ | flow | O-07：只读 Service Account 下 CreateExport 未验证 |
| P-35 | — | — | none | 通道开关与凭据配置在下方卡片内 |
| P-36 | — | — | none | 用户 / 权限 / 审计 / 模板均在 Tab 内操作 |

**统计**：主操作按钮 19 页 + 禁用态 1 页（P-34）；仅导出 9 页；仅只读说明 7 页。页头筛选按钮 **0 处**，表格工具区导出 **0 处**，全站 `data-act="export"` 28 处 = 28 个具备导出能力的页面。

> **原型默认角色改为 ADMIN**：v1.0 默认 FINANCE，会让 19 个新建类按钮被 RBAC 隐藏，评审时看不到完整功能。切到 AGENT / SERVICE 可演示「按钮隐藏 + 权限说明」的降级效果。

---

## 6. 页面清单与 Frame 映射（36 页，对齐 PRD §4 FR-14）

| # | Frame | 菜单组 | 关键内容 | FR | 主要表 |
| --- | --- | --- | --- | --- | --- |
| P-01 | 工作台 · 概览 | 工作台 | 4 KPI、争议倒计时、DEAD 任务、AI 待复核、七环节闭环图 | FR-12 | `fin_statement` `int_sync_task` `ai_suggestion` |
| P-02 | SKU 档案 | 主数据 | EN/AR 双语、类目码、HS Code、品牌 | FR-02.1 | `md_sku` |
| P-03 | Partner SKU 映射 | 主数据 | 一 SKU 多站点映射、`offer_code` | FR-02.2 | `md_partner_sku` |
| P-04 | 供应商与报价 | 主数据 | 有效价历史、MOQ、交期 | FR-03.1 | `md_supplier` `md_sku_supplier` |
| P-05 | 仓与站点 | 主数据 | 1 店 2 站、仓 `site_code + fulfillment_mode` 创建后只读 | FR-01.1 | `md_store` `md_site` `md_warehouse` |
| P-06 | 系统参数 | 主数据 | 10 项 `sys_config`、版本化、按站点覆盖 | FR-01.2 | `sys_config` |
| P-07 | 采购单 | 采购 | 状态机、报价带出、`source_ref_no` | FR-03.2 | `pur_order(_item)` |
| P-08 | 到货入库 | 采购 | 合格量 → 生成批次、数量守恒 | FR-04.1 | `inv_inbound(_item)` |
| P-09 | FIFO 批次流水 | 采购 | 批次剩余量、消耗流水、COGS 溯源 | FR-04.2/04.3 | `inv_batch` `inv_batch_consumption` |
| P-10 | 四态台账 | 库存 | 可用/在途/锁定/不可售 + 守恒校验 | FR-04.4 | `inv_stock_ledger` |
| P-11 | 调拨 | 库存 | 多段在途、差异必须留原因 | FR-04.5 | `inv_transfer(_item)` |
| P-12 | 盘点 | 库存 | 盈亏调整单、按批次追补 | FR-04.5 | `inv_stocktake(_item)` |
| P-13 | 库存变动流水 | 库存 | `move_no`、来源单据、操作人、时间戳 | FR-04.7 | `inv_stock_move` |
| P-14 | ASN 登记 | 物流 | `DRAFT→SEALED→SLOT_BOOKED→…`，**SEALED 前禁填 slot** | FR-05.1 | `log_asn(_item)` |
| P-15 | 收货差异 | 物流 | `diff_qty`、异常记录、禁止静默关闭 | FR-05.2 | `log_asn_item` |
| P-16 | 头程费用 | 物流 | `allocation_flag=NONE`，不改 `unit_cost` | FR-05.3 | `log_shipment_cost` |
| P-17 | 订单 | 销售 | 四通道导入、`DELIVERED` 才扣批次、待补妥投清单 | FR-06.1~06.4 | `sal_order(_item)` |
| P-18 | 退货与申诉时效 | 销售 | A/B/C 分级、窗口/申诉截止、二次上架 | FR-06.5/06.6 | `sal_return` |
| P-19 | Offer 健康 | 销售 | `is_active` vs `live_status`、`offer_issues` 双站点 | FR-02.3 | `md_partner_sku` |
| P-20 | 周账单 `WEEKLY` | 财务 | **15 天倒计时**、逐行对账、回款差额 | FR-07.1~07.5 | `fin_statement(_line)` `fin_payout` |
| P-21 | 仓储账单 `STORAGE` | 财务 | 与周账单**分开核对**、库龄 >365 天 | FR-07.1 | `fin_statement` |
| P-22 | 差异清单 | 财务 | 未匹配行 `matched=0`、`raw_json`、导出 | FR-07.2/07.4 | `fin_statement_line` |
| P-23 | 回款登记 | 财务 | Bank Transaction ID、付款日汇率、COD 单列 | FR-07.5 | `fin_payout` |
| P-24 | 费用字典 | 财务 | 类目×站点×履约×生效日期、`PERCENT/FIXED/TIER` | FR-07.6 | `fin_fee_dict` |
| P-25 | L2 费用池 | 财务 | 头程/关税/清关/包材/汇兑/通道费、`included_in_partial` | FR-08.2 | `fin_expense_pool` |
| P-26 | 利润报表与重算 | 财务 | **三档口径**、VAT 风险条、公式版本、全量重算 | FR-08.1~08.7 | `fin_profit_snapshot` |
| P-27 | 补货建议 | 补货 | 指标逐项可查、`DRAFT→CONFIRMED→CONVERTED`、一键转单 | FR-09.1~09.5 | `rp_replenish_suggestion` |
| P-28 | 大促系数 | 补货 | `event_factor` 按站点，默认 1.0 | FR-09.1 | `sys_config` |
| P-29 | 预警 | 补货 | 断货/滞销/库龄超期 | FR-09.6 | `inv_stock_ledger` |
| P-30 | 报表中心 | 报表 | RPT-01~RPT-10 十个入口 | FR-12 | 全部 |
| P-31 | 同步任务 | 集成 | `PENDING→…→DEAD`、429 退避 | FR-10.4 | `int_sync_task` |
| P-32 | API 日志 | 集成 | `X-Request-Id`、状态码、latency、错误 envelope | FR-10.5/10.6 | `int_api_log` |
| P-33 | Webhook 事件 | 集成 | `event_id` 幂等、5 状态、3 种事件类型（N-24） | FR-10.7 | `int_webhook_event` |
| P-34 | 导出任务 | 集成 | impex 三步、12 个类别码（N-23）、`CreateExport` 权限提示 O-07 | FR-10.8 | `int_export_job` |
| P-35 | 环境配置 | 集成 | `env` / 生产沙箱共用凭据 / **四通道降级开关** | FR-10.2/10.3 | `md_partner_account` |
| P-36 | 系统管理 | 系统 | 用户 · 角色 · 审计日志 · 导入模板 · **AI 建议复核** | FR-01.3/01.4/FR-13/FR-11 | `sys_*` `ai_suggestion` |

> 36 页 = FR-14 的 11 组菜单全展开；「系统」组 5 个子页合并为 1 个带 Tab 的 Frame（P-36），避免文件膨胀。
> **无 Listing / 广告 / VAT 申报 / 审批流 / 移动端 Frame**：FR-15 只以灰色 `Later` 徽标提示预留。

---

## 7. Prototype 连线表（Figma Prototype 设置）

| 编号 | 触发 | 来源 | 目标 | 动画 | 对应业务规则 |
| --- | --- | --- | --- | --- | --- |
| I-01 | On click | 侧栏任意菜单项 | 对应 P-xx Frame | Instant | 导航 |
| I-02 | On click | 顶栏 `AE/SA/全部` | 同 Frame 的 Site Variant | Instant | D-1 站点过滤 |
| I-03 | On click | 表格任意行 | DetailDrawer Open | **Slide right 240ms ease-out** | D-8 原始报文 |
| I-04 | On click | 抽屉关闭 / 遮罩 | Close | Slide left 200ms | — |
| I-05 | On click | KPI「账单争议倒计时」 | P-20 | Instant | D-4 |
| I-06 | On click | KPI「AI 待复核」 | P-36 / AI Tab | Instant | D-5 |
| I-07 | On click | P-07 采购单「确认」 | 状态变体 `CONFIRMED` + Toast | Instant | §3.3 状态机 |
| I-08 | On click | P-14 ASN「约 Slot」（DRAFT 态） | **错误 Toast：SEALED 后才可约 slot** | Instant | FR-05.1 |
| I-09 | On click | P-14「封箱 SEALED」→「约 Slot」 | 状态变体推进 + `slot_date` 可编辑 | Instant | N-09 |
| I-10 | On change | P-26 三档口径分段控件 | 三套利润数值 Variant 切换 | Smart Animate 160ms | D-7，历史快照不变 |
| I-11 | On click | P-26「全量重算」 | ConfirmModal → 生成新 `formula_version` + Toast | Instant | FR-08.5 |
| I-12 | On click | P-36 AI 建议「通过 / 驳回」 | SuggestionCard 变体 + `reviewed_by/at` | Instant | D-5，复核前 `matched` 不变 |
| I-13 | On click | P-27「确认并转采购单」 | P-07 新草稿，带 `source_ref_no` | Instant | FR-09.5 |
| I-14 | On click | P-35「关闭 API 通道」 | 通道降级态 + 「CSV 仍可用」提示 | Instant | FR-10.3，UAT-01 步 10 |
| I-15 | On click | P-22「导出差异清单」 | 导出 Toast + 倒计时校验 | Instant | FR-07.4 |
| I-16 | On hover | 所有 `ℹ` 图标 | Tooltip（口径说明） | Dissolve 120ms | FR-08.6 |

**全局**：`While hovering` 侧栏项 → hover Variant；`On key/Enter` 全局搜索 → 结果下拉。
**不用** 拖拽、长按、计时自动跳转（ERP 是效率工具，动效只做位移与状态反馈）。

---

## 8. 状态、反馈与边界

### 8.1 九个状态机 → `Tag/Status` 变体（PRD §3.3）

| 对象 | 状态序列（Stepper 顺序） | 禁行规则（界面必须拦） |
| --- | --- | --- |
| 采购单 | DRAFT → CONFIRMED → PARTIAL_RECEIVED → RECEIVED → CLOSED | 无明细不可确认 |
| 入库单 | DRAFT → CONFIRMED → CLOSED | 合格量守恒 |
| 调拨单 | DRAFT → IN_TRANSIT → RECEIVED → CLOSED | `diff_qty≠0` 需异常记录 |
| ASN | DRAFT → SEALED → SLOT_BOOKED → IN_TRANSIT → RECEIVED | **SEALED 前禁填 slot_date** |
| 订单 | IMPORTED → DELIVERED → RETURNED / CANCELLED | 仅 DELIVERED 扣 FIFO |
| 账单 | PENDING → MATCHED / DISPUTED → CLOSED | 截止前不得自动 CLOSED |
| 补货建议 | DRAFT → CONFIRMED → CONVERTED | AI 只能写 DRAFT |
| AI 建议 | PENDING → APPROVED / REJECTED | 必须记 `reviewed_by/at` |
| 同步任务 | PENDING → RUNNING → SUCCESS / FAILED → DEAD | 429 指数退避 |

### 8.2 六类告警（NFR-15）在界面上的落点

| 告警 | 位置 | 视觉 |
| --- | --- | --- |
| 429 / 5xx | P-31、P-32 | 红色 Tag + 重试次数 |
| `PERMISSION_DENIED` | P-32 | 错误 envelope 展开 + 处置指引 |
| DEAD 任务 | P-01 KPI、P-31 | 红色数字，可点进详情 |
| 争议倒计时 ≤3 天 | P-01 / P-20 / P-22 | `CountdownBadge/danger`，**不可关闭** |
| 批次不足 | P-09、P-10 | 行内红色提示 |
| 库龄 >365 天 | P-21、P-29 | 橙色 Tag + FBN 仓储费跳涨说明 |

### 8.3 必须有的四种空/异常态

`DataTable` 四个 Variant 都要画：**empty**（无数据 + 引导按钮）、**loading**（骨架行）、**error**（重试）、**partial**（导入坏行进异常清单，成功行正常显示）。
> 特别地，P-17 订单导入与 P-20 账单导入的「坏行不丢弃」必须可见（FR-13.2）。

---

## 9. 给开发的标注表（Design → Code Handoff）

| 项 | 标注值 |
| --- | --- |
| 导出切图 | 图标 SVG 2x；`noon` 品牌标识 SVG，不用位图 |
| 侧栏 | 224px fixed，z=20；收起态 56px；当前项左侧 3px 主色条 |
| 顶栏 | 56px sticky，z=10；站点切换在右，搜索在中 |
| 内容区 | padding 24，gap 16，纵向滚动；表格头 sticky |
| 表格 | 行高 48，表头 40；金额右对齐等宽数字；状态列 Fixed 120 |
| 抽屉 | 480px，z=40，遮罩 `rgba(17,26,46,.35)`；表格行点击打开 |
| 金额 | 一律 `DECIMAL(18,4)` 落库，界面保留 2 位 + 币种前缀，**跨站点合计显示 CNY 归一值** |
| 时间 | 存储 GMT+8（NFR-11）；界面显示 `YYYY-MM-DD HH:mm`，站点本地时间仅作副行 |
| 币种 | AED / SAR / CNY / USD 明确标注；汇率缺失报错不用 1 兜底（FR-01.5） |
| 幂等键 | 列表「单号」列可复制；`statement_no` / `order_no+site_code` / `event_id` 重复导入给「已存在」提示 |
| 键盘 | `Ctrl/⌘+K` 全局搜索、`Esc` 关抽屉、`Enter` 提交查询 |
| 权限 | 按 §1.4 矩阵隐藏/禁用按钮；非财务角色看不到「利润重算」按钮（403 兜底） |
| 无障碍 | 对比度 ≥4.5:1；状态不能只靠颜色，必须带文字 |

---

## 10. Figma 文件结构（照这个建页）

```
[Root] Noon ERP · Prototype v1.0
├─ [Page] Cover                 封面、版本、评审指引
├─ [Page] Design System         Color Styles / Text Styles / Effect Styles
├─ [Page] Components            C-01~C-20 主件（含 Variant）
├─ [Page] Patterns              AppShell、FilterBar、KpiRow、DataTable、DetailDrawer
├─ [Page] Screens / 01 工作台     P-01
├─ [Page] Screens / 02 主数据     P-02~P-06
├─ [Page] Screens / 03 采购       P-07~P-09
├─ [Page] Screens / 04 库存       P-10~P-13
├─ [Page] Screens / 05 物流       P-14~P-16
├─ [Page] Screens / 06 销售       P-17~P-19
├─ [Page] Screens / 07 财务       P-20~P-26   (最复杂，先画)
├─ [Page] Screens / 08 补货       P-27~P-29
├─ [Page] Screens / 09 报表       P-30
├─ [Page] Screens / 10 集成       P-31~P-35
├─ [Page] Screens / 11 系统       P-36
├─ [Page] Flows                  七环节闭环图、AI 复核流、通道降级流
└─ [Page] Handoff                标注、切图、验收清单
```

**推荐建稿顺序**：`Design System → Components → Patterns → Screens/07 财务 → Screens/01 工作台 → 其余`。
理由：财务页把 `DataTable / CountdownBadge / SegmentControl / AlertBar` 全用上了，先啃最复杂的，后面复用率最高。

---

## 11. 评审验收清单

- [ ] 36 个 Frame 与 FR-14 菜单一一对应，无 Listing / 广告 / VAT 申报 / 审批流入口
- [ ] 所有外部数据行都有 `SourceChannel` 徽标（API / EXPORT / WEBHOOK / MANUAL）
- [ ] 站点切换在任意页面都能过滤 AE / SA
- [ ] 15 天倒计时在 P-01 / P-20 / P-22 三处同时可见，≤3 天红色
- [ ] 利润页三档口径可切，且每张利润页有不可关闭的 VAT 风险条
- [ ] AI 建议复核前，`matched` 显示「未改动」；通过后才有 `reviewed_by/at`
- [ ] ASN 在 DRAFT 态点「约 Slot」被拦截并给出原因
- [ ] 关闭 API 通道后，界面明确提示 CSV 仍可用
- [ ] 每个详情抽屉都有 `raw_payload` 折叠区与审计链
- [ ] 九个状态机的 Stepper 序列与 PRD §3.3 一致
- [ ] 金额列右对齐、带币种、跨站点合计为 CNY 归一值
- [ ] 所有组件用 Instance，页面内无 Detach
- [ ] **§5.1：任何页面页头都没有「筛选」按钮，也没有泛化的「新建单据」**
- [ ] **§5.1：只读 / 派生页无新建入口，且显示了具体只读原因（不是空白）**
- [ ] **§5.1：每个页面的「导出」最多出现一次，表格工具区无独立导出按钮**
- [ ] **§5.1：筛选字段与预设一致；站点在 FilterBar 里是只读徽标，不是第二个下拉**
- [ ] **§13：工作台有「原型模拟边界」提示；筛选真实生效、详情取点击行、利润从联动计算**
- [ ] **§13：P-01 可执行 8 步联动，批次血缘与红冲链可查；P-34 12 码仅「发现」层达成**
- [ ] **§5.1：切到 AGENT / SERVICE 角色，写入口按 RBAC 隐藏；当页头按钮全被隐藏时（如 P-28）补权限说明，不留空白；P-34 保持禁用态**

---

## 13. 模拟边界与端到端内存联动（v1.2 / Codex C-6）

**必须向评审者显式说明**：原型所有数据都在浏览器内存中模拟，**不调用 Noon API、不落数据库、不代表真实业务已完成**。按钮演示的是 PRD 规则与状态流转，不是真实执行结果。工作台顶部常驻「原型模拟边界」提示条，点击右上角 `PROTOTYPE v1.1` 可再次查看。

| 项 | v1.1（问题） | v1.2（修订后） |
| --- | --- | --- |
| 筛选 | 按钮只弹 toast，不改变结果 | 关键字 / 状态 / 期间真实过滤当前表格行，重置恢复 |
| 详情抽屉 | 任意行返回固定采购状态与固定字段 | 按点击行从 `S.rowRegistry` 取真实字段；演示血缘抽屉显示批次父子树与 COGS 红冲链 |
| 利润 | 写死 `l1=78210, pool=7716.58` | 从联动结果的 `DEMO` / `S.calcBatches` 计算；三档并存，重算生成新计算批次 |
| 端到端 | 无 | P-01 新增 8 步联动：确认采购 → 部分到货 → 调拨/ASN → 部分收货 → 妥投 → B 级退货红冲 → 对账 → 利润三档 |

**联动规则落点**：采购 100×¥10 → 父批 60 + 在途 40 → AE-FBN 收 38、短少 2 记 `TRANSFER_SHORT` 不进 COGS → 妥投 10 记 SALE ¥100 → 退货 2 件先红冲 −¥20、补偿 ¥6 后净损 ¥14 → 周账单 `STMT-DEMO-W47` 匹配 → 生成 3 个 `calc_batch_no`。与 PRD §FR-04.6/FR-04.8/FR-06.1b/FR-08.3/FR-08.5/FR-08.8 一一对应。

**P-34 类别码（Codex C-1）**：12 行严格取自 `docs/noon-erp-api-integration-v1.0.md` §3.2 原始响应，按「①发现 / ②创建权限 / ③文件列头」三层展示，当前仅 ① 达成 → 全部「不可用」；不存在的类别码不得出现在界面。

---

## 12. 变更记录

| 版本 | 时间 | 内容 |
| --- | --- | --- |
| v1.0 | 2026-11-15 22:50 | 首版：按 PRD v1.0.2 完成设计系统、36 页 Frame 映射、20 组件、16 条连线、9 状态机与开发标注；附可点击 HTML 原型与 token 文件 |
| **v1.2** | **2026-11-17 22:10** | **Codex 交叉评审修订**：新增 §13 模拟边界与端到端内存联动、真实筛选与行级详情、利润三档并存与计算批次；P-34 类别码三层验证重写；配套 PRD v1.0.3 / DDL v0.4 |
| **v1.1** | **2026-11-16 00:20** | **评审反馈修订**：取消 36 页通用的「筛选 / 导出 / 新建单据」三件套与表格工具区重复导出；新增 §5.1 页头操作能力矩阵（36 页逐页定义主操作 / 导出 / 筛选预设 / 只读原因）、6 条规则、4 类筛选预设、组件 C-21/C-22、5 条验收项；原型默认角色 FINANCE → ADMIN |

**文档结束 · PROTO-NOON-ERP-001 v1.2**
