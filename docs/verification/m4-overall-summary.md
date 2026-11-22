# M4 库存与 FIFO · 可独立开发范围整体汇总（交用户通知 Codex 一次性审核）

日期：2026-11-22。基线：Codex 多轮复核（盘点 v1.2 通过、头程费用登记通过）+ 用户「先 M4 后 M5、一次性持续完成 M4 剩余可独立范围、整体完成后一次审核、不逐批等待」要求。**本文汇总 M4 可独立开发部分整体交付，供 Codex 一次性重点审核。**

---

## 1. 完成范围（M4 可独立部分）

| 批次 | 模块 | 核心闭环 | 状态 |
|---|---|---|---|
| 第一轮 | 采购入库 + 四态台账 | 入库确认→批次生成→台账 available 增（含 PARTIAL_RECEIVED 分批） | ✅ 已验收 |
| 第二轮 | 调拨 + 在途拆批 | create→ship（源批次扣减+在途子批次）→receive（在途转可用+短少 TRANSFER_SHORT） | ✅ 已验收 |
| 第三批·1 | 盘点 + 账实校正（v1.2） | create 快照→apply 快照闸门（账面变动拒绝+重盘）+零成本盘盈拒绝 | ✅ Codex 复核通过 |
| 第三批·2 | 头程费用登记 | create/list/get/result + 幂等 + 授权 + fx/feeType/period 校验（纯登记不摊 FIFO） | ✅ Codex 复核通过 |
| 第三批·3 | FBN 入仓 ASN 收货 | create→seal（扣源仓+在途子批次）→receive（在途转可用+短少 ASN_SHORT） | ✅ 本轮完成，待 Codex 审核 |
| 联调 | 跨模块关键流程 | 采购入库→调拨→ASN→盘点的批次血缘衔接 + 四态台账全局守恒 | ✅ 本轮完成，待 Codex 审核 |

---

## 2. 本轮（ASN + 联调）关键变更

**新增文件**（只增不改既有业务包）：
- 后端内核 `com.yx.noon.asn`：`AsnService` + 9 个 record/规则类（`docs/verification/m4-asn/doubao-report.md` 裁决 D1-D5）。
- 迁移 `yx-erp/sql/noon_m4_asn.sql`（幂等表 `noon_m4_asn_request` PK(request_id,action)）、`noon_m4_asn_menu.sql`（菜单 3600 段）。
- Controller `AsnController.java`（create/seal/receive/result/list/get）+ 异常映射 400/403。
- 前端 `api/noon/inbound/asn.js` + `views/noon/inbound/asn/index.vue`（新建/封单/收货/血缘详情三动作页）。
- 验证脚本 `verify-m4-asn-e2e.py` / `m4-asn-browser-check.js` / `verify-m4-asn-runtime.py` / `verify-m4-keyflow-e2e.py`。
- 文档 `docs/verification/m4-asn/contract.md`（v1 + §12 实现对照与遗留边界）、`generated/ADOPTION.md`、本文。

**关键设计**：ASN 建模为"本地仓→FBN 仓入仓调拨"，复用已验收的调拨守恒模式（源批次扣减→在途子批次→实收转 OPEN+短少 ASN_SHORT），四态台账双边守恒、成本血缘可追溯。动作级幂等 `PK(request_id,action)`；`ASNSHORT:<itemId>:<shortEventId>` 事件键防双计；`split_reason=ASN_TRANSIT`、`source_type=TRANSFER_SPLIT` 不新增枚举。

**联调链路**（一条 SKU 库存的完整生命周期，47 项断言全过）：入库 B1(100@10) → 调拨 B1→B2(短少5，仓3 available=45) → ASN B2→B3(短少2，FBN available=18) → 盘点仓1 盘亏2(available=48) → 守恒 **批次91 + 短少7 + 盘亏2 = 100** ✅。这证明下游模块能正确消费上游生成的批次、三级血缘可追溯、四态台账全局守恒。

---

## 3. 测试证据（全部独立复跑，全绿）

| 层级 | 结果 | 证据 |
|---|---|---|
| Maven 单元测试 | framework **29/29** + yxerp-noon **198/198**（含 ASN 14、调拨21、盘点15、头程9、入库12、reversal25、intake45、FIFO26、主数据18、采购13），BUILD SUCCESS | `mvn -pl yxerp-noon -am test` |
| 隔离 MySQL 8.4 真库 IT | 盘点 **7/7** + 头程 **5/5** + ASN **5/5** = **17/17**，maven_exit=0 | 各 `verify-m4-*-runtime.py`，真 CHECK/并发/真库守恒/无 PENDING |
| HTTP E2E + 真实浏览器 | 盘点 **88/88**（浏览器11）+ 头程 **75/75**（浏览器8）+ ASN **94/94**（浏览器15）+ 联调 **47/47** = **304/304**，FAIL=0 | 各 `docs/verification/m4-*/e2e-output.txt` |
| 前端构建 | vite build 通过（含 ASN 三动作页） | E2E 内实跑 |
| 生成器实调 | `log_asn` importTable→配置→batchGenCode 导出骨架成功 | `m4-asn/generated/raw/` |

ASN E2E 覆盖：create 只登记不动库存→seal 扣源仓拆在途子批次（血缘/台账双边/流水/幂等）→receive 在途转可用+短少 ASN_SHORT+守恒→幂等三态/动作隔离/跨站 FBN 拒绝/非 FBN 拒绝→浏览器 UI 走完三步。

---

## 4. 若依生成器使用情况

按用户要求实际使用生成器（三批一致）：
- **采用**：列表/分页/搜索前端骨架、菜单目录-页面-按钮三级结构与 perms 命名、domain 字段定义。ASN 页 `asn/index.vue` 保留生成骨架，扩展三动作弹窗与血缘展开。
- **未直接采用**：强事务领域逻辑（库存守恒/在途拆批/短少消耗/幂等）由手写 `AsnService` 内核承担——单表 CRUD 生成器无法表达"扣源仓→在途子批次→实收转 OPEN→短少记消耗→双边台账"业务闭环。Controller 由 CRUD 改为状态机动作入口。逐文件对照见 `docs/verification/m4-asn/generated/ADOPTION.md`（头程/盘点同理）。

---

## 5. 剩余依赖（不阻塞 M4，M5 联调）

| 项 | 依赖 | 预留/状态 |
|---|---|---|
| 不合格库存隔离 `qty_unsellable` | M5 退货 A/B/C 分级（真实来源），成本口径/能否回售待确认 | 预留 `quarantine(skuId,warehouseId,qty,sourceReturnId,grade)`；本批不实现、不臆造、不标完整验收 |
| 退货入库/冲回 | M5 退货模块 | M4 已备好四态台账与 FIFO 消耗红冲能力，M5 落地后联调 |
| 头程费用实际分摊 + 利润接入 | M6 / 业务确认 | 头程现仅登记，`allocation_flag` NONE/PARTIAL/FULL 仅标记；页面不误导为已分摊，避免漏计/重复计费（Codex 复核要求） |
| Noon 平台 FBN Inbound API | 平台 API 未接入 | ASN `source='MANUAL'`；slot 预约/tracking 未实现，头状态仅 DRAFT/SEALED/RECEIVED |

---

## 6. 业务待决（需用户/Codex 决策，不自行假定）

1. **盘点期间出入库控制**（Codex 明确要求）：现状是 v1.2 事后检测（闸门拒绝+重盘），不阻止盘点窗口内出入库。三种口径待选：A 冻结（DRAFT 期禁该 SKU/仓出入库，强规则）、B 补偿（记录逐笔变动还原，回到时刻错位风险）、C 检测+重盘（现状）。预留只读 `listInventoryMovesDuringCount(...)` 流水追溯接口。**不声称完全覆盖实物盘点时序**（详见 `m4-asn/contract.md` §12.2）。
2. **盘盈无可追溯成本**：v1.2 `ZeroCostGainException` 拒绝零成本盘盈并提示补录（已落实）；是否允许业务估值成本仍需确认。
3. **ASN 假设**（豆包 D1-D5 + 报告）：整单封单是否需行级部分发运；源仓停用是否阻塞封单；短少成本未含头程（等分摊批次）；`log_asn` 无收货时刻列（仅审计可查）；`partnerSku` 仅登记不校验归属。
4. **通用**：成本取加权平均（非最近采购价）、无审批一步过账、按 SKU 非批次盘点、幂等表无 TTL——均为待核实业务/使用限制。
5. **环境**：真实 Noon 平台、旧库升级、跨设备异常重试未验证。

**未连生产、未部署、未推送、未碰密钥。** 用户负责远程推送。请 Codex 按本文一次性重点审核，审核通过后再进入 M5。
