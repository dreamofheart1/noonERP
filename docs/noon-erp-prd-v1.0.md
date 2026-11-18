# Noon 跨境电商 ERP — 产品需求文档（PRD）

| 项目 | 内容 |
| --- | --- |
| 文档编号 | `PRD-NOON-ERP-001` |
| 版本 | **v1.0.3 — Codex 交叉评审修订**（基于 v1.0.2；6 项交叉评审全部核实成立并修订，见 `docs/noon-erp-codex-review-v1.0.md`） |
| 生成时间 | 2026-11-13 17:00 (GMT+8)；修订 2026-11-13 20:22、21:26；**2026-11-17 22:00** |
| 状态 | **已评审 + M0 已联调 + 交叉评审已修订**：只读 Service Account 生产网关实调通过；剩余为开发执行与 O-07~O-11 验证项，见 §10 |
| 前置文档 | `docs/noon-erp-requirements-v0.1.md`（业务流程梳理）、`docs/noon-erp-requirements-v0.2.md`（范围收敛）、`docs/noon-erp-codex-review-v1.0.md`（6 项核实证词） |
| 配套设计 | **`docs/noon-erp-schema-v0.4.sql`**（MySQL 8，45 表 + 4 视图，Codex 评审 6 项修订；v0.3 为评审基线保持不变）；**`docs/noon-erp-api-integration-v1.0.md`**（M0 实测记录、完整原始响应、MCP 权限矩阵、字段映射） |
| 事实依据 | Noon 官方开发文档站 `noon-docs.noonpartners.dev`（123 页 sitemap）、已登录 Developer Portal、Service Account 凭据文件字段核对、**2026-11-13 21:20 生产网关 7 次只读实调 + 54 页 API Reference 的 `MCP access` 全量扫描**、v0.1 公开资料核查 |
| 架构前提 | Java SpringBoot **单体** · MySQL 8 单库 · 云端单机 · ≤10 人 · Web 端 · AI 外挂不侵入核心 |
| 事实纪律 | 事实带编号与核查时间；公开资料冲突项一律参数化；**未核到的后台字段不用推测补齐**；原始字段不删减 |
| 文件说明 | 文件名沿用 `noon-erp-prd-v1.0.md` 以保持引用稳定，**内部版本以本表 v1.0.3 为准** |

---

## 0. 一页速览

> **这是什么**：一套给「一个店铺 / UAE+KSA 双站点 / FBN 单履约模式」用的进销存 + 财务利润 ERP。主干是万墨 Wimoor 那条最值得抄的自动回流线：**采购到货 → FIFO 批次 → FBN 入仓 → 订单与周账单 → SKU 利润 → 补货建议 → 再驱动采购**。
>
> **不是什么**：不是刊登系统、不是广告投放系统、不是多平台 ERP、不是 VAT 申报系统、不是带审批流的 OA。

### 本版五条硬约束

| # | 约束 | 出处 |
| --- | --- | --- |
| 1 | **FBN 单模式**，只做一套佣金结算利润模型；`fulfillment_mode` 枚举保留但不建第二套引擎 | Q1 / §2.2 |
| 2 | **数据通道是四通道**：Noon 公开 API 覆盖商品/价格/库存/Offer/报表/事件，**但缺 FBN 入仓(ASN)、FBN 订单、Statement、广告** → 这四块必须走 Seller Lab 导出 / 手工登记 | N-08 / §2.5 |
| 3 | **FIFO 批次成本**与**L2 期间费用池物理分离**，头程/关税/包材/汇兑/通道费不进 `unit_cost` | Q5 + Q8 / FR-04、FR-08 |
| 4 | **周账单 15 天争议倒计时**是刚需，逾期即资金损失；月度 FBN 仓储账单必须与周账单分别核对 | F-10/F-11 / FR-07 |
| 5 | **AI 只走外挂**：只读视图 + 只写建议单（DRAFT），人工确认后由核心系统落单；官方 MCP 只授 Read，物理上无法改库存/价格 | Q12 已确认 / §8 |

### 一句话完成判定

> 一个 SKU 从「采购下单」→「FIFO 批次入库」→「ASN 入 FBN 仓」→「订单/账单导入」→「算出 L1 贡献毛利与 L1+L2 净利」→「生成补货建议」全链路跑通，**不再依赖 Excel 手工台账**；周账单能在 15 天争议期内出差异清单。

### 相对 v0.2 的变化

1. **Q12 已确认**：AI 客户端（WorkBuddy / Codex / Claude Code）作为外挂接入方式成立；用户同时采纳「首个业务场景 = 周账单差异核查」的建议 → 两条路径都落地（§8）。
2. **R2 已关闭**：Service Account 凭据已下载并完成字段核对（§2.6），并在 v1.0.2 完成只读角色的生产 Login/whoami 实调（N-21）。
3. 需求从「对齐底稿」升级为**完整 PRD**：新增角色权限矩阵、页面菜单、状态机、逐条功能验收标准、非功能需求、UAT 主用例、里程碑 DoD、DDL 待修订项。
4. 未继续浏览器核查（用户本轮转向 PRD），Seller Lab 后台字段未核到；**按 A-01 决议不阻塞开工**，仍未用推测补齐。
5. **v1.0.1 修订（三项评审决议按推荐落地）**：A-01 O-01 不阻塞开工（人工录入 + 账单反推）、A-02 授权修订 DDL 产出 `schema-v0.3.sql`（D-01~D-03 关闭）、A-03 生产/沙箱共用一份凭据用 `env` 区分。详见 §2.2、§6.4、§11 E。
6. **v1.0.2 修订（M0 生产实调 + A-04 只读权限）**：Service Account 设为只读后完成 Login/whoami/仓清单/导出类别/事件类型实调 → **R2~R5 关闭**；新增 N-21~N-25，修正「Statement 无 API」「MCP 可供 AI 取数」两处口径，明确官方 MCP 仅 11 个 Read 工具 / 0 个写工具；新增 A-04、O-07~O-11。完整实测数据见 `docs/noon-erp-api-integration-v1.0.md`。
7. **v1.0.3 修订（Codex 交叉评审 6 项）**：全部核实成立并修订；新增 `docs/noon-erp-codex-review-v1.0.md` 逐条证据；DDL 升 v0.4（45 表）；范围不变（FBN/双站点/FIFO/头程独立/AI 人工复核）

---

## 1. 产品定位

### 1.1 背景与痛点

| 痛点 | 现状 | 系统要解决什么 |
| --- | --- | --- |
| 数据分散 | Noon 后台、Excel 台账、1688 选品工具各自独立 | 建立唯一 SKU 主数据与 Partner SKU 映射，所有单据归一到 `sku_id + site_code` |
| 成本算不准 | 采购价、头程、关税混着记，无法回答「这个 SKU 到底赚多少」 | FIFO 批次成本 + L2 费用池分离，公式版本化、可全量重算 |
| 双站点没法比 | AED 与 SAR 直接相加，口径不一致 | 日汇率归一后出双站点对比视图 |
| 账单容易漏争议 | 每周三出 statement，15 天后逾期不受理 | 账单导入即生成倒计时与差异清单 |
| FBN 补货靠拍脑袋 | 在途、入仓周期、大促系数没进模型 | 按「站点 × 仓」出补货建议，人工确认才落单 |
| 接口不确定 | 平台 API 能力一半对一半 | `NoonAdapter` 四通道，任何模块可降级到 CSV 不停摆 |

### 1.2 产品目标

| 编号 | 目标 | 可度量结果 |
| --- | --- | --- |
| G-01 | 主干闭环系统化 | 采购→批次→入仓→订单→账单→利润→补货全链路在系统内闭环，无需 Excel 台账 |
| G-02 | 成本与利润可信 | 任一 SKU/站点/期间可查 L1、L2、合并三套口径，且能按 `formula_version` 重算复现 |
| G-03 | 资金不漏 | 每张周账单在 `dispute_deadline` 前产出差异清单，逾期账单数 = 0 |
| G-04 | 库存账实可追 | 每次库存变动有单据编号、操作人、时间戳与原始报文；四态台账可对账 |
| G-05 | AI 可控 | AI 输出全部落在建议单，人工复核留痕；AI 账号无法写任何业务表 |
| G-06 | 平台变化不伤核心 | 费率、时限、汇率、公式全部落 `sys_config` / `fin_fee_dict`，改配置不改代码 |

### 1.3 非目标（Non-goals，本版明确不做）

刊登与内容生成上架、广告投放与 ACOS 模块、VAT 申报与归集计算、审批流引擎、多平台适配（Amazon.ae / Namshi / 独立站）、移动端、Global/FBP 利润引擎、供应商绩效、绩效提成、选品爬取（保持独立工具）、历史订单迁移、平台账单争议的在线提交。

> 上述各项在模型里均留「口子」：`md_partner_sku` 映射、`AD_SPEND` 行类型、`vat_amount` 空字段、单据 `status` 状态机、`NoonAdapter` 单层适配、`fulfillment_mode` 枚举。后期启用不改现有表结构。

### 1.4 用户与角色

| 角色 | 人数级别 | 主要职责 | 数据范围 |
| --- | --- | --- | --- |
| 管理员 ADMIN | 1 | 用户/角色/参数/凭据引用/导入模板 | 全站点 |
| 运营 OPERATOR | 2–3 | SKU 主数据、Offer 健康、双站点对比、补货建议复核 | 全站点或按 `site_scope` |
| 采购 PURCHASE | 1–2 | 供应商、采购单、补货建议转采购 | 全站点 |
| 仓管 WAREHOUSE | 2–3 | 入库、调拨、盘点、ASN 登记、收货差异 | 按仓 + 站点 |
| 财务 FINANCE | 1–2 | 账单导入对账、费用字典、费用池、利润重算、回款 | 全站点 |
| 客服 SERVICE | 1 | 退货登记、窗口期与申诉截止跟踪 | 按站点 |
| AI 外挂 AGENT | 机器 | 读只读视图、写建议单 | **只读 + `ai_suggestion` 单表写** |

**权限矩阵**（V=查看 / C=新增 / U=修改 / X=复核导出 / —=无权）

| 功能域 | ADMIN | OPERATOR | PURCHASE | WAREHOUSE | FINANCE | SERVICE | AGENT |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 系统参数 / 用户角色 | V C U X | V | — | — | V | — | — |
| SKU / Partner SKU | V C U X | V C U X | V | V | V | V | V（只读视图） |
| 供应商 / 采购单 | V C U X | V | V C U X | V | V | — | — |
| 库存 / 批次 / 调拨 / 盘点 | V C U X | V | V | V C U X | V | V | — |
| ASN / 头程费用登记 | V C U X | V | V | V C U | V | — | — |
| 订单 / 退货 | V C U X | V | V | V | V C U | V C U | — |
| 账单 / 费用字典 / 利润重算 | V C U X | V | — | — | V C U X | — | — |
| 补货建议复核与转单 | V C U X | V C X | V C X | — | V | — | V（只读） |
| AI 建议单 | V C U X | V C X | V X | — | V X | — | **仅 C(DRAFT)** |

### 1.5 名词约定

全文统一：`sku_id`=本地 SKU；`partner_sku`=Noon Partner SKU；`site_code`=AE/SA；`ASN`=FBN 入仓货件；`L1`=SKU 贡献毛利；`L2`=期间与批次费用池；`COGS`=FIFO 批次消耗汇总；`statement`=平台结算账单。完整术语表见附录 A。

---

## 2. 范围基线

### 2.1 组织与业务边界

```
md_partner_account (PRJ468049, 1)
   └─ md_store (1)
        ├─ md_site AE (AED)
        │    ├─ LOCAL 国内仓
        │    ├─ TRANSIT 中转仓
        │    └─ FBN-AE 仓
        └─ md_site SA (SAR)
             ├─ TRANSIT 中转仓
             └─ FBN-SA 仓
```

- 履约模式：**仅 FBN**；所有仓创建时绑定 `site_code + fulfillment_mode`，**创建后不可变更**（F-07 / N-11 的仓库码约束）。
- 跨站点对比必须先把 AED/SAR 经 `fx_rate` 归一到 CNY，**不允许直接相加**。
- ⚠️ R9：Q2「一个店铺两站点」与公开资料「一照一店、两站账号独立数据不互通」口径冲突 → 模型保持 account/store/site 三层可配置，凭据可 1 对 1 也可 1 对多，待 Seller Lab 核实后再定死。

### 2.2 决议记录（Q1~Q14 + A-01~A-04 → 需求落地）

| 编号 | 答复 | PRD 落地位置 | 状态 |
| --- | --- | --- | --- |
| Q1 | **FBN** | FR-04–FR-08 单佣金模型；`fulfillment_mode` 枚举保留 | 已确认 |
| Q2 | 一个店铺、两个站点 | §2.1 组织树；`md_store`/`md_site`/`md_warehouse` | 已确认，R9 待核 |
| Q3 | egolite 已登录；开发文档站 `noon-docs.noonpartners.dev` | §2.3 事实基线；§7 集成；Portal 项目 `PRJ468049` | 已确认 |
| — | **Service Account 凭据已下载** | §2.6 字段核对；§7.4 密钥管理 | 本轮新增 |
| Q4 | 刊登不重要，后期再说 | 只保留 `md_partner_sku` 映射（FR-02），不建 Listing/内容表 | 已确认 |
| Q5 | **FIFO** | FR-04 FIFO 规则；`inv_batch` + `inv_batch_consumption` | 已确认 |
| Q6 | VAT 先不考虑 | `sys_config.vat_mode=IGNORE`；`vat_amount` 字段保留；风险 R7 | 已确认 |
| Q7 | 广告先不做 | 保留 `fin_statement_line.line_type='AD_SPEND'`，否则周账单净额对不平 | 已确认 |
| Q8 | 不摊、单独、部分合并 | FR-08 双层利润三档 `NONE/PARTIAL/FULL` | 已确认 |
| Q9 | 不扩平台 | 仅一层 `NoonAdapter`，不引入多平台抽象基类 | 已确认 |
| Q10 | 云端服务器、≤10 人、无移动端 | §5 非功能、§9.4 上线检查 | 已确认 |
| Q11 | 无审批流 | 单据 `status` 状态机 + `sys_audit_log`；后期新增 `sys_approval_flow` 不改现有表 | 已确认 |
| Q12 | WorkBuddy / Codex 之类；**建议也支持** | §8：AI 客户端外挂（官方 MCP 只授 Read）+ 首个场景「周账单差异核查」 | **本轮已确认** |
| Q13 | 数据在 Noon 后台（egolite 已登录） | FR-13 导入模板；只迁主数据 + 期初批次，不迁历史订单 | 已确认 |
| Q14 | 选品独立，不进 ERP | ERP 只接收「已开发完成的 SKU」 | 已确认 |
| **A-01** | O-01 不现在补核，按推荐值走 | Seller Lab 导出列头 / 仓编码 / 费率表**不阻塞 M0~M2**：字段映射先用人工录入 + 账单反推；导入模板按可配置列头设计，拿到样本后只改映射配置不改表 | ✅ 已决议 |
| **A-02** | 授权修订 D-01~D-03 | 产出 `docs/noon-erp-schema-v0.3.sql` 作为**当前开发依据**；v0.2 保留为历史基线不覆盖 | ✅ 已决议 |
| **A-03** | 生产/沙箱共用同一份 `.json` 凭据 | 由 `md_partner_account.env` + `base_url`/`sandbox_url` 区分；凭据本身不带环境标识 | ✅ 已决议 |
| **A-04** | **Service Account 角色设为只读**（用户 2026-11-13 21:18） | MVP API 通道全为读操作 → 只读够用且更安全；与 AI 外挂的 MCP OAuth 授权**互不干扰**；后期刊登/库存回写/调价需按 N-16 改该 account 角色（每 partner 仅 1 个 service account）；`CreateExport` 权限见 O-07 | ✅ 已决议并实调验证 |

> A-01~A-03 是 2026-11-13 20:22 用户「问题按照推荐的走」的落地记录；A-04 是 21:18 用户「账号权限改成只能读」的落地记录，已完成 M0 生产实调。O-03~O-06 仍按 §10.2 默认值执行，O-07~O-11 为联调后新增验证项。

### 2.3 平台事实基线（引用编号，不重复论证）

| 组 | 编号 | 要点 | 对需求的约束 |
| --- | --- | --- | --- |
| v0.1 已核 | F-01~F-06、F-09~F-19 | 万墨模块清单、三种履约模式、FBPI 流程、周三 statement / 次周周四付款 / **15 天争议**、月度仓储账单独立、费率不含 VAT、UAE 基准定价、英阿双语、24h SLA、大促节奏、合规字段、COD | 见 §4 各模块「规则」列；费率/时限全部参数化 |
| v0.1 已核 | C-01~C-07 | VAT 是否必需、打款门槛 100/1000、退货窗口 14/15、佣金区间冲突、FBP 定义混用、API 范围、COD/通道费 | §2.4 全部落 `sys_config`，不写死 |
| **v0.1 作废** | F-07 / F-08 | 「商品创建不支持 API」「无公开开发者门户」 | ❌ 由 N-01/N-15 取代。文档站公开、Catalog 有 `SkuGenerate`、Content 有 `UpsertProduct`；本版因 Q4 不使用 |
| 浏览器一手 | N-01~N-06 | 123 页文档站、Portal 已登录（`yixin` / `PRJ468049`）、生产/沙箱网关、JWT+cookie 鉴权、per-project 双计数限流、错误 envelope、Request Logs 保留 14 天 | §7 集成设计 |
| 浏览器一手 | N-07~N-09 | 公开端点 15 域；**缺 FBN Inbound/ASN、FBN 订单、Statement、广告**；术语表却明写 ASN 通过 FBN Inbound API 创建且 seal 后才能约 slot | §2.5 通道矩阵；R1 |
| 浏览器一手 | N-10~N-15 | Offer 跨国家读取（双站点对比）、Stock API 为**绝对值 set**、reserved 由平台扣、impex 三步异步导出、Event Notifications ≤5 destination、官方 MCP Read/Change 权限分离 | FR-02/FR-10/§8 |
| **本轮新增** | N-16 | Getting Credentials：每个 partner 从 seller portal 可建 **1 个 service account**、每个最多 **5 个 key**；key 可即时停用；会话有效期 **30 天**；可选 IP 白名单与 key 过期时间，不设则**全 IP 可用且永久有效**；service account 需分配角色 | §7.4 密钥管理、R2 |
| **本轮新增** | N-17 | `iat` 必须为**整秒** Unix 时间，与服务器时钟偏差 >5 分钟即拒绝；**每次 login 生成新 JWT**；所有请求必带 `User-Agent` | §7.1、FR-10.1 |
| **本轮新增** | N-18 | Quick Start 官方警告：credential `.json` **绝不提交源码仓库** | §7.4、`.gitignore` |
| **本轮新增** | N-19 | `channel_identifier` 由 API User Service 代替 `user_code` 使用，支持编程化创建/轮换凭据 | §7.4 轮换策略 |
| **本轮新增** | N-20 | 凭据文件 6 字段齐全，与 Portal 项目码一致；私钥为未加密 PKCS#8 RSA 2048 | §2.6 |
| **M0 实调** | N-21 | 只读角色 Login / whoami 均 200；`Set-Cookie` 为 `_npsid` + `_nprtnetid`；`x-request-id` 格式 `req_000_<32hex>`；**生产 login 的 cookie 可跨 host 用于沙箱**，但沙箱是 deterministic mock 且**跳过权限校验**，不能用来验证权限或取真实字典 | §7.1、§7.4、A-03 |
| **M0 实调** | N-22 | `ListWarehouses` 返回全量 5 仓，`fulfillment_system_code` 仅 `directship_v2`(4) / `fbpi`(1)，**无 FBN 仓类型**；编码后缀 `AE`，未见 KSA 仓 | R3 关闭、§7.2 |
| **M0 实调** | N-23 | `GetExportCategoryList` 共 12 类，含 FBN 库存明细/台账/库龄、**订单导出（可选 `is_fulfilled_by_noon`）**、item 级交易明细、发票与贷项通知单 → 「无实时 REST 端点」≠「无报表通道」 | R4 关闭、§2.5、§7.5 |
| **M0 实调** | N-24 | `ListEventTypes` 仅 3 种（`FBPI::ORDER_SYNC` / `FBPO::PO_SYNC` / `RETURNS::REFERENCE_UPDATE`），**无 FBN 订单事件**；`destinations=[]` | R5 关闭、§2.5 |
| **M0 全量扫描** | N-25 | 54 个 API Reference 端点中 **11 个 Read / 0 个 Change / 43 个 Not on MCP**；平台级零写工具；`GetProductOffers`/impex/whoami/Returns/FBPI 订单全不在 MCP | §8 AI 外挂边界修正 |

> 核查时间：v0.1 事实 2026-11-12 22:48 GMT+8；浏览器一手事实 2026-11-12 23:47 GMT+8；凭据核对 2026-11-13 17:00 GMT+8；**M0 生产实调与 MCP 全量扫描 2026-11-13 21:20–21:26 GMT+8**。平台规则年内会变（费率表 2026 已调整），一律配置化并保留历史版本。完整原始响应见 `docs/noon-erp-api-integration-v1.0.md`。

### 2.4 冲突项参数化（初始化参数 = `sys_config` 初始值）

| `config_key` | 初值 | 来源 | 说明 |
| --- | --- | --- | --- |
| `cost_method` | `FIFO` | Q5 | 批次成本 |
| `vat_mode` | `IGNORE` | Q6 | 不做 VAT；毛利虚高风险 R7 |
| `allocation_policy`（维度A） | `NONE` | Q8 | 站点层 L2 合并范围 `NONE`/`PARTIAL`/`FULL`；与是否摊到 SKU 无关 |
| `sku_allocation_enabled`（维度B） | `0` | 2026-11-18 决议 | SKU 分摊独立开关，默认关闭；1=启用后按 `profit_allocation_basis` 分摊，ΣSKU=SITE |
| `profit_allocation_basis` | `REVENUE_SHARE` | 2026-11-18 决议 | 维度B方法参数（非开关）：收入占比 / 件数 / 平均 |
| `return_window_days` | `15` | C-03 | 14/15 冲突取宽口径 |
| `appeal_window_days` | `14` | C-03 | Directship 7 天需按场景覆盖 |
| `statement_dispute_days` | `15` | F-10 | 逾期争议不受理 |
| `payout_threshold` | `1000` | C-02 | 100/1000 冲突，按站点可覆盖 |
| `return_restock_cost` | `ORIGINAL` | Q5 | 退货二次上架批次成本口径 |
| `stock_update_mode` | `ABSOLUTE` | N-11 | 绝对值 set，禁止增量同步 |
| `api_max_destinations` | `5` | N-13 | webhook destination 上限 |

> `sys_config` 唯一键含 `effective_from`，参数本身有历史版本，改配置不影响已生成的利润快照（快照带 `formula_version`）。

### 2.5 数据通道矩阵（**本版最关键的现实约束**）

| 数据 / 动作 | 主通道 | 依据 | MVP | 备选降级 |
| --- | --- | --- | --- | --- |
| SKU / Partner SKU 映射 | 一次性 CSV 导入 | Q4 | ✅ | 手工录入 |
| Offer 健康 / 双站点对比 | `GetProductOffers` API（**Not on MCP，ERP 直连**） | N-10 / N-25 | ✅ | Seller Lab 导出 |
| 商品价格 / 激活 | Pricing API | N-07 / A-04 | ❌ 暂缓；只读角色下不写 | 手工 |
| 自有仓库存推送 | Stock API（绝对值） | N-11 / **N-22** | ❌ **不推送**：实调 5 仓全为 directship/fbpi，**无 FBN 仓类型**（R3 关闭） | — |
| 退货参考号 | `ListReturnReferences` API（Not on MCP） | N-07 / N-25 | ✅ | 账单退货行 |
| 事件订阅 | Event Notifications webhook（≤5） | N-13 / **N-24** | ⚠️ **仅退货辅助**：实调 3 种事件，无 FBN 订单事件 | 轮询 / 导出 |
| 报表异步导出 | impex `CreateExport` 三步 | N-12 / **N-23** | ⚠️ 12 个类别码已实测；**`CreateExport` 在只读角色下未验证（O-07）** | Seller Lab CSV / 手工 |
| **FBN 入仓 ASN** | **Seller Lab 手工登记** | N-08/N-09 | ✅ | 提 Support Case 索取 FBN Inbound API |
| **FBN 订单** | **Seller Lab / impex 导出 CSV** | N-08 / **N-23** | ✅ `noon_noonoms_ordersexport` 可按 `is_fulfilled_by_noon` 筛 | 手工登记 |
| **周 statement / 月度仓储账单** | **Seller Lab 导出 CSV** | N-08 / **N-23** | ✅ | impex `noon_financeweb_transactionviewreportonitemlevel` / `invoicesandcreditnotes`（列头待验证 O-07） |
| 头程/关税/包材费用 | 手工登记 | Q8 | ✅ | 费用导入模板 |
| AI 外挂取数 | **ERP `v_ai_*` 视图为主** + 官方 MCP 11 个 Read 工具补充 | **N-25** | ✅ MCP 仅能补类目/内容/价格/库存/仓/事件类型 | 只读 API 直连 |

> **一句话**：`NoonAdapter` 的 API / CSV / WEBHOOK / 手工四通道不是过度设计，是 FBN 的现实。M0 生产实调进一步确认：无实时订单/结算 REST 端点、无 FBN 仓类型、无 FBN 订单事件；但 impex 有 12 类报表（含 FBN 库存台账、订单、item 级交易），可作为 **CSV 通道的 `EXPORT_API` 自动化升级**，不改变 MVP 降级设计。任何模块都必须能在 API 不可用时切到 CSV，不影响业务闭环。

### 2.6 本轮 Service Account 凭据字段核对结论

**核对对象**：`/Users/dream/Downloads/noon_credentials_sensitive.json`（仅核对字段结构，**私钥内容不进入任何交付文档**）。

| 字段 | 观测值（脱敏） | 官方定义 / 用途 | 核对 |
| --- | --- | --- | --- |
| `key_id` | `noon-partners-key-id-…`（UUID 后缀，53 字符） | JWT `sub` / key 标识 | ✅ 格式正常 |
| `private_key` | PKCS#8 PEM 私钥头（未加密），共 28 行 | RS256 签名私钥；openssl 解析通过，**未加密 PKCS#8 / RSA 2048** | ✅ 可用，但**未加密** |
| `channel_identifier` | `test@p468049.idp.noon.partners` | API User Service 中代替 `user_code` 标识 service account（N-19）；`test` 是创建时填的 **username，不是环境标识** | ✅ 格式正常 |
| `project_code` | `PRJ468049` | 登录 body `default_project_code` | ✅ 与 Portal `?project=PRJ468049` 完全一致 |
| `type` | `apijwt` | 认证类型，对应 RS256 JWT 换 session cookie | ✅ 一致 |
| `issued_at` | `2026-11-13T08:52:02Z`（北京时间 16:52） | 签发时间；刚创建 | ✅ 时间合理 |

**结论**：字段齐全、结构合法、项目归属正确 → **R2 已在 2026-11-13 21:20 的生产实调中关闭**：只读角色 Login 200 / whoami 200，`x-request-id` 正常返回，`whoami.username` 与 `channel_identifier` 一致。完整实测见 `docs/noon-erp-api-integration-v1.0.md` §2。剩余仅文件权限 `600`、IP 白名单与 key 过期（R10，需用户手动处理）。

**安全处置（本轮已做 / 需你做）**

| 项 | 状态 |
| --- | --- |
| 私钥内容不写入任何交付文档、不入库、不进 Git | ✅ 已遵守；`md_partner_account.credential_ref` 只存路径引用 |
| 项目根新增 `.gitignore`（屏蔽 `*credentials*.json`、`*.pem`、`.env*` 等） | ✅ 已创建 `/.gitignore` |
| 文件当前权限 `0644`（同机其他用户可读），建议改为 `600` 并移出 Downloads 到服务端受控目录 | ⚠️ **需你确认后执行**，我未改动个人目录中的原文件 |
| 建议设置 IP 白名单 + key 过期时间（默认全 IP 可用、永不过期，N-16） | ⚠️ 待 Portal 配置 |
| 确认 service account 已分配角色，否则 `PERMISSION_DENIED`（N-06） | ⚠️ 待 Portal 确认 |
| 泄漏处置：Portal 立即 deactivate key（即时生效），再用 API User Service 轮换（最多 5 keys） | 应急预案 |
| **生产 / 沙箱共用同一份凭据**（A-03），只由 `env` 与网关 host 区分；`credential_ref` 一条记录即可 | ✅ 已决议，写入 `schema-v0.3.sql` 文件头 |

---

## 3. 业务流程

### 3.1 FBN 单模式闭环（七环节）

```
① 主数据/映射 ─→ ② 采购 ─→ ③ FIFO 批次入库 ─→ ④ ASN 入 FBN 仓
                    ↑                                  ↓
   ⑦ 补货建议 ← ⑥ SKU 利润(L1+L2) ← ⑤ 订单/退货/周账单对账 ─┘
```

> 与 v0.1 九环节相比：**选品/刊登/定价**三个环节移出系统边界（Q4/Q14/非目标），FBN 由平台履约故不建发货 SLA 看板，主干压缩为七环节。

### 3.2 主流程分步

| # | 环节 | 触发 | 主责 | 输入 | 输出单据 | 系统动作 | 主要表 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ① | 主数据与映射 | 新品开发完成 | 运营 | SKU 档案、Seller Lab Partner SKU 清单 | SKU、供应商、Partner SKU 映射 | 校验唯一性、双语字段、类目码 | `md_*` |
| ② | 采购 | 补货建议转单 / 新品首单 | 采购 | 报价、MOQ、交期 | 采购单 `DRAFT→CONFIRMED` | 按 `md_sku_supplier` 带出有效报价，记录 `source_ref_no` | `pur_*` |
| ③ | 到货入库 | 采购到货质检 | 仓管 | 到货数量、合格量、采购价 | 入库单 + **FIFO 批次** | 生成 `inv_batch`、写四态台账与变动流水 | `inv_*` |
| ④ | 头程 / ASN 入仓 | 备货完成 | 物流/仓管 | 批次、箱单、FBN 仓码 | 调拨单、ASN、头程费用单 | ASN 状态推进，**seal 后才允许约 slot**；收货差异回写 | `log_*` |
| ⑤ | 订单 / 退货 / 账单 | 订单妥投、每周三、次月第二周 | 财务/客服 | 订单 CSV、周 statement、月度仓储 statement | 订单、退货单、结算单、差异清单 | 逐行对账、**生成 15 天争议倒计时**、FIFO 出库扣批次 | `sal_*` `fin_*` |
| ⑥ | 利润核算 | 账单核对完成 / 手动触发重算 | 财务 | 订单收入、账单费用、批次消耗、L2 费用池 | `fin_profit_snapshot` | 按 `formula_version` 计算 L1/L2/合并口径 | `fin_profit_snapshot` |
| ⑦ | 补货建议 | 定时 / 手动 | 运营 | 销量、在途、库存、交期、大促系数 | 补货建议单（DRAFT） | 计算 `suggested_qty`，**人工确认**后转采购/调拨/ASN | `rp_replenish_suggestion` |

### 3.3 关键状态机

| 对象 | 状态与迁移 | 前置校验 |
| --- | --- | --- |
| 采购单 `pur_order` | `DRAFT → CONFIRMED → PARTIAL_RECEIVED → RECEIVED → CLOSED` | CONFIRMED 需至少 1 行且供应商有效；CLOSED 需全部到货且已结算 |
| 入库单 `inv_inbound` | `DRAFT → CONFIRMED → CLOSED` | CONFIRMED 时按合格量生成 `inv_batch`，数量守恒校验 |
| 调拨单 `inv_transfer` | `DRAFT → IN_TRANSIT → RECEIVED → CLOSED` | 收货差异 `diff_qty ≠ 0` 必须生成异常记录才可 CLOSED |
| ASN `log_asn` | `DRAFT → SEALED → SLOT_BOOKED → IN_TRANSIT → RECEIVED` | **`SEALED` 后才可以填 `slot_date`**（N-09）；RECEIVED 前必须回填 `qty_received` |
| 订单 `sal_order` | `IMPORTED → DELIVERED → RETURNED / CANCELLED` | 仅 `DELIVERED` 触发 FIFO 批次扣减与 COGS 归集 |
| 账单 `fin_statement` | `PENDING → MATCHED / DISPUTED → CLOSED` | CLOSED 需差异清单已处理；`dispute_deadline` 前不得自动 CLOSED |
| 补货建议 `rp_replenish_suggestion` | `DRAFT → CONFIRMED → CONVERTED` | **AI 只能写 DRAFT**；CONVERTED 必须写 `converted_doc_type/no` |
| AI 建议 `ai_suggestion` | `PENDING → APPROVED / REJECTED` | APPROVED 必须记录 `reviewed_by/at`；APPROVED 后由核心系统生成业务单，AI 不参与 |
| 同步任务 `int_sync_task` | `PENDING → RUNNING → SUCCESS / FAILED → DEAD` | 429 指数退避；超过重试上限转 DEAD 并告警 |

### 3.4 异常与补偿流程

| 场景 | 处理规则 |
| --- | --- |
| ASN 收货数量差异 | `diff_qty` 回写 `log_asn_item`，生成库存调整单与 `inv_stock_move`，差异原因留 `raw_payload`；不允许静默覆盖 |
| 退货二次上架 | 生成**新 FIFO 批次插队尾**，成本按 `return_restock_cost` 配置；不回流原批次 |
| 账单行匹配不上 | 进差异清单，标 `matched=0`，保留 `raw_json` 原始行；不做自动猜测归因 |
| 账单重复导入 | `statement_no` 唯一键拒绝，返回「已存在」并给出原记录编号与时间 |
| API 429 / 5xx | 指数退避重试，`int_api_log` 记录 `X-Request-Id`；超上限转 DEAD 并告警，业务可切 CSV 通道 |
| webhook 重复事件 | `event_id` 唯一键幂等，重复直接 ACK 不重复处理 |
| 费率变更 / 公式改动 | 新增 `fin_fee_dict` 版本或 `formula_version`，**不覆盖历史**；利润快照可全量重算并保留新旧两套结果 |
| 利润口径争议 | 三档 `NONE/PARTIAL/FULL` 并存互不覆盖，报表显式标注口径与 `formula_version` |

---

## 4. 功能需求（FR）

**优先级**：P0=MVP 必备 / P1=二期 / 延后=非目标但留口子。
**验收标准**均为可测条件；「对应表」为本功能的主要落库对象。

### FR-01 基础配置与权限（P0）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-01.1 | 组织主数据 | 1 partner / 1 store / 2 sites / 仓库清单 | 仓创建时绑定 `site_code + fulfillment_mode`，**创建后不允许修改这两个字段** | `md_*` 主数据 | 尝试修改仓的站点/模式被拒并记审计日志 | `md_partner_account` `md_store` `md_site` `md_warehouse` |
| FR-01.2 | 系统参数 | `sys_config` 10 项初值 + 后续改值 | 参数按 `config_key + scope_key + effective_from` 版本化；支持按 `site_code` 覆盖 | 参数生效 | 改 VAT/退货窗口/争议天数后，新单据按新值计算、旧快照不变 | `sys_config` |
| FR-01.3 | 用户与 RBAC | 用户 / 角色 / 站点范围 | ≤10 人；`site_scope` 控制可见站点；密码散列存储 | 登录与鉴权 | 用非财务账号访问利润重算接口返回 403 | `sys_user` `sys_role` `sys_user_role` |
| FR-01.4 | 审计日志 | 所有写操作 | 每次写生成 `log_no` + 操作人 + `before_json/after_json` + 时间戳 | 可回溯日志 | 任一单据能列出完整变更链 | `sys_audit_log` |
| FR-01.5 | 币种与汇率 | 日汇率 AED/SAR/CNY/USD | 跨站点对比先归一到 CNY；汇率按 `rate_date` 取，缺失时报错而非用 1 兜底 | `fx_rate` | 双站点销售额合计 ≠ AED+SAR 直接相加 | `fx_rate` |
| FR-01.6 | 单据编号规则 | 前缀 + `YYYYMMDD` + 序列 | 全局唯一、可读、带创建时间戳；禁止复用 | 各类单号 | 并发导入 1000 单无重号 | 全部单据表 |

### FR-02 商品主数据与 Partner SKU 映射（P0）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-02.1 | 本地 SKU 档案 | SKU 编码、EN/AR 名称、类目码、HS Code、原产地、品牌、规格 JSON | `sku_code` 唯一；`category_code` 必须可关联费率字典；双语字段保留但 AR 可空 | `md_sku` | 缺类目码的 SKU 在利润计算时被拦截并提示 | `md_sku` |
| FR-02.2 | Partner SKU 映射 | `partner_sku + site_code + business_model` | 唯一键约束；一个本地 SKU 可映射多站点多条；`offer_code` 可空 | `md_partner_sku` | 重复映射被拒；订单导入能按 partner_sku 回查 `sku_id` | `md_partner_sku` |
| FR-02.3 | Offer 健康回读 | `GetProductOffers` API | 按站点取 `live_status` / price / stock / `offer_issues`；区分 `is_active`（定价激活）与 `live_status`（前台可见） | 双站点对比视图 | 「我设了价却不展示」能在 `offer_issues` 中定位 | `md_partner_sku` |
| FR-02.4 | 不做刊登 | — | 不建 Listing/内容表、不调用 `UpsertProduct`；上架在 Seller Lab 人工完成 | 仅映射 | 系统内不存在刊登入口 | — |

### FR-03 供应商与采购（P0）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-03.1 | 供应商与报价 | 供应商、SKU-供应商、报价、MOQ、交期 | 报价按 `effective_from/to` 留历史价；采购单取当前有效报价 | `md_supplier` `md_sku_supplier` | 改价后新采购单用新价、旧单不变 | `md_supplier` `md_sku_supplier` |
| FR-03.2 | 采购单 | 供应商、SKU、数量、单价、币种、汇率 | 状态机按 §3.3；`source` 可为 `MANUAL` / `REPLENISH_SUGGESTION` / `AI_SUGGESTION`；**无审批流** | `pur_order(_item)` | 由补货建议转单时 `source_ref_no` 可回溯 | `pur_order` `pur_order_item` |
| FR-03.3 | 采购成本口径 | 采购单价、汇率 | `unit_cost` 只含采购价，**不含头程/关税/包材**（Q8）；外币按 `fx_rate` 换算 | `inv_batch.unit_cost` | 批次成本与费用池金额不重复计入 | `inv_batch` |

### FR-04 库存与 FIFO 批次成本（P0，★核心）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-04.1 | 到货生成批次 | 入库单合格量、采购价、到货时间、仓 | 每次到货生成一条 `inv_batch`（`qty_in` / `qty_remaining` / `unit_cost` / `received_at`）；`source_type=PURCHASE` | FIFO 批次 | 同一入库单两行生成两个批次且 `qty_in` 之和等于合格量 | `inv_batch` `inv_inbound(_item)` |
| FR-04.2 | FIFO 出库扣减 | 订单妥投事件 | 按 `received_at ASC, batch_id ASC` 依次扣 `qty_remaining`；每扣一批写一条 `inv_batch_consumption`；扣到 0 关闭批次 | COGS 流水 | 构造 3 批不同单价，卖出跨批数量后 `COGS = Σ(qty×unit_cost)` 与手算一致 | `inv_batch_consumption` |
| FR-04.3 | COGS 唯一来源 | 订单行 | `sal_order_item.cogs_amount` **只从 `inv_batch_consumption` 汇总回填**，禁止手工填、禁止移动加权 | 订单行成本 | 删除消耗流水后订单 COGS 归零且利润重算随之变化 | `inv_batch_consumption` `sal_order_item` |
| FR-04.4 | 四态台账 | 所有库存变动 | `qty_available / qty_in_transit / qty_reserved / qty_unsellable` 分离；`qty_reserved` 由平台扣、**不通过 Stock API 设置**（N-11） | `inv_stock_ledger` | 每次变动后台账与流水、批次剩余量三者守恒 | `inv_stock_ledger` `inv_stock_move` |
| FR-04.5 | 调拨与盘点 | 调拨单 / 盘点单 | 调拨多段在途；盘点盈亏写调整单并**按批次追补/冲销消耗流水**；**过账唯一责任**：调拨/ASN 只改 `inv_stock_ledger` 四态与 `inv_batch` 拆批血缘，**不写 `inv_batch_consumption`**（C-5） | 单据与流水 | 盘亏后批次剩余量同步减少且 COGS 可重算；调拨/ASN 全流程 COGS 不变 | `inv_transfer(_item)` `inv_stocktake(_item)` `inv_batch` |
| FR-04.6 | **退货成本冲回 + 分级处置**（C-2/C-2b） | 退货分级 A/B/C、补偿、退款 | **任何退货第一步都是红冲原 SALE 消耗层**（`sign=-1`，`reversal_of_id` 指向原流水）。**A 级**：按 `return_restock_cost` 建新批次插队尾（资产转移，不产生损益）。**B/C 级**：`RETURN_LOSS` **按原采购层成本全额计损**（`amount = 原消耗 amount × 退货比例`，**不得扣减补偿**）；平台赔偿**只在 `COMPENSATION` 确认一次**（`platform_fee` 减项，即收益 +），禁止同时在 `RETURN_LOSS` 里减补偿 —— 双计会把净损从 70 错算成 40。退款记 `refund_amount` 冲减 revenue；跨期红冲指向 `original_period` 并触发原期快照重算 | 冲回流水 + 新批次/损耗 | **成本100 / 售价150 / 全额退款 / 赔偿30 → 累计净损失 70**（不是 40、不是 200）；红冲可追到原消耗层 | `inv_batch_consumption` `sal_return` `inv_batch` |
| FR-04.7 | 原始留痕 | 平台报文 / CSV 行 | `raw_payload` 字段**不删减**；每条流水带 `move_no` + `ref_doc_type/no` + 操作人 + 时间戳 | 可回溯 | 任一库存变动可查到来源单据与原始报文 | `inv_stock_move` |
| FR-04.8 | **拆批在途与守恒**（C-5） | 采购到货 100、发运 40、目的仓收货 38 | 拆批时父批次保留采购成本血缘，子批次 `parent_batch_id` 指向父批；在途段只计 `qty_in_transit`，目的仓部分收货生成第二段拆批，短少 2 记 `TRANSFER_SHORT` 且不进 COGS | 批次余额 + 四态台账 | 任意时点 `Σ批次(qty_remaining + qty_in_transit)` = 台账四态对应量；短少可追到采购价 | `inv_batch` `inv_stock_ledger` `inv_stock_move` |

> **设计理由**：Q8 要求「不摊、单独、部分合并」。若把头程摊进 `unit_cost`，就再也无法单独出「不含期间费用的 SKU 贡献毛利」。FIFO 管采购成本，L2 管期间/批次费用，两者必须物理分离。

**C-2 退货数字案例（统一验算基准：单件成本 100、售价 150、全额退款；L1 = revenue − platform_fee − cogs_fifo）**

| 场景 | revenue | platform_fee | cogs_fifo（SALE/红冲/RETURN_LOSS） | 赔偿确认位置 | L1 净损益 |
| --- | --- | --- | --- | --- | --- |
| **A 级可售**（货回仓） | 150 − 150 = 0 | 0 | 100 − 100 + 0 = 0 | 无 | **0**（新批次资产 100，不产生损益） |
| **B/C 级报废，赔偿 30**（★验收基准） | 150 − 150 = 0 | **−30**（COMPENSATION 减项，仅一次） | 100 − 100 + **100** = 100 | 仅 COMPENSATION | **0 − (−30) − 100 = −70** ✓ |
| **B/C 级报废，无赔偿** | 0 | 0 | 100 − 100 + 100 = 100 | 无 | **−100** |
| **部分退货 2/10 件**（B 级，每件赔偿 3） | 30 − 30 = 0（退的2件） | −6（2×3，仅一次） | 20 − 20 + 20 = 20（退的2件） | 仅 COMPENSATION | **−14**（2件净损）；未退 8 件 L1 = 8×(150−100) = 400 |
| **跨期退货**（原期 2026-10，退货 2026-11） | 退款归退货期 | 赔偿归退货期 | 红冲归原 2026-10 并重算 | COMPENSATION 归退货期 | 两期并存，不把原期成本挪到退货期 |

> **★防双计红线（C-2b）**：赔偿 30 只能出现在 `platform_fee` 的 `COMPENSATION` 减项 **或** `RETURN_LOSS` 的抵减中**二选一**，本版统一走 `COMPENSATION`。`RETURN_LOSS` 永远按**原采购层成本全额**计损（100），不得写 `100−30=70`。若双计：L1 会变成 `0−(−30)−70 = −40`（少算 30 损失）。若既不计损也不冲回：COGS 停在 200（多算 100）。**唯一正确答案是 −70**。

**C-5 拆批守恒案例（国内仓 CN01 → FBN 仓 AE-FBN）**

| 时点 | 父批次 BAT-001 (CN01) | 子批次 BAT-001-02 (在途) | 子批次 BAT-001-03 (AE-FBN) | 台账 |
| --- | --- | --- | --- | --- |
| 采购到货 100 | qty_remaining=100 | — | — | CN01 available=100 |
| 发运 40 | qty_remaining=60 | qty_in_transit=40, parent=BAT-001 | — | CN01 avail=60 / transit=40 |
| 目的仓收 38 | 60 | 关闭，短少 2 记 TRANSFER_SHORT | qty_remaining=38, unit_cost 继承 100 | AE-FBN avail=38；短少 2 不进 COGS |
| FBN 售 10 | 60 | — | qty_remaining=28 | 销售妥投才写 SALE 消耗 10×100 |

> **`unit_cost` 继承规则**：拆批不改变单位成本，采购价血缘可从任一子批次回溯至父批次 `pur_order_item_id`；短少只减少数量，不重算单价。

### FR-05 头程与 FBN 入仓 ASN（P0）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-05.1 | ASN 登记 | `asn_no`、站点、FBN 仓码、SKU/批次/数量 | 本版 `source=MANUAL`（Seller Lab 手工登记）；状态机按 §3.3，**`SEALED` 后才可填 `slot_date`** | `log_asn(_item)` | DRAFT 状态下填 `slot_date` 被拒 | `log_asn` `log_asn_item` |
| FR-05.2 | 收货差异 | `qty_shipped` vs `qty_received` | 计算 `diff_qty` 并回写库存与批次，差异必须留原因 | 异常记录 | 差异为 0 时不生成异常；非 0 时不允许静默关闭 ASN | `log_asn_item` `inv_stock_move` |
| FR-05.3 | 头程费用登记 | 运费 / 关税 / 清关 / 包材 / 贴标 | `fee_type` 枚举；**`allocation_flag` 默认 `NONE`，不摊进批次成本**；可按期间或 ASN 归集 | `log_shipment_cost` → `fin_expense_pool` | 登记费用后 `inv_batch.unit_cost` 不变 | `log_shipment_cost` |
| FR-05.4 | API 升级预留 | FBN Inbound API（待索取） | `log_asn.source` 预留 `API`；不得臆造端点 | 未来可切换 | 拿到官方 spec 后仅需替换适配器实现，不改业务表 | `log_asn` |

### FR-06 订单与退货（P0）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-06.1 | **订单导入幂等与状态更新**（C-4） | Seller Lab / impex CSV、webhook 事件 | `source_channel=API/EXPORT/WEBHOOK/MANUAL`；`order_no + site_code` 是业务唯一键；按 `raw_payload` 规范化指纹 `SHA256`：**同指纹重复导入 → ACK 不更新、不过账**；**同订单新状态（IMPORTED→DELIVERED→RETURNED/CANCELLED）→ 按状态机更新 `state_version+1`**，不整单拒绝；`platform_item_no` 定义行级粒度；`raw_payload` 不删减；按 `partner_sku` 回查 `sku_id` | `sal_order(_item)` | 同一 CSV 重传 2 次只落 1 单且 COGS 不过账 2 次；先导入后补妥投成功；部分妥投 2/3 件可表达 | `sal_order` `sal_order_item` |
| FR-06.1b | **过账唯一性**（C-4/C-5） | 妥投 / 退货事件 | 每个 `sal_order_item` 的同一 `move_type` 只产生**一条**消耗流水；红冲必须指向原流水；调拨/ASN 不产生 COGS | `inv_batch_consumption` | 重复投递妥投事件，COGS 不变；ASN 收退货后 COGS 不变 | `inv_batch_consumption` `inv_stock_move` |
| FR-06.2 | 妥投触发成本 | `delivered_at` | **仅 `DELIVERED` 触发 FIFO 扣减与 COGS 归集**；CANCELLED 不扣 | 出库流水 | 取消订单不产生 `inv_batch_consumption` | `inv_batch_consumption` |
| FR-06.3 | 周账单归期 | 妥投时间 | 账单按妥投归期，导入订单未填 `delivered_at` 时进「待补」清单 | 待补清单 | 待补订单不参与当期利润快照 | `sal_order` |
| FR-06.4 | COD 标识 | `payment_type` | `COD` 与 `PAID` 分开统计；COD 未回款不计入现金口径（本版不建资金模块，仅出报表维度） | 报表维度 | COD 订单在回款报表中单列 | `sal_order` |
| FR-06.5 | 退货登记 | `ListReturnReferences` + 账单退货行 | 记录 `grade A/B/C`、`compensation_amount`；按 `sys_config` 计算 `window_deadline` / `appeal_deadline` | `sal_return` | 窗口/申诉截止日可查询与预警；逾期单项高亮 | `sal_return` |
| FR-06.6 | 退货处置 | 分级结果 | **先红冲原 SALE 消耗层**（FR-04.6 / C-2）；A 级二次上架建新批次；B/C 级按**原采购层成本全额**写 `inv_batch_consumption(move_type=RETURN_LOSS)`，**不得扣减赔偿**；退款冲收入；赔偿只在 `COMPENSATION` 平台费减项确认**一次** | 冲回 + 损耗 + 退款 | 成本100/售价150/全额退款/赔偿30 → L1 = −70（不是 −40、不是 −200）；同一件货 COGS 不重复确认、赔偿不双计 | `sal_return` `inv_batch_consumption` |

### FR-07 结算账单与对账（P0，★资金安全）

| 编号 | 功能 | 输入 | 规则 | 输出 | 验收标准 | 对应表 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-07.1 | 两类账单导入 | 周 `WEEKLY` statement、月度 `STORAGE` statement 的 Seller Lab CSV | `statement_no` 唯一；`statement_type` 必须区分；**两类分别核对，不合并成一张** | `fin_statement` | 导入重复账单被拒；STORAGE 不会计入 WEEKLY 净额 | `fin_statement` |
| FR-07.2 | 逐行对账 | 账单原始行 | `line_type` 枚举 `SALE / REFERRAL_FEE / FBN_OUTBOUND / STORAGE / RETURN_FEE / AD_SPEND / COMPENSATION / OTHER`；按 `order_no`/`sku_id`/`line_type` 匹配本地；保留 `raw_json` | `fin_statement_line` + 差异清单 | 未匹配行 `matched=0` 全部进差异清单，不自动猜测 | `fin_statement_line` |
| FR-07.3 | **15 天争议倒计时** | `generated_at` | `dispute_deadline = generated_at + statement_dispute_days`（默认 15）；按 `sys_config` 取值，可按站点覆盖 | 倒计时 + 提醒 | 账单导入后 1 分钟内生成截止日；剩余 ≤3 天高亮告警 | `fin_statement` |
| FR-07.4 | 争议处理 | 差异清单 | 本版只记录 `reconcile_status=DISPUTED` + 导出差异清单供 Seller Lab 提交；**不做平台在线提交** | 差异清单（可导出） | 差异清单含账单号、行号、金额、原始行、截止日 | `fin_statement` |
| FR-07.5 | 回款登记 | Bank Transaction ID、金额、日期 | `fin_payout` 关联 `statement_no`，汇率按付款日取 | 回款流水 | 账单净额与回款差额可查 | `fin_payout` |
| FR-07.6 | 版本化费用字典 | 类目 × 站点 × 履约模式 × 生效日期 | `PERCENT / FIXED / TIER`；`min_amount` 支持 KSA 每单最低 1 SAR；**新增版本不覆盖历史** | `fin_fee_dict` | 改费率后旧期间的利润重算仍用旧费率 | `fin_fee_dict` |
| FR-07.7 | AD_SPEND 保留 | 账单中的广告扣款 | 虽不做广告模块（Q7），该行类型必须入库，否则周账单净额对不平 | 账单行 | 含广告扣款的账单能对平净额 | `fin_statement_line` |
| FR-07.8 | VAT 字段预留 | — | `vat_amount` 可空、`vat_mode=IGNORE`；VAT 科目位保留但不参与计算 | 空字段 | 启用 VAT 时不需要改表 | `sal_order` `fin_fee_dict` |

### FR-08 利润引擎（P0，★核心）

**公式定义（写入 `formula_version`，可配置、可重算）**

```
platform_fee  = REFERRAL_FEE + FBN_OUTBOUND + STORAGE + RETURN_FEE − COMPENSATION
cogs_fifo      = Σ inv_batch_consumption.amount        （按订单行/期间归集，唯一来源）
L1 contribution_margin = revenue − platform_fee − cogs_fifo

L2 expense_pool = Σ fin_expense_pool.amount            （按 period / site / asn 归集）
allocated_expense(SITE) = 0                            when NONE
                        = Σ(勾选科目 included_in_partial=1) when PARTIAL
                        = Σ(全部科目)                    when FULL
net_profit(SITE) = contribution_margin(SITE) − allocated_expense(SITE)

-- SKU 行：L1 直接聚合，L2 仅按 basis 分摊，禁止每个 SKU 各扣一次整池
SKU.contribution_margin = revenue − platform_fee − cogs_fifo
SKU.allocated_expense   = SITE.allocated_expense × (SKU.basis_value / ΣSKU.basis_value)
SKU.net_profit          = SKU.contribution_margin − SKU.allocated_expense
守恒校验：ΣSKU.allocated_expense = SITE.allocated_expense（误差 ≤ 0.01）
basis 由 sys_config.profit_allocation_basis 决定：REVENUE_SHARE / QTY_SHARE / EQUAL
```

| 编号 | 功能 | 规则 | 验收标准 |
| --- | --- | --- | --- |
| FR-08.1 | L1 SKU 贡献毛利 | 按 `sku_id × site_code × period` 聚合收入、平台费、FIFO COGS（含退货红冲净额）；`revenue` 按 `fx_rate` 归一 | 单 SKU 手算与系统结果一致（误差 ≤ 0.01） |
| FR-08.2 | L2 费用池 | 独立归集头程/关税/清关/包材/汇兑/通道费/AD_SPEND；**不写入 `inv_batch`** | 登记 L2 费用后 L1 数值不变 |
| FR-08.3 | 三档合并口径并存（C-3，维度A） | `NONE/PARTIAL/FULL` 三套结果**并存互不覆盖**，各自写独立的 `allocation_policy`；只决定 SITE 行 L2 合并范围（0/勾选/全部），**与是否摊到 SKU 无关**；报表显式标注口径 | 同一期间同一公式版本可同时查到 3 套结果，切换不覆盖历史且不影响维度B |
| FR-08.4 | 多维查询 | SKU / Partner SKU / 站点 / 品类 / 仓 / 期间 | 双站点对比先归一币种；AED 与 SAR 不直接相加 |
| FR-08.5 | 公式版本与多次重算（C-3） | 每次计算生成 `calc_batch_no` + `formula_version + recalculated_at`；改费率或公式后指定期间重算，**生成新批次不覆盖旧批次**；`calc_input_snapshot` 保存费率版本/汇率/流水截止/费用池批次 | 同一期间重算 2 次得到 2 个 `calc_batch_no`，旧快照原样可查且输入可复算 |
| FR-08.6 | 净利口径澄清 | `net_profit = contribution_margin − allocated_expense`（DDL 旧注释「L1+L2」按此公式解释，勿按加法理解） | 报表口径说明与本公式一致 |
| FR-08.7 | VAT 风险提示 | `vat_mode=IGNORE` 时，报表页固定展示提示：「售价含 VAT（AE 5% / SA 15%）、费率报价不含 VAT，当前 L1 毛利系统性偏高」 | 每张利润报表都带该提示，不可关闭 |
| FR-08.8 | **站点总利润与 SKU 分配**（C-3，维度B） | `scope_type=SITE, sku_id=0` 行存站点总额（SITE 行按维度A合并的 L2）；SKU 行是否分摊由**独立开关** `sku_allocation_enabled`（默认 0）决定：关闭时 SKU 行 `allocated_expense=0`、净利 = 自身 L1；仅开启时才按 `allocation_basis=REVENUE_SHARE` 分摊 | 开关关闭时 PARTIAL/FULL 仅 SITE 行生效、SKU 行净利 = 自身 L1；开关开启时任一 `period+site+policy+batch` 下 ΣSKU 分摊 = SITE 行金额（误差 ≤0.01） |

**C-3 三档并存与分摊数字案例（期间 2026-11 / 站点 AE / 同一公式版本 FV-2026.11-01；SITE 列三档恒成立，SKU 列仅在维度B `sku_allocation_enabled=1` 启用时成立）**

| 口径 | SITE: L1 | SITE: L2 池 | SITE: 净利 | SKU-A (收入 600, 占比 60%) | SKU-B (收入 400, 占比 40%) |
| --- | --- | --- | --- | --- | --- |
| NONE | 78,210 | 7,716.58（仅展示） | **78,210** | L1=46,926，分摊 0，净利 46,926 | L1=31,284，分摊 0，净利 31,284 |
| PARTIAL（勾选 2,540） | 78,210 | 2,540 | **75,670** | 分摊 1,524，净利 45,402 | 分摊 1,016，净利 30,268 |
| FULL | 78,210 | 7,716.58 | **70,493.42** | 分摊 4,629.95，净利 42,296.05 | 分摊 3,086.63，净利 28,197.37 |

> 三档各自一个 `calc_batch_no`（如 `CB-20261117-001/002/003`），互不覆盖；重算再生成 `CB-20261117-004`。合计守恒：46,926+31,284=78,210（L1）；4,629.95+3,086.63=7,716.58（L2）。

> **默认口径决议（2026-11-18 用户确认；同日 Codex 复核修正两维度）**：利润口径由**两个相互独立的维度**控制，切换维度A **不会**改变维度B。
>
> - **维度A · 站点层费用合并** `sys_config.allocation_policy`（默认 `NONE`）：只决定 **SITE 行**合并多少 L2 —— `NONE`=0（L2 仅单独展示）、`PARTIAL`=勾选科目、`FULL`=全部。三档并存互不覆盖，「部分合并 / 全部合并」需求保留不变，用户可显式切换。
> - **维度B · SKU 级分摊开关** `sys_config.sku_allocation_enabled`（默认 `0` 关闭）：**独立开关**，才决定是否把 SITE 行的 `allocated_expense` 分摊到 SKU 行。分摊方法固定为收入占比 `profit_allocation_basis=REVENUE_SHARE`（该配置是维度B的**方法参数**，不是开关）。**关闭时** PARTIAL/FULL 仅在 SITE 行生效，SKU 行 `net_profit` = 自身 L1、不承担 L2，无需 ΣSKU=SITE 校验；**启用时** SKU 行按 REVENUE_SHARE 分摊，ΣSKU 分摊额 = SITE 行 `allocated_expense`（误差 ≤0.01），禁止每 SKU 各扣整池。
>
> **关键澄清**：维度A 选择 `PARTIAL`/`FULL` **不等于**启用 SKU 分摊；此前把 `allocation_policy` 当作「分摊开关」的表述已作废。SKU 级分摊为可选功能、默认关闭，是否在特定报表默认启用仍属待确认范围，不在本版擅自开启。

> ⚠️ R7：不做 VAT 会让 L1 毛利系统性虚高。这是已接受的口径，字段与科目位已预留，随时可启用不改表。

### FR-09 补货建议（P0）

| 编号 | 功能 | 规则 | 验收标准 |
| --- | --- | --- | --- |
| FR-09.1 | 指标计算 | `avg_daily_sales_7/30`、`days_of_cover = qty_available / avg_daily_sales_7`、`qty_in_transit`、`lead_time_days = 生产 + 头程 + 入仓`、`safety_stock`、`event_factor`（斋月 / Yellow Friday 系数） | 各指标可在建议单上逐项查看来源 |
| FR-09.2 | 建议量公式 | `suggested_qty = CEIL(avg_daily_sales_30 × event_factor × (lead_time_days + safety_days) − qty_available − qty_in_transit)`，下限 0 | 手算与系统一致；在途增加时建议量下降 |
| FR-09.3 | 分站点分仓 | 按 `sku_id × site_code × warehouse_id` 分别出建议，**不跨站点合并** | AE 与 SA 建议单独立 |
| FR-09.4 | 人工复核闸门 | `source=AI/MANUAL`，**AI 只能写 `DRAFT`**；`CONFIRMED` 必须记录 `reviewed_by/at`；`CONVERTED` 写 `converted_doc_type/no` | AI 账号直接写 CONFIRMED 被拒 |
| FR-09.5 | 一键转单 | 转采购单 / 调拨单 / ASN，回写 `source_ref_no` 形成闭环 | 由建议单能反查生成的采购单 |
| FR-09.6 | 预警 | 断货、滞销、库龄超期（FBN >365 天仓储费跳涨） | 超期 SKU 出现在预警清单 |

### FR-10 NoonAdapter 与集成（P0，★技术底座）

| 编号 | 功能 | 规则 | 验收标准 |
| --- | --- | --- | --- |
| FR-10.1 | **凭据与鉴权** | `.json` 只在服务端加密文件读取，`credential_ref` 仅存路径引用，**私钥不入库、不进 Git**；RS256 JWT（`sub=key_id`、`iat` 整秒、`jti`）→ `POST /identity/public/v1/api/login` body `{token, default_project_code}` → session cookie；**每次登录新建 JWT**、时钟偏差 ≤5 分钟；所有请求带 `User-Agent`（`NoonErpClient/1.0`）；会话 30 天到期自动重新登录 | 沙箱 Login + whoami 跑通；日志中不出现私钥；`iat` 用毫秒会被拒并能被本地校验拦截 |
| FR-10.2 | 环境隔离 | `env=SANDBOX/PRODUCTION` 落 `md_partner_account`；沙箱换 host 为 `noon-sandbox-api-gateway.noon.partners`，**login 仍走生产**；默认先沙箱；**A-03：生产与沙箱共用同一份 `.json` 凭据**，不申请第二份 | 切环境只改 `env`/host 配置不改代码、不换凭据；`md_partner_account` 只需 1 条记录 |
| FR-10.3 | 四通道 | `API / CSV / WEBHOOK / MANUAL` 统一抽象，业务层只依赖内部领域接口 | 关闭 API 通道后订单/账单仍可 CSV 导入跑通闭环 |
| FR-10.4 | 限流与重试 | per-project 固定窗口 + burst 双计数；读 `X-Ratelimit-Remaining` / `X-Ratelimit-Burst-Remaining` / `X-Ratelimit-Retry-After`；429 指数退避；失败写 `int_sync_task` 补偿 | 模拟 429 后自动退避重试且不丢任务 |
| FR-10.5 | 全量 API 留痕 | 记录 `X-Request-Id`、http_status、error_code、request/response、latency、时间戳；平台侧 Request Logs 只保留 14 天，**必须本地留痕** | 提 Support Case 可直接从系统拿到 request_id |
| FR-10.6 | 错误映射 | 错误 envelope `error{status_id, code, message, fields, doc_url}`；`PERMISSION_DENIED` → 提示检查 service account 角色；其他错误码映射为可读告警 | 权限不足时给出明确处置指引而非原始堆栈 |
| FR-10.7 | Webhook 接收 | ≤5 destination；`event_id` 唯一键幂等；状态 `RECEIVED/ACKNOWLEDGED/PROCESSED/FAILED/KILLED`；原始 `payload_raw` 不删减 | 重复事件不产生重复订单 |
| FR-10.8 | impex 导出任务 | `GetExportCategoryList → CreateExport → GetExportStatus` 三步；`export_category_code` **只能是 `int_export_category` 中已发现的 12 个码**（C-1），且 `is_usable=1` 才可自动创建；当前 **12 个码全部 `is_usable=0`**（O-07/O-09 未验证）→ 界面只可登记不可声称可用；`int_export_job` 记录状态与 `file_url` | 不存在的类别码写入被拒；只读账号下未验证时任务状态为 `BLOCKED` 而非 `SUCCESS` | `int_export_category` `int_export_job` |
| FR-10.9 | Stock 绝对值语义 | `stock_update_mode=ABSOLUTE`；**禁止增量同步**；reserved 由平台扣，不推送 | 连续两次推同一绝对值不叠加 |
| FR-10.10 | 字段映射层 | 平台报文 → 内部 DTO 单独一层映射；原始字段全留 `raw_payload`/`raw_json` | 平台加字段不导致导入失败 |
| FR-10.11 | 官方 MCP 接入 | WorkBuddy / Codex / Claude Code 可直连官方 MCP，OAuth 授权时**只勾 Read**（`execute_api_read`），Revoke 随时可用；**N-25：平台仅 11 个 Read 工具 / 0 个写工具，Offer 与报表不在 MCP** | 核对 MCP 工具清单零写工具；在受控环境验证写端点被拒（§8.2、UAT-01 步 9、O-11），**不在生产盲测** |

### FR-11 AI 外挂（P0，Q12 已确认）

> **双路径落地**：① 接入方式 = AI 客户端（WorkBuddy / Codex / Claude Code）+ 官方 MCP 只读权限；② 首个业务场景 = **周账单差异核查 `STATEMENT_RECON`**（用户采纳建议）。其余场景保留枚举、按序启用。

| 编号 | 功能 | 规则 | 验收标准 |
| --- | --- | --- | --- |
| FR-11.1 | 三不碰边界 | AI **不碰库存台账、采购单、财务账**；唯一可写表是 `ai_suggestion`，且只能 `status=PENDING/DRAFT` | 用 AI 账号直接 UPDATE `inv_stock_ledger` / `pur_order` / `fin_statement` 全部被拒 |
| FR-11.2 | 只读取数 | 4 个视图 `v_ai_sku_overview` / `v_ai_profit` / `v_ai_statement_open` / `v_ai_replenish` + 官方 MCP `execute_api_read` | 视图查询不含任何写操作 |
| FR-11.3 | 建议单 | `scene`、`source_agent`、`confidence`、`payload_json`（建议内容，**不是指令**）、状态机 `PENDING→APPROVED/REJECTED` | APPROVED 必须记录 `reviewed_by/at`；REJECTED 必须可填原因 |
| FR-11.4 | 落单闸门 | APPROVED 后**由核心系统**生成业务单并回写 `converted_doc_type/no`；AI 不参与落单 | 从建议单可反查业务单，且业务单创建人是人类账号 |
| FR-11.5 | 首个场景 `STATEMENT_RECON` | 输入：未闭合账单 + 账单行 + 本地订单/费用；AI 输出：差异候选清单（账单号、行、金额、疑似原因、证据字段），**不修改任何账单行** | 差异候选清单可逐条勾选复核；复核前 `fin_statement_line.matched` 不变 |
| FR-11.6 | 场景枚举与顺序 | `STATEMENT_RECON`（首个）→ `REPLENISH` → `EN_AR_COPY` → `PRICING` | 未启用场景不出现在 AI 入口 |
| FR-11.7 | 审计 | AI 每次取数与每条建议都写 `sys_audit_log`，带编号与时间戳 | 可回放「哪次取数产出了哪条建议」 |

### FR-12 报表与看板（P0）

| 编号 | 报表 | 关键口径 |
| --- | --- | --- |
| RPT-01 | SKU 利润表 | L1 / L2 / 合并三口径、公式版本、可重算、可导出 |
| RPT-02 | 双站点对比 | 归一币种后对比销量/收入/毛利/可售天数 |
| RPT-03 | 账单差异清单 | 未匹配行 + 争议截止日倒计时 |
| RPT-04 | 回款与账期 | 账单净额 vs 回款差额、COD 单列 |
| RPT-05 | 库存四态与库龄 | 可用/在途/锁定/不可售、>365 天预警 |
| RPT-06 | FIFO 批次流水 | 批次剩余量、消耗流水、COGS 溯源 |
| RPT-07 | 补货建议看板 | 建议量、依据指标、状态、转单结果 |
| RPT-08 | Offer 健康 | `live_status` / `offer_issues`（双站点） |
| RPT-09 | 退货与申诉时效 | 窗口期、申诉截止、分级与补偿 |
| RPT-10 | 接口健康 | 429/错误码/DEAD 任务/request_id 检索 |

### FR-13 数据导入与一次性迁移（P0）

| 编号 | 功能 | 规则 | 验收标准 |
| --- | --- | --- | --- |
| FR-13.1 | 标准模板 | 提供 SKU、供应商、Partner SKU 映射、期初批次、订单、周账单、月度仓储账单、费用池 8 类 CSV 模板 | 模板含字段说明、示例行、必填标记 |
| FR-13.2 | 导入校验 | 唯一键、必填、类型、枚举、汇率存在性校验；**D-02：订单导入事务须校验主表 `qty` = Σ 明细 `qty`、`gross_amount` = Σ 行金额**，不一致整单进异常清单；错误行进异常清单不丢弃，成功行进正式表 | 一个坏行不会导致整批回滚（可配置整批事务）；主表与行表数量/金额恒等 |
| FR-13.3 | 迁移范围 | 只迁主数据 + 期初 FIFO 批次；**不迁历史订单**（Q13） | 迁移后库存账与批次成本可对齐期初 |
| FR-13.4 | 原始留档 | 每次导入生成批次号、操作人、时间戳，原始行存 `raw_payload`/`raw_json` | 可下载原始文件并复现导入 |

### FR-14 页面与菜单（IA，P0）

```
工作台
├─ 概览：待办 / 账单争议倒计时 / DEAD 同步任务 / AI 待复核建议
├─ 主数据：SKU · Partner SKU 映射 · 供应商 · 仓与站点 · 系统参数
├─ 采购：采购单 · 到货入库 · FIFO 批次流水
├─ 库存：四态台账 · 调拨 · 盘点 · 库存变动流水
├─ 物流：ASN 登记 · 收货差异 · 头程费用
├─ 销售：订单 · 退货与申诉时效 · Offer 健康
├─ 财务：周账单 · 仓储账单 · 差异清单 · 回款 · 费用字典 · 费用池 · 利润重算
├─ 补货：补货建议 · 大促系数 · 预警
├─ 报表：RPT-01~RPT-10
├─ 集成：同步任务 · API 日志 · Webhook 事件 · 导出任务 · 环境配置
└─ 系统：用户 · 角色 · 审计日志 · 导入模板 · AI 建议复核
```

### FR-15 明确不做但留口子

| 项 | 预留方式 | 启用代价 |
| --- | --- | --- |
| 刊登 | `md_partner_sku` + EN/AR 字段 | 新增内容表与 API 调用，不改现有表 |
| 广告 | `AD_SPEND` 行类型 + `fin_expense_pool` 科目 | 新增广告报表与归因 |
| VAT | `vat_amount` 空字段 + `vat_mode` 参数 + 科目位 | 配置开启 + 公式版本升级 |
| 审批流 | 单据 `status` 状态机 + `sys_audit_log` | 新增 `sys_approval_flow`，不改现有表 |
| 多平台 | 单层 `NoonAdapter` | 新增适配器实现 |
| Global/FBP 利润 | `fulfillment_mode` 枚举 | 新增第二/第三套利润引擎 |
| 移动端 | Web 端响应式即可 | 另立项目 |

---

## 5. 非功能需求（NFR）

| 编号 | 类别 | 要求 |
| --- | --- | --- |
| NFR-01 | 架构 | Java SpringBoot **单体单 jar**，模块用包/模块化隔离；MySQL 8 单库；无注册中心、无 MQ 集群（本地任务表 + 定时调度足够 ≤10 人） |
| NFR-02 | 部署 | 云端单台服务器 + Nginx/HTTPS；**无移动端**；浏览器 Chrome / Edge 最近两个大版本 |
| NFR-03 | 性能 | 常规列表 ≤2s；单次 1 万行 CSV 导入 ≤30s；利润全量重算（1 年数据）≤5min；10 人并发无明显阻塞 |
| NFR-04 | 安全·凭据 | 见 §7.4；私钥不入库、不进 Git、不进日志；服务端文件权限 `600`；`.gitignore` 已配置 |
| NFR-05 | 安全·账号 | 密码散列、RBAC、`site_scope` 站点隔离、HTTPS、登录失败锁定 |
| NFR-06 | 审计 | 所有写操作带 `log_no` + 操作人 + before/after + 时间戳；AI 取数与建议同样留痕 |
| NFR-07 | 数据留痕 | 所有导入 `raw_payload` / `raw_json` 原始字段**不删减**；日志带编号与时间戳；平台侧 Request Logs 仅 14 天，本地至少留 12 个月 |
| NFR-08 | 幂等 | webhook `event_id`、账单 `statement_no`、订单 `order_no+site_code`、支付/同步任务均有幂等键 |
| NFR-09 | 可配置 | 费率、VAT、门槛、退货窗口、争议天数、汇率、公式版本全部落库；**禁止写死在 Java 常量** |
| NFR-10 | 可重算 | 利润快照带 `formula_version` 与 `recalculated_at`，重算保留新旧结果，原始单据不变 |
| NFR-11 | 时间口径 | 系统账务统一以 **Asia/Shanghai (GMT+8)** 为基准存储；站点本地时间（AE/SA）仅用于展示；平台原始时间字符串保留在 `raw_payload` |
| NFR-12 | 币种口径 | AED / SAR / CNY / USD；`fx_rate` 日汇率；跨站点对比先归一到 CNY；汇率缺失报错而非用 1 兜底 |
| NFR-13 | 备份与恢复 | 每日全备 + binlog，保留 30 天；上线前做一次恢复演练；单据号与时间戳可支撑恢复后对账 |
| NFR-14 | 可扩展性 | FR-15 的 7 项后期能力启用时**不改现有表结构**；`NoonAdapter` 替换端点不改业务代码 |
| NFR-15 | 可观测 | 429 / 5xx / `PERMISSION_DENIED` / DEAD 任务 / 争议倒计时 ≤3 天 / 批次不足 六类告警 |

---

## 6. 数据模型

### 6.1 表分组（共 **45 表 + 4 视图**，与当前开发依据 `schema-v0.4.sql` 一致）

| 组 | 张数 | 表名 | 职责 |
| --- | --- | --- | --- |
| A 主数据与配置 | 9 | `md_partner_account` `md_store` `md_site` `md_warehouse` `md_sku` `md_partner_sku` `md_supplier` `md_sku_supplier` `sys_config` | 组织、SKU 映射、供应商、参数 |
| B 采购·FIFO·库存 | 12 | `pur_order` `pur_order_item` `inv_batch` `inv_batch_consumption` `inv_stock_ledger` `inv_stock_move` `inv_inbound` `inv_inbound_item` `inv_transfer` `inv_transfer_item` `inv_stocktake` `inv_stocktake_item` | 采购到货、批次成本、四态台账与流水 |
| C 头程与入仓 | 3 | `log_asn` `log_asn_item` `log_shipment_cost` | ASN 状态、收货差异、头程费用登记 |
| D 订单·退货·结算·利润 | 10 | `sal_order` `sal_order_item` `sal_return` `fin_statement` `fin_statement_line` `fin_fee_dict` `fin_payout` `fin_expense_pool` `fin_profit_snapshot` `fx_rate` | 订单、账单逐行对账、双层利润 |
| E 补货 | 1 | `rp_replenish_suggestion` | 建议单与转单 |
| F 集成·日志·权限·AI | 9 | `int_sync_task` `int_webhook_event` `int_api_log` `int_export_job` `sys_user` `sys_role` `sys_user_role` `sys_audit_log` `ai_suggestion` | 同步、留痕、RBAC、建议单 |
| 视图 | 4 | `v_ai_sku_overview` `v_ai_profit` `v_ai_statement_open` `v_ai_replenish` | AI 只读取数 |

### 6.2 核心关系链

```
md_partner_sku(partner_sku, site_code) ──回查──> md_sku(sku_id)
pur_order_item ──到货──> inv_batch(batch_no, unit_cost, qty_remaining)
inv_batch ──FIFO 消耗──> inv_batch_consumption ──汇总──> sal_order_item.cogs_amount
inv_batch ──装箱──> log_asn_item(asn_no) ──入仓──> FBN 仓
sal_order(DELIVERED) ──妥投归期──> fin_statement(WEEKLY / STORAGE)
fin_statement_line ──匹配──> 差异清单 ──> fin_profit_snapshot(L1)
log_shipment_cost / fin_expense_pool ──L2──> fin_profit_snapshot(net_profit)
fin_profit_snapshot + 库存四态 ──> rp_replenish_suggestion ──转单──> pur_order
ai_suggestion ──人工 APPROVED──> 核心系统落单（不直接写业务表）
```

### 6.3 关键字段口径（开发前必读）

| # | 说明 |
| --- | --- |
| 1 | `inv_batch_consumption` 是 **COGS 唯一来源**；`sal_order_item.cogs_amount` 只允许汇总回填 |
| 2 | `inv_batch.unit_cost` **不含**头程/关税/包材（Q8）；费用进 `log_shipment_cost` → `fin_expense_pool` |
| 3 | `fin_profit_snapshot.contribution_margin = revenue − platform_fee − cogs_fifo`；`net_profit = contribution_margin − allocated_expense`（**不是加 L2**） |
| 4 | `fin_statement.statement_type` 必须区分 `WEEKLY` / `STORAGE`；`dispute_deadline = generated_at + 15d` |
| 5 | `fin_statement_line.line_type='AD_SPEND'` 即使不做广告模块也必须保留 |
| 6 | `sal_order` 主表保留 `partner_sku/qty/gross_amount` 冗余字段用于导入快速落库；**成本核算粒度以 `sal_order_item` 为准**，两者一致性由导入事务保证 |
| 7 | `inv_stock_ledger.qty_reserved` 由平台扣减，ERP 不通过 Stock API 设置；推送量是绝对值 |
| 8 | `int_api_log.request_id` = 响应头 `X-Request-Id`，Support Case 必备 |
| 9 | `int_webhook_event.event_id` 为幂等键；`int_export_job.export_code` 可空（PENDING 阶段） |
| 10 | 所有平台规则参数在 `sys_config` 有历史版本；利润快照带 `formula_version`，改公式不影响历史 |

### 6.4 DDL 修订结果（**D-01~D-03 已关闭**，A-02 授权落地）

### 6.4 DDL 修订结果（D-01~D-03 已关闭；C-1~C-5 见 v0.4）

| 编号 | 原问题 | 修订内容 | 状态 |
| --- | --- | --- | --- |
| D-01 | `v_ai_sku_overview` 按 `l.site_code = p.site_code` 关联，而 `inv_stock_ledger` 以 `(sku_id, warehouse_id)` 唯一 → **同站点多仓放大库存行** | 改为按 `(sku, site)` **聚合** `COALESCE(SUM(qty_*),0)`，新增 `warehouse_count`，并显式 `GROUP BY s.id, … p.id …`；注释写清「同一 SKU 多条 Partner SKU 时为同一份站点汇总，禁止跨行相加」 | ✅ 已修复 |
| D-02 | `sal_order` 与 `sal_order_item` 冗余字段缺一致性约束 | DDL 表上方补注粒度口径；**FR-13.2 增加导入事务校验**（主表 qty / 金额 = 行级合计），作为可测验收标准 | ✅ 已明确 |
| D-03 | 时间口径未在 DDL 声明 | 文件头设计约定第 6 条：所有 `DATETIME` 统一 GMT+8 存储，站点本地时间仅展示层换算，平台原始时间保留在 `raw_payload` | ✅ 已补注 |

> **当前开发依据 = `docs/noon-erp-schema-v0.4.sql`**；`v0.3` 为 Codex 评审基线、`v0.2` 为历史基线，均不覆盖。v0.4 静态校验：45 表 / 4 视图、括号平衡、无重名表、建表语句均带 `ENGINE`、`sys_config` 与 `int_export_category` 初始化幂等。
>
> **Codex 评审 6 项在 v0.4 的落点**：C-1 新增 `int_export_category`（12 个码三层验证）；C-2 `inv_batch_consumption` 加 `sign/reversal_of_id/refund_amount/cross_period`、`sal_return` 加退款与净损；C-3 `fin_profit_snapshot` 加 `allocation_policy/calc_batch_no/scope_type/calc_input_snapshot`；C-4 `sal_order` 加 `import_fingerprint/state_version`、`sal_order_item` 加 `platform_item_no/qty_*`；C-5 `inv_batch` 加 `parent_batch_id/split_seq/transfer_no/asn_no`；C-6 原型模拟边界见 §4 与原型说明。逐条证据见 `docs/noon-erp-codex-review-v1.0.md`。

---

## 7. 接口与集成设计

### 7.1 鉴权流程（FR-10.1 细化）

1. 服务端读取加密的 `.json` 凭据（**只在内存使用，私钥不落库、不进日志**）。
2. 取 `key_id` 作 JWT `sub`，生成 `iat`（**整秒 Unix 时间**，与服务器时钟偏差 ≤5 分钟）与随机 `jti`。
3. 用 `private_key`（RS256）签 JWT → `POST https://noon-api-gateway.noon.partners/identity/public/v1/api/login`，body `{ "token": <JWT>, "default_project_code": "PRJ468049" }`，**带 `User-Agent`**。
4. 取回 session cookie，后续请求带 cookie + `User-Agent`；会话 **30 天**（N-16），到期或失效自动重新走 1–3。
5. 沙箱：业务请求改 host 到 `noon-sandbox-api-gateway.noon.partners`，**login 仍走生产**；`env` 由 `md_partner_account.env` 控制。
6. 每次登录生成**新 JWT**；禁止复用旧 token。
7. 角色不足返回 `PERMISSION_DENIED` → 提示到 Portal 给 service account 配角色后重新认证。**A-04：当前角色为只读**，本版全部 API 调用均为读操作，实测通过；后期启用写端点须先按 N-16 调整该 service account 角色（每 partner 仅 1 个）。
8. 全流程写 `int_api_log`（`X-Request-Id`、http_status、error_code、latency、时间戳）。

**M0 实测校准（N-21）**：Login 返回 `Set-Cookie: _npsid, _nprtnetid`（仅记录名称，值不落日志）；`x-request-id` 实测格式 `req_000_<32hex>`；官方 `APILogin` 页的 Python/Go/Java 示例确认 JWT claims 为 `sub=key_id` / `iat=<整秒>` / `jti=<UUID>`，与上述流程一致。**生产 login 的 cookie 可跨 host 用于沙箱**（实测 200），但沙箱跳过权限校验且返回 deterministic mock，故 R3/R4/R5 结论一律以生产网关只读调用为准。

### 7.2 端点清单（MVP 使用 / 缺口）

| 用途 | 端点 / 能力 | 状态 |
| --- | --- | --- |
| 登录 / 身份 | `APILogin` / `whoami` | ✅ 已公开、**M0 实调 200**；Not on MCP |
| Offer 健康 / 双站点 | `GetProductOffers` | ✅ MVP 使用；需真实 `partner_sku`（O-08）；**Not on MCP**（N-25） |
| 退货 | `ListReturnReferences` | ✅ MVP 使用；Not on MCP |
| 库存 | `GetStock` / `UpdateStock`（绝对值） | ❌ **R3 已关闭**：`ListWarehouses` 实调无 FBN 仓类型 → 不推送；`GetStock` 仅 MCP Read 可用 |
| 报表导出 | `GetExportCategoryList` / `CreateExport` / `GetExportStatus` | ✅ **R4 已关闭**：12 个类别码实测（N-23）；**C-1 修正：仅「发现」层已达，12 码全部 `is_usable=0`**；`CreateExport` 权限 O-07 未验证；三步均 Not on MCP |
| 事件 | `ListEventTypes` / `ListDestinations` / `CreateHttpsDestination` | ✅ **R5 已关闭**：3 种事件、`destinations=[]`（N-24）；前两者 MCP Read 可用 |
| 定价 / 商品 | Pricing / Catalog / Content 端点 | 公开但本版不用（Q4 / A-04 只读） |
| **FBN 入仓 ASN** | FBN Inbound API（术语表提及） | ❌ **未公开**，提 Support Case |
| **FBN 订单** | 订单 REST API | ❌ **未公开**；改走 impex `noon_noonoms_ordersexport`（N-23） |
| **Statement / 结算 / 仓储账单** | 财务 REST API | ❌ **未公开**；改走 impex `noon_financeweb_transactionviewreportonitemlevel` / `invoicesandcreditnotes`（N-23，列头待验证） |
| **FBN 库存台账** | 库存 REST API | ❌ 未公开；改走 impex `fbn_inventoryv2_*` 三类报表（N-23） |
| **广告** | 投放 API | ❌ **未公开**，本版不做 |

### 7.3 限流、错误与重试

- 限流：per-project 固定窗口 + burst **双计数独立**；Login、CreateExport 为 1500 次/60s。客户端读 `X-Ratelimit-Remaining` / `X-Ratelimit-Burst-Remaining` / `X-Ratelimit-Retry-After`，429 用指数退避（建议初值 1s、倍率 2、上限 60s、最多 6 次）。
- 错误统一 envelope：`error{status_id, code, message, fields[{name, descriptions}], doc_url}`；映射为系统告警与可读处置指引。
- 全部请求/响应写 `int_api_log`；DEAD 任务进工作台告警。

### 7.4 凭据与密钥管理（安全红线）

| 要求 | 实现 |
| --- | --- |
| 私钥不入库 | `md_partner_account.credential_ref` 只存服务端加密文件路径 |
| 私钥不进 Git | 项目 `.gitignore` 已屏蔽 `*credentials*.json`、`*.pem`、`.env*`、`application-secret.yml` |
| 文件权限 | 服务端 `600`，所属账号专用；**Downloads 中的原文件当前为 `0644`，建议改为 `600` 并移出个人下载目录** |
| 密钥生命周期 | 1 个 service account / 最多 5 keys（N-16）；建议设 IP 白名单 + 过期时间；泄漏即在 Portal **deactivate**（即时生效），用 API User Service 轮换（N-19） |
| 会话 | 30 天；失效自动重新登录；不持久化 cookie 到日志 |
| 日志脱敏 | request/response 中不得出现 `private_key`、JWT、cookie 值 |
| 环境隔离 | **A-03：生产/沙箱共用一份 `.json`**，由 `md_partner_account.env` + `base_url`/`sandbox_url` 区分；凭据本身**不带环境标识**（`channel_identifier` 中的 `test` 是用户名，不是沙箱标记），不得据此推断环境；`md_partner_account` 只需 1 条记录。**N-21 实测**：生产 login 的 cookie 跨 host 用于沙箱返回 200，A-03 成立；但沙箱跳过权限校验、返回 mock，**不得用于验证权限或取真实字典** |
| 只读角色（A-04） | 当前 service account 仅 Read；MVP 全部 API 调用为读操作，实测通过。`CreateExport` 权限见 O-07；后期写操作须按 N-16 改该 account 角色（不能另建可写 service account） |

### 7.5 接口字段映射表（M0 实测部分已落定；文件列头仍按 A-01 可配置）

| 数据源 | 目标表 | 关键字段映射 | 状态 |
| --- | --- | --- | --- |
| `APILogin` / `whoami` | `int_api_log` / `md_partner_account` | `x-request-id`、`_npsid`/`_nprtnetid`（仅名称）、`user_code`、`username` | ✅ **M0 实测 200** |
| `ListWarehouses` | `md_warehouse` | `warehouse_code` / `display_name` / `fulfillment_system_code` / `is_active` / `next_token` | ✅ **M0 实测 5 条**（仅自持仓，非 FBN 仓） |
| `GetExportCategoryList` | `int_export_category` | `export_category_code` / `params.fields.<name>.required` / `request_id` / 三层验证列 | ✅ **M0 实测 12 类；仅发现层达成**（C-1） |
| `ListEventTypes` / `ListDestinations` | `int_webhook_event` 字典 | `event_types[]` / `destinations[]` | ✅ **M0 实测 3 种 / 空** |
| `GetProductOffers` | `md_partner_sku` | `partner_sku` / `sku` / `title` / `brand` / `offers[]`（含 `live_status`/price/stock） | ⚠️ 文档已核、**未实调**（需真实 `partner_sku`，O-08）；Not on MCP |
| impex 导出**文件** | `int_export_job` → `sal_order(_item)` / `fin_statement(_line)` / `inv_stock_*` | `file_url`、列头、金额口径、行类型 | ⚠️ 类别码已实测，**列头未实调**（O-07 / O-09） |
| **Seller Lab 订单 CSV** | `sal_order(_item)` | 列头、订单号、妥投时间、金额、费用 | ⚠️ O-01 未核到；**A-01：列头做成可配置映射**，先用人工录入样本，不阻塞开工 |
| **Seller Lab Statements & Invoices CSV** | `fin_statement(_line)` | 列头、账单号、行类型、金额、周期 | ⚠️ O-01 未核到；**A-01：账单反推 + 可配置列头**，不臆造字段 |
| **Seller Lab 费率表** | `fin_fee_dict` | 类目 × 站点 × 费率 | ⚠️ O-01 未核到；**A-01：人工录入初值 + 账单反推校准**，费率版本化留历史 |

> 完整原始响应、12 个类别码参数、54 端点 MCP 权限矩阵与 `sys_config` 建议值见 **`docs/noon-erp-api-integration-v1.0.md`**。

---

## 8. AI 外挂设计（Q12 已确认）

### 8.1 定位（两条路径同时成立）

```
路径 A（接入方式）  WorkBuddy / Codex / Claude Code
                     └─ Noon 官方 MCP，OAuth 只勾 Read(execute_api_read)
                        └─ N-25：仅 11 个 Read 工具 / 0 个写工具，能力有限
路径 B（业务场景）  首个场景 = STATEMENT_RECON 周账单差异核查
                     └─ ERP 只读视图 v_ai_* → 差异候选清单 → 人工复核
                        └─ 取数主力 = 本地视图，MCP 只是补充
```

> **能力边界（N-25，M0 全量扫描 54 个 API Reference 端点）**：官方 MCP 只暴露 11 个 Read 工具、平台**未暴露任何 Change 工具**；`GetProductOffers`、impex 三步、`whoami`、`ListReturnReferences`、FBPI 订单等 43 个端点全部 **Not on MCP**。所以 AI 外挂**拿不到 Offer 健康、报表导出、订单与账单**，这些必须由 ERP 核心用只读 service account 直连并落入 `v_ai_*` 视图。完整清单见 `docs/noon-erp-api-integration-v1.0.md` §7。

### 8.2 边界（三不碰 + 一可写）

| 对象 | AI 权限 |
| --- | --- |
| `inv_stock_ledger` / `inv_batch*` / `pur_order*` / `fin_statement*` / `fin_profit_snapshot` | ❌ 禁止写 |
| 官方 MCP `execute_api_change` / 全部写端点 | ❌ **平台未暴露任何写工具**（N-25：54 端点中 0 个 Change）—— 不是「我们没授权」，而是**工具物理上不存在** |
| `v_ai_*` 四个只读视图 | ✅ 只读（**AI 取数主力**） |
| `ai_suggestion` | ✅ **唯一可写**，且只能写 `status=PENDING` |

> **双层只读保证**：① ERP 侧 AI 只能读视图 + 写建议单；② 平台侧官方 MCP 零写工具（N-25），叠加 service account 只读（A-04）。比自己写权限校验更稳。Revoke 后已发 token 最长还活 1 小时，需在应急预案里考虑。首个场景 `STATEMENT_RECON` 只读本地视图，**不受 MCP 能力限制影响**。

### 8.3 只读视图

| 视图 | 内容 | 主要消费场景 |
| --- | --- | --- |
| `v_ai_sku_overview` | SKU + Partner SKU + 库存四态（**按 SKU×站点聚合**）+ `warehouse_count` | 补货建议、双站点对比（✅ D-01 已在 v0.3 修复） |
| `v_ai_profit` | 期间利润 L1/L2/净利 + 公式版本 | 利润归因 |
| `v_ai_statement_open` | 未闭合账单 + 争议截止日 + 对账状态 | **首个场景** |
| `v_ai_replenish` | 补货建议与可售天数 | 补货建议复核 |

> **AI 取数口径提醒**：`v_ai_sku_overview` 已改为聚合视图；同一 SKU 若有多条 Partner SKU，每行展示的是**同一份站点库存汇总**，AI 不得把多行相加。此约束已写入 v0.3 视图注释。

### 8.4 首个场景端到端：`STATEMENT_RECON`

| 步 | 动作 | 系统行为 |
| --- | --- | --- |
| 1 | 每周账单导入完成 | `fin_statement.reconcile_status=PENDING`，生成 `dispute_deadline` |
| 2 | AI 读 `v_ai_statement_open` + 账单行 + 本地订单/费用（只读） | 取数写 `sys_audit_log` |
| 3 | AI 输出差异候选清单 | 写 `ai_suggestion(scene=STATEMENT_RECON, status=PENDING, payload_json, confidence)`；**不修改 `matched`** |
| 4 | 财务在工作台逐条复核 | APPROVED / REJECTED，记录 `reviewed_by/at` |
| 5 | APPROVED 后由**核心系统**处理 | 更新 `fin_statement_line.matched`、生成差异清单导出件、`reconcile_status` 推进 |
| 6 | 留痕 | 建议单回写 `converted_doc_no`，全程可回放 |

**验收**：AI 输出的任何内容在人工复核前**不改变任何账单行、库存或利润数据**；差异清单可在 `dispute_deadline` 前导出。

### 8.5 场景启用顺序

`STATEMENT_RECON`（首个，只读、最痛、最可量化）→ `REPLENISH`（写 DRAFT 补货建议）→ `EN_AR_COPY`（文案，不进 ERP 也可独立运行）→ `PRICING`（后期且需 Change 权限评估，本版不启用）。

---

## 9. 里程碑与验收

### 9.1 迭代与完成判定（不写绝对工期，按依赖顺序）

| 迭代 | 交付 | DoD（完成判定） | 依赖 |
| --- | --- | --- | --- |
| **M0 环境与凭据联调** | 凭据安全落地、Login + whoami、只读角色授权、`ListWarehouses` / `GetExportCategoryList` / `ListEventTypes` / `ListDestinations` 实调、沙箱跨 host 验证、54 端点 MCP 权限扫描 | ✅ **已达成（2026-11-13 21:26）**：生产网关 7 次只读调用全部 200、`x-request-id` 齐全、**R2~R5 关闭**，结论写入 `docs/noon-erp-api-integration-v1.0.md` 与本文 §2.3；`GetProductOffers` 因需真实 `partner_sku` 留作 O-08 | 凭据 ✅、只读角色 ✅（A-04） |
| **M1 主干闭环** | FR-01~FR-06：主数据、采购、FIFO 批次、四态库存、ASN、订单/退货导入 | 一个 SKU 从采购到货到 ASN 入仓跑通；FIFO 跨批扣减手算一致；四态守恒 | 人工录入订单样本 + 可配置列头映射（**A-01，不阻塞**） |
| **M2 财务与利润** | FR-07~FR-09、FR-12~FR-13：两类账单、15 天倒计时、差异清单、L1/L2 利润、重算、补货建议 | 周账单在争议期内出差异清单；三档口径并存；建议单能转采购单 | 人工录入账单 + 费率初值由账单反推（**A-01，不阻塞**） |
| **M3 集成与 AI 外挂** | FR-10~FR-11：四通道、限流退避、webhook 幂等、官方 MCP Read、首个场景 | AI 实测无法写业务表；差异候选清单可复核导出；API 关闭时 CSV 通道仍跑通闭环 | M1/M2 数据 |

### 9.2 端到端 UAT 主用例（编号 UAT-01）

| 步 | 操作 | 期望 |
| --- | --- | --- |
| 1 | 建 SKU + AE/SA 两条 Partner SKU 映射 | 映射唯一、可回查 |
| 2 | 建供应商与报价，生成采购单并确认 | 状态 `CONFIRMED`，带有效报价 |
| 3 | 分两次到货，价格不同 | 生成 2 条 FIFO 批次，`qty_remaining` 正确 |
| 4 | 调拨 + 登记 ASN，推进到 `RECEIVED` | `SEALED` 前不能约 slot；收货差异有记录 |
| 5 | 导入订单并置为 `DELIVERED`，销量跨两个批次 | `inv_batch_consumption` 两条，COGS 与手算一致 |
| 6 | 导入周 statement + 月度 STORAGE 账单 | 两类账单分开；`dispute_deadline = generated_at + 15d` |
| 7 | 查看差异清单与利润报表 | L1 正确、L2 独立、`NONE/PARTIAL/FULL` 三套可切、带 VAT 风险提示 |
| 8 | 生成补货建议并人工确认转采购单 | AI/系统只写 DRAFT；转单后 `source_ref_no` 可回溯 |
| 9 | 验证 AI 只读边界 | ① 核对官方 MCP 工具清单中**零个写工具**（N-25）；② 在**受控/沙箱环境**用只读账号调写端点应返回 403/`PERMISSION_DENIED` 并写审计日志；**不在生产盲测写操作**（O-11） |
| 10 | 断掉 API 通道，仅用 CSV 重复步 5–7 | 闭环仍跑通 |

### 9.3 上线判定

> 不再依赖 Excel 手工台账；周账单能在 15 天争议期内出差异清单；FIFO COGS 可溯源；AI 只读边界实测通过；备份恢复演练通过。

### 9.4 上线检查清单

- [ ] ✅ **A-04：service account 只读角色已配并完成 M0 生产实调**；**A-03：同一份凭据 + `env` 明确区分生产/沙箱**（N-21 跨 host cookie 已验证）；凭据服务端 `600` + 加密文件（**仍待用户手动处理**）
- [ ] `.gitignore` 生效，仓库无任何私钥文件
- [ ] IP 白名单 / key 过期时间已配置；泄漏应急处置人已指定（R10 仍待处理）
- [ ] ✅ **R2~R5 已关闭**：仓清单、12 个导出类别码、3 种事件类型、`x-request-id` 格式均已实测，`sys_config` 初值按 `docs/noon-erp-api-integration-v1.0.md` 第 12 节的**建议值**落库（未改动 DDL）
- [ ] 8 类导入模板已就绪、列头映射可配置（**A-01：人工录入 + 账单反推，不等待 Seller Lab 字段核查**）；impex 自动导出前先验证 O-07 权限
- [ ] 当前数据库脚本为 `schema-v0.4.sql`（Codex 6 项修订 + D-01~D-03 已修复），非 v0.3/v0.2
- [ ] 每日备份 + binlog，恢复演练通过
- [ ] 六类告警（NFR-15）已配置接收人
- [ ] 回滚方案：单 jar 版本回退 + 数据库备份恢复

---

## 10. 风险与开放问题

### 10.1 风险登记（R）

| 编号 | 风险 | 状态 / 建议动作 |
| --- | --- | --- |
| **R1** | FBN Inbound(ASN) / FBN 订单 / Statement / 广告 **无实时 REST 端点**（N-08/N-09） | 提 Support Case 索取；**N-23 补充**：impex 有 12 类报表可覆盖 FBN 订单/库存台账/item 级交易，作为 CSV 通道自动化升级，列头与权限待验证（O-07/O-09） |
| **R2** | Service Account 凭据 | ✅ **已关闭（M0 生产实调）**：只读角色 Login/whoami 200、字段核对通过、`x-request-id` 正常；A-03/A-04 已定。剩余仅 R10 的文件权限 `600` + IP 白名单/过期（用户手动处理） |
| **R3** | Stock API 是否适用 **FBN 仓** | ✅ **已关闭（N-22）**：`ListWarehouses` 全量 5 仓均为 `directship_v2`/`fbpi`，无 FBN 仓类型 → **不推送库存**，四态库存仍以本地台账 + ASN + FBN 库存报表为准 |
| **R4** | `export_category_code` 清单 | ✅ **已关闭（N-23）**：实调 12 类，含 FBN 库存台账/明细/库龄、订单导出、item 级交易、发票贷项；`namshi_*` 按 Q9 不使用 |
| **R5** | Event Types 清单 | ✅ **已关闭（N-24）**：仅 3 种且无 FBN 订单事件、`destinations=[]` → webhook 仅作退货辅助，订单仍走导出 |
| **R6 / O-01** | **Seller Lab 后台字段未核到**（店铺/站点结构、FBN 仓编码、Statements & Invoices 导出列头、真实费率表） | ✅ **A-01 已决议：不阻塞开工**。字段映射做成可配置列头，先用人工录入 + 账单反推；**不用推测补齐**。后续拿到样本只改映射配置不改表；若需补核，回复 `continue` |
| **R7** | 不做 VAT 使 L1 毛利系统性虚高 | 已接受；报表中固定提示（FR-08.7）；字段与科目位保留 |
| **R8** | Q8「部分合并」具体科目组合未定 | 实现 `PARTIAL` 界面勾选 `included_in_partial`，不写死规则 |
| **R9** | Q2「一店两站」与「一照一店、两站独立」口径冲突 | 三层模型可配置；待 Seller Lab 核完定死 |
| **R10** | 凭据文件权限 `0644`、默认全 IP 可用且永不过期（N-16/N-20） | 建议 `chmod 600` + IP 白名单 + 过期时间；泄漏即 deactivate |
| **R11** | D-01：`v_ai_sku_overview` 多仓放大库存 | ✅ **已关闭**：`schema-v0.3.sql` 改为按 SKU×站点聚合 + `warehouse_count`，见 §6.4 |

### 10.2 开放问题（O；O-01 / O-02 已按 A-01 / A-03 关闭，O-07~O-11 为 M0 联调新增）

| 编号 | 问题 | 影响 | 建议默认 |
| --- | --- | --- | --- |
| **O-01 ✅ 已关闭** | Seller Lab 导出字段 / 真实费率表 / 仓编码 | 决定导入解析与费用字典初值 | **A-01 决议**：人工录入 + 账单反推、列头可配置；不阻塞 M0~M2；拿到样本只改映射配置 |
| **O-02 ✅ 已关闭** | 生产与沙箱是否共用同一 `.json` 凭据 | 决定 `md_partner_account` 记录数 | **A-03 决议**：共用一份凭据，用 `env` + host 区分；`md_partner_account` 只需 1 条 |
| O-03 | `PARTIAL` 默认勾选哪些 L2 科目 | 影响净利报表 | 默认不勾选，由财务在界面配置 |
| O-04 | 大促系数 `event_factor` 取值 | 影响补货量 | 默认 1.0，按站点手动调整 |
| O-05 | 账单争议由谁在 Seller Lab 提交 | 影响流程责任 | 财务导出差异清单后在 Seller Lab 人工提交 |
| O-06 | 是否需要官方 MCP 之外再加一层 ERP 只读 API | 影响 AI 取数灵活性（N-25 已确认 MCP 仅 11 个 Read 工具） | 首个场景只用视图即可，后期再加 |
| **O-07** 🔴 | 只读角色能否调用 `CreateExport` / `GetExportStatus` | 决定 impex 自动导出通道是否可用，进而决定 FBN 订单/账单能否自动化 | **未验证前按「不可用」**，A-01 手工导出 + 账单反推不变；需单独评估授权 |
| **O-08** | 真实 `partner_sku` 样本 | FR-02.3 `GetProductOffers` 实读与 Offer 字段落地 | 从 Seller Lab 取 1 个真实 SKU，不臆造 |
| **O-09** | impex 导出文件的实际列头、金额口径、行类型枚举 | 决定导入解析规则与 `fin_fee_dict` 初值 | 列头做成可配置映射，拿到样本再填 |
| **O-10** | KSA 站点仓编码 | `md_warehouse` 初始化 | 生产 `ListWarehouses` 仅返回 5 条 `AE` 后缀仓，暂按 UAE 建模，KSA 待补 |
| **O-11** | 只读账号写端点的拒绝信封（403 / `PERMISSION_DENIED`） | UAT-01 步 9 判据 | 受控环境验证，**不在生产盲测写操作** |

---

## 11. 附录

### A. 术语表

| 术语 | 含义 |
| --- | --- |
| FBN | Fulfilled by noon，官方仓履约，需 ASN 入仓；佣金 + 出库费 + 月度仓储费 |
| FBP / FBPI | 卖家自持库存自发货 / 其 API 集成形态（本版不实现） |
| Global / NGS | 中国直发供价结算（本版不实现） |
| ASN | Advanced Shipping Notice，FBN 入仓货件；seal 后才能约仓库 slot |
| Partner SKU | Noon 侧商品编码，与本地 `sku_id` 按站点映射 |
| Offer | 每国家 + 每 business model 一条商品报价记录；`GetProductOffers` 可跨国家读取 |
| impex | Noon Reports 异步导出（CreateExport / GetExportStatus） |
| statement | 平台结算账单；WEEKLY（周三生成）与 STORAGE（月度仓储）两类 |
| FIFO / COGS | 先进先出批次成本 / 销货成本，本版唯一来源是 `inv_batch_consumption` |
| L1 / L2 | SKU 贡献毛利 / 期间与批次费用池 |
| destination | Event Notifications 的 HTTPS 接收端，每项目最多 5 个 |
| MCP | Noon 官方 Model Context Protocol Server，Read/Change 权限分离 |
| `X-Request-Id` | Noon API 响应头，提 Support Case 必备 |

### B. 编号索引

| 前缀 | 含义 | 范围 |
| --- | --- | --- |
| F- / C- | v0.1 已确认事实 / 口径冲突项 | F-01~F-19（F-07/F-08 作废）、C-01~C-07 |
| N- | 一手核查事实 | N-01~N-25（N-16~N-20 凭据核对；**N-21~N-25 M0 生产实调与 MCP 全量扫描**） |
| Q- | 用户对齐问题 | Q1~Q14，全部已答复 |
| FR- | 功能需求 | FR-01~FR-15 |
| NFR- / RPT- | 非功能需求 / 报表 | NFR-01~15、RPT-01~10 |
| R- / O- | 风险 / 开放问题 | R1~R11（**R2~R5 已关闭**）、O-01~O-11（O-01/O-02 已关闭；O-07~O-11 为 M0 新增验证项） |
| D- | DDL 修订项 | D-01~D-03（✅ v0.3 已全部关闭） |
| A- | 评审决议 | A-01~A-04（A-01~A-03「按推荐走」；**A-04 只读权限**） |
| UAT- | 端到端验收用例 | UAT-01 |

### C. 来源与核查时间

| 来源 | 时间 (GMT+8) | 用途 |
| --- | --- | --- |
| `docs/noon-erp-requirements-v0.1.md` | 2026-11-12 22:48 | 万墨模块清单、业务闭环、F/C 事实与来源链接 |
| `noon-docs.noonpartners.dev`（123 页 sitemap） | 2026-11-12 23:47 | N-01~N-15：API 域、鉴权、限流、错误、沙箱、术语、MCP、Stock/Offer/impex |
| `developer.noon.partners/?project=PRJ468049` | 2026-11-12 23:47 | Portal 登录态、项目码、Request Logs / Rate Limit Analytics / Support Cases |
| `/docs/authentication/getting-credentials` | 2026-11-13 17:00 | N-16：1 service account / 5 keys / 30 天会话 / IP 白名单与过期 / 角色 |
| `/docs/authentication/authenticating-requests` | 2026-11-13 17:00 | N-17：`iat` 整秒、±5 分钟、每次新建 JWT、User-Agent |
| `/docs/getting-started/quick-start` | 2026-11-13 17:00 | N-18：凭据绝不进 Git；JWT→cookie 流程 |
| `/Users/dream/Downloads/noon_credentials_sensitive.json` | 2026-11-13 17:00 | N-20：6 字段、RSA 2048 PKCS#8、项目码一致性（**私钥内容未输出、未入库**） |
| `docs/noon-erp-schema-v0.2.sql` | 2026-11-12 23:47 | 历史基线：44 表 + 4 视图，表名口径来源 |
| `docs/noon-erp-schema-v0.3.sql` | 2026-11-13 20:22 | Codex 评审基线：D-01~D-03 修订、A-03 环境策略；44 表 / 4 视图，保持不变 |
| `docs/noon-erp-schema-v0.4.sql` | 2026-11-17 22:00 | **当前开发依据**：Codex 6 项修订；45 表 / 4 视图，新增 `int_export_category` |
| 生产网关 7 次只读实调（Login / whoami / ListWarehouses / GetExportCategoryList / ListEventTypes / ListDestinations / 沙箱） | 2026-11-13 21:20 | N-21~N-24；R2~R5 关闭；原始响应与 `x-request-id` 见 `api-integration-v1.0.md` §3 |
| 54 页 `/docs/api-reference/**` 的 `MCP access` 标注 | 2026-11-13 21:21 | N-25：11 Read / 0 Change / 43 Not on MCP |
| `/docs/api-reference/authentication/auth-service-api-login.md`、`/docs/overview/sandbox.md` | 2026-11-13 21:19 | 官方 Python/Go/Java JWT 示例；静态沙箱口径与「跳过权限校验」 |
| `docs/noon-erp-api-integration-v1.0.md` | 2026-11-13 21:26 | **M0 实测基线**：完整原始响应、12 类别码、5 仓、3 事件、54 端点 MCP 矩阵、字段映射、`sys_config` 建议 |

### D. 变更记录

| 版本 | 变化 |
| --- | --- |
| v0.1 | 业务流程九环节、模块清单 M1~M12、Q1~Q14 对齐清单、F/C 事实基线 |
| v0.2 | 推翻 v0.1 的 F-07/F-08；按 Q1~Q14 收敛 FBN 单模式；FIFO、双层利润、44 表 DDL、R1~R9 |
| PRD v1.0 | Q12 确认并双路径落地；凭据字段核对完成、R2 更新；新增权限矩阵、状态机、FR 逐条验收标准、NFR、页面 IA、UAT、里程碑 DoD、DDL 待修订项、开放问题；创建 `.gitignore` 防私钥误提交 |
| **PRD v1.0.1** | 用户「问题按照推荐的走」→ **A-01/A-02/A-03 三项决议闭环**：O-01 不阻塞开工、授权修订 DDL 产出 `schema-v0.3.sql`（D-01~D-03 关闭）、生产沙箱共用凭据用 `env` 区分；同步更新决议表、FR-10.2/FR-13.2、§6.4、§7.4/§7.5、§8.3、M1/M2 依赖、上线清单、R2/R6/R11、O-01/O-02、编号索引与来源 |
| **PRD v1.0.2** | 用户「账号权限改成只能读」+「继续完成未完成的任务」→ **A-04 只读权限决议 + M0 生产实调完成**：Node 原生 RS256 JWT 直连生产网关，7 次只读调用全部 200；**R2/R3/R4/R5 关闭**；新增 N-21~N-25、O-07~O-11；修正「Statement 无 API」「MCP 可供 AI 取数」两处口径，明确 MCP 仅 11 Read / 0 Change；同步 §0/§2.2/§2.3/§2.5/§2.6/§7.1/§7.2/§7.4/§7.5/§8.1/§8.2/§9.1/§9.2/§9.4/§10/§11；**DDL 保持 v0.3 不变**；新增配套文档 `docs/noon-erp-api-integration-v1.0.md` |
| **PRD v1.0.3** | Codex 静态交叉评审 6 项全部核实成立：类别码以实测原始响应重写并三层验证、退货红冲与跨期案例、利润三档/重算/站点分摊与守恒、订单幂等与状态更新、拆批在途与过账唯一责任、原型模拟边界与内存联动；配套 DDL v0.4、原型与设计说明同步修订 |

### E. 评审决议记录（2026-11-13 20:22 / 21:26，已全部闭环）

| 编号 | 议题 | 决议 | 落地 |
| --- | --- | --- | --- |
| **A-01** | O-01 是否现在补核 Seller Lab 字段 | **不补核、不阻塞开工**：人工录入 + 账单反推，导入列头做成可配置映射 | §2.2、§7.5、§9.1、§9.4、§10.1 R6、§10.2 O-01 |
| **A-02** | 是否授权直接修订 D-01~D-03 | **授权**：产出 `docs/noon-erp-schema-v0.3.sql`，v0.2 保留为历史基线 | §6.4、§8.3、§11 C |
| **A-03** | 生产/沙箱凭据策略 | **共用一份 `.json`**，由 `env` + 网关 host 区分，`md_partner_account` 只需 1 条 | §2.2、§2.6、FR-10.2、§7.4、§9.4、§10.1 R2、§10.2 O-02 |
| **A-04** | Service Account 权限（用户 21:18 告知） | **设为只读**；MVP 全部 API 调用为读操作，已完成生产实调；与 AI 外挂 MCP OAuth 互不干扰；后期写操作须按 N-16 改该 account 角色 | §2.2、§7.1、§7.4、§10.1 R2、`api-integration-v1.0.md` §9 |

O-03~O-06 按 §10.2 默认值执行，不需要逐条再确认。**M0 联调类事项已于 2026-11-13 21:26 全部完成，R2~R5 关闭**；剩余仅为开发执行 + O-07~O-11 的验证项（均不阻塞 M1 开工）。

---

**文档结束 · PRD-NOON-ERP-001 v1.0.3**
