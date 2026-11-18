# Noon ERP 原型验证报告（2026-11-18）

执行者：WorkBuddy　｜　触发：Codex 在协作文件「Codex → WorkBuddy：重跑验证并保留证据」中的要求
基线 commit：`360c26b`（验证时 HEAD）　｜　对象：`prototype/index.html`（内部 v1.1）

本报告只报告**实际执行过**的检查。所有证据（脚本、输出、截图）保存在项目目录内，不依赖临时目录。

---

## 0. 环境与运行命令

| 项 | 值 |
|---|---|
| Node | v22.22.2 (`node -v`) |
| Python | python3（静态服务器） |
| 浏览器 | WorkBuddy 内置浏览器（ego-browser 真实 Chromium 渲染） |
| 工作目录 | `/Users/dream/Work/noonERP` |

运行命令（可复现）：

```bash
cd /Users/dream/Work/noonERP
# 语法检查（抽取 script）
python3 -c "import re;h=open('prototype/index.html',encoding='utf-8').read();open('docs/verification/extracted.js','w',encoding='utf-8').write('\n'.join(re.findall(r'<script[^>]*>(.*?)</script>',h,re.S)))"
node --check docs/verification/extracted.js
# 数据模型验证（含 24 项断言）
node docs/verification/verify-chain.js | tee docs/verification/verify-chain-output.txt
# 浏览器验证见本文第 4 节，截图存于本目录
```

证据文件（绝对路径）：

| 文件 | 内容 |
|---|---|
| `/Users/dream/Work/noonERP/docs/verification/extracted.js` | 从原型抽取的 JS（语法检查对象） |
| `/Users/dream/Work/noonERP/docs/verification/verify-chain.js` | 可复现验证脚本（24 项断言） |
| `/Users/dream/Work/noonERP/docs/verification/verify-chain-output.txt` | 脚本实际运行输出 |
| `/Users/dream/Work/noonERP/docs/verification/P26_profit.png` | 利润页 P-26 浏览器截图 |
| `/Users/dream/Work/noonERP/docs/verification/P01_conservation.png` | 演示面板 P-01 浏览器截图 |

---

## 1. 八步演示链：批次与库存逐笔预期 vs 实际

`node docs/verification/verify-chain.js` 驱动 `demoStep` 全链路，逐步快照如下（CN01=国内仓，AE-FBN=海外仓）：

| 步 | 动作 | CN01 可用/在途 | AE-FBN 可用/不可售 | PO未收 | 批次状态 |
|---|---|---|---|---|---|
| 1 | 确认采购单 | 0/0 | 0/0 | 100 | — |
| 2 | 部分到货 40 | 40/0 | 0/0 | 60 | BAT-001=40/OPEN |
| 3 | 发运 40 | 0/40 | 0/0 | 60 | BAT-001=0, BAT-001-02=40/IN_TRANSIT |
| 4 | 短少收货 38 | 0/0 | 38/0 | 60 | BAT-001-02=0/CLOSED, BAT-001-03=38/OPEN |
| 5 | 妥投 10 | 0/0 | 28/0 | 60 | BAT-001-03=28/OPEN |
| 6 | B级退货2 | 0/0 | 28/2 | 60 | 可用不变，报废2件转不可售 |
| 7 | 周账单 | 0/0 | 28/2 | 60 | — |
| 8 | 利润三档 | 0/0 | 28/2 | 60 | — |

关键守恒断言（实际均通过）：

- **未到货 60 件不建批次**：全程仅 3 个批次（BAT-001 / -02 / -03），60 件只挂 PO 未收，不进任何可用台账。✓
- **在途批收货后 CLOSED 且 qty=0**：BAT-001-02 状态 CLOSED、余额 0，不与目的仓 38 重复统计。✓
- **短少 2 可追溯**：`MV-DEMO-004` type=TRANSFER_SHORT qty=−2，发40−收38=短少2，成本血缘仍指父批。✓
- **B级报废不回可售**：AE-FBN 可用保持 28、`unsellable=2`，`MV-DEMO-006` 记报废去向，成本已在 RETURN_LOSS 全额计损。✓
- **父子批不同时计可售**：父批 BAT-001=0、在途批=0，只有目的仓 BAT-001-03 计入。✓
- **终态**：CN01 可用0/在途0；AE-FBN 可用28/不可售2；PO未收60。✓

## 2. 三档利润：基于演示订单流水，赔偿单次

`orderProfit()` 实际输出：

```
{"revenue":120,"platformFee":76.5,"cogs":100,"comp":6,"l1":-56.5}
```

- 收入 = 毛收入150 − 退款30 = **120**；平台费 = 82.5 − 赔偿6 = **76.5**（赔偿只在 COMPENSATION 减一次）；COGS = 100−20(红冲)+20(损失) = **100**；L1 = 120−76.5−100 = **−56.5**。✓

三档批次（`src=DEMO_CHAIN`，可沿 `calc_batch_no → STMT-DEMO-W47 → CON-DEMO-005/006/007` 追溯）：

| calc_batch | 口径 | L1 | L2池 | 净利 |
|---|---|---|---|---|
| CB-DEMO-001 | NONE | −56.5 | 0 | −56.5 |
| CB-DEMO-002 | PARTIAL | −56.5 | 0 | −56.5（未勾选科目） |
| CB-DEMO-003 | FULL | −56.5 | 77.16 | −133.66 |

`net == l1 − pool` 逐档成立。✓

**未勾选 vs 勾选部分费用**：未勾选时演示 PARTIAL 池 = 0；勾选 FREIGHT+AD_SPEND 后演示池 ≈ 52.16（48.2+3.96）、静态全站池 = 5216（4820+396）。✓（浮点尾差用容差比较）

**历史批次不覆盖**：先放入 `CB-HIST-000` 再跑完整链路，该批次仍在 `calcBatches` 中。✓

## 3. 两维度独立切换

| 操作 | 维度A policy | 维度B skuAlloc |
|---|---|---|
| 初始 | NONE | false（默认关闭） |
| 切维度A → PARTIAL | PARTIAL | **仍 false** |
| 点维度B开关 | **仍 PARTIAL** | true |

结论：**切换站点费用口径不会自动开启 SKU 分摊；切换 SKU 分摊开关不会改变站点费用口径**。二者互不自动改变。✓

**页面展示 vs 实际模拟计算的覆盖边界**（据实说明）：

- **页面展示层（浏览器截图可见）**：三档批次表、L1/L2/净利数值、两维度开关状态、守恒链"已发生/待执行"文案、ΣSKU=SITE 守恒说明、静态示例标注——均为**真实 DOM 渲染**，见 `P26_profit.png` / `P01_conservation.png`。
- **实际模拟计算层（脚本断言）**：`demoStep` 全链路数值、`orderProfit()`、`demoAllocFor`/`allocFor`、两维度状态变量——均在 node 中**真实执行**并断言，见 `verify-chain-output.txt`。
- **未覆盖**：SKU 分摊开关"开启后"的逐 SKU 金额分摊**仅为页面文案与状态演示**，原型未实算逐 SKU 分摊数字；生产 API 权限/导出文件、数据库落库、跨页面持久化均**不在本原型验证范围**。

## 4. 浏览器真实渲染检查

启动静态服务器（`cd /Users/dream/Work/noonERP/prototype && python3 -m http.server 8765`），用内置 Chromium 打开 `http://127.0.0.1:8765/index.html`，实际执行：

| 检查 | 预期 | 实际 |
|---|---|---|
| 守恒链 step=2（到货后） | 未执行步骤显示"待执行"+"预期" | 待执行 10 处、已发生 2 处、含"预期：" ✓ |
| 守恒链 step=8（全跑完） | 全部节点已发生 | 已发生 6 处、待执行仅剩下一步按钮区 1 处 ✓ |
| P-26 演示链实算 | 显示来源标签、L1=−56.5、STMT-DEMO-W47 追溯、静态示例区分 | 四项全部命中 ✓ |
| 两维度切换 | 切A不影响B、切B不影响A | init NONE/false → 切A PARTIAL/false → 切B PARTIAL/true ✓ |
| 截图留存 | 存项目内非临时目录 | `docs/verification/P26_profit.png`、`P01_conservation.png` ✓ |

---

## 5. 失败处理记录

本次验证过程中出现 3 处**脚本自身问题**（非原型缺陷），已逐一修复后重跑：

1. `orderProfit` 为 `DEMO` 对象方法而非全局函数 → 导出改为 `DEMO.orderProfit.bind(DEMO)`。
2. `new Function` 不返回 module → 改为从传入的 `module.exports` 读取。
3. `DEMO_STEPS` 未导出、浮点尾差 52.160000000000004 → 改用 `app.steps`、容差比较。

修复后 `node docs/verification/verify-chain.js` **24/24 通过，EXIT=0**。`node --check docs/verification/extracted.js` 通过（SYNTAX_OK）。

## 6. 边界声明（保留）

- 全部为**浏览器内存演示 + node 数据模型模拟**，不落库、未部署，原型不得称已上线 ERP。
- 演示 L2 池 ¥77.16 为全站 ¥7,716.58 的 1/100 人为缩放示例，**非实际订单已核实费用**。
- 生产 API 导出权限与导出文件仍未实测（O-07/O-09），类别"发现≠可创建导出任务"口径不变。
- 维度B（SKU 分摊）是否在某特定报表默认启用，仍待用户拍板；本版默认关闭，逐 SKU 分摊金额未在原型实算。
