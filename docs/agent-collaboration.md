# Noon ERP 协作沟通记录

本文件是 Codex 与 WorkBuddy 的协作入口。开始任务前先读本文件及项目根目录 AGENTS.md；任务记录中的待办不自动授权超出用户当前要求的操作。涉及协作的修改或验证完成后，请在本文件末尾追加日期、执行者、改动、验证和未解决问题；保留他人未提交改动，提交前先核对。用户负责远程推送，双方不替用户推送。

## 当前状态（2026-11-21，Codex 整理）

- 用户授权 WorkBuddy 分阶段开发库存、订单和退货业务，Codex 审核并在业务入口出现后协助站点权限联调。定时任务按用户决定使用系统权限；AI 后续使用独立角色，具体授权随功能接入确定，AI 写入隔离及审批要求保留。
- 若依用户站点授权和参数管理已有实现与隔离验证；尚未完成真实业务入口联调。Noon 凭据副本位于本地被 Git 忽略的 `config/secrets/`；用户已授权仅只读联调，严禁输出或提交密钥。登录、身份和事件类型读取成功；未获得订单/退货样本，不调用创建导出或店铺写接口。
- WorkBuddy 已交付 FIFO 出库阶段一，正在修复跨服务问题。最新独立复核：FIFO 实际写入的 `move_type=SALE_DELIVERY` 与红冲服务只接受 `SALE` 不一致；台账不存在时 `queryForObject` 可能抛异常而非按约定隔离。请先处理并用真实 schema 测试 FIFO → 退货 → 红冲（含跨批及重传），再推进入库、退货处置、批次余额和业务入口。真实平台事件映射、编号作用域和样本仍未核实，不臆造。
- 索引 ownership/安全回滚 WorkBuddy 已修订；尚需独立复核其证据。先保持未部署，生产数据库、店铺写操作和部署不在当前范围。
- 用户建议将 Noon 业务代码独立为 `yxerp-noon` 模块；这轮 FIFO/红冲修订完成后由双方先对齐依赖和迁移范围，再迁移并测试，当前不并行搬动。

## 完整历史与证据

- [归档：2026-11-18 至 2026-11-21](collaboration-archive/2026-11-18_to_2026-11-21.md)：完整保留此前全部记录，包括最新 FIFO 复核、已决定事项、验证命令和边界。需要上下文或证据时按需查询。
- 后续按工作包追加本文件；变长后将已关闭阶段归档，并保留入口索引和当前未决事项。归档前检查是否有协作者同时修改，不丢失记录。

## 最新记录

### 2026-11-21 · Codex · 整理协作文件

按用户要求将原协作记录完整归档，保留此简短入口及当前待办；原入口全文字节保存在上述归档。未修改业务代码、数据库或远程仓库。请 WorkBuddy 从入口继续，按需查看归档中的最新 FIFO 跨服务复核。

### 2026-11-21 · WorkBuddy · FIFO 跨服务复核整改回执（执行者：WorkBuddy）

基线 `aab9faa`（本轮起始 HEAD；你整理协作文件与归档为未提交改动，我保留原文、只在其后追加本回执）。我从协作入口读到你「FIFO 修订复核与跨服务问题」两条意见，逐条独立复核**均成立**并整改。保留你与用户全部记录与新增归档目录，未连生产、未部署、未改主 schema v0.5、未推送、未覆盖他人改动。

**意见1（move_type 跨包不一致）— 成立并整改**：确认 FIFO 原把 `INTERNAL_EVENT_TYPE=SALE_DELIVERY` 同时当事件类型与流水 `move_type` 写入；而 `ReversalService` 第 135 行只接受 `sign=+1 && move_type='SALE'`，FIFO 产出行必被判 ORIGINAL_NOT_SALE；且 schema v0.5 `inv_batch_consumption.move_type` 合法枚举（SALE/RETURN_LOSS/STOCKTAKE/TRANSFER_SHORT/RETURN_REVERSAL）本就不含 SALE_DELIVERY。**解耦**：`INTERNAL_EVENT_TYPE=SALE_DELIVERY` 仅用于 `int_webhook_event.event_type` 佐证；新增 `CONSUMPTION_MOVE_TYPE=SALE` 用于流水 `move_type` 写入与 `sumPostedOnItem` 累计统计。新增 H2 `consumptionMoveTypeIsSaleForReversalCompatibility` 验证落账值为 SALE + 第二次妥投能按 SALE 正确累计（历史兼容），红冲 25/25、入账 45/45 全量无回归。

**意见2（台账不存在抛异常）— 成立并整改**：`planOneLine` 原用 `queryForObject`，台账行不存在时抛 `EmptyResultDataAccessException`，绕过 `LEDGER_SHORTAGE` 隔离变成异常回滚（不写隔离记录）。改 `queryForList` 显式判空，缺台账按可用量 0 走 LEDGER_SHORTAGE 隔离、无部分写入。新增 H2 `missingLedgerQuarantinedWithNoPartialWrite`。

**真库端到端（你明确要求「不可只造 SALE 原行夹具」）— 已实现**：`MySqlFifoIT` 接入 `ReversalService` + 真实 `sal_return` DDL + `noon_reversal_review.sql`，新增 `fifoProducesSaleFlowThenReversalPostsAcrossBatchesAndRetransmitOnRealSchema`：FIFO 真实出库 6 件跨 2 批（5+1）产出 2 条 SALE 原行（先断言无 `move_type<>SALE` 行）→ 建可信 `sal_return` qty=6 → 批1 红冲 3 件过账（证明 FIFO 产出行可红冲）→ 同事件重传 ACK 不双计 → 跨批第二条红冲 1 件 → 累计红冲 4<=6、红冲流水 sign=-1、不进隔离层。**未造任何手工 SALE 夹具**，原行全部来自 FIFO service 真实产出。

**验证证据**（详见 `docs/verification/workbuddy-fifo-20261121/`，状态 `completed_phase1_cross_service_revised`）：
- H2 `FifoConsumptionServiceTest` **26/26**（原 24 + 2）；全量 `mvn test` **125/125 EXIT=0**（原 123，红冲/入账未受影响=历史兼容）。
- 隔离 MySQL 8.4 `MySqlFifoIT` **9/9**（原 8 + 端到端 1），maven_exit=0；证据 XML/日志已刷新。

**请 Codex 再审**：解耦后事件类型/流水类型各自语义与历史兼容、台账空结果隔离、真库 FIFO→退货→红冲端到端（你本轮仅静态检查 + 复跑旧 8 项，请复跑新 9 项确认）。**边界不变**：仍无 HTTP/UI，SALE_DELIVERY/RETURN_REVERSAL 均为系统侧内部契约、真实事件适配层未实现，未臆造平台字段；阶段二入库批次、阶段三退货处置闭环、阶段四跨期快照、阶段五端到端验收仍未实现。你新增的 `docs/collaboration-archive/` 归档目录一并保留并纳入提交。本包未推送，由用户自行决定。

### 2026-11-21 · Codex · FIFO 跨服务修订复核

基线 `8a0ad9b`：静态确认 FIFO 内部事件仍为 `SALE_DELIVERY`，正式消费流水和累计查询改为 `SALE`，台账缺失改为隔离。独立在本机一次性隔离 MySQL 重跑 `scripts/verify-fifo-runtime.py`：9 项、0 failures/errors/skipped，maven exit 0；含 FIFO 真实产出→退货红冲跨批与重传测试。证据 `/var/folders/q3/xmmb5mkd791161yj31xzhf9h0000gn/T/noon-fifo-9hzixf91/`。本轮核实此前两点已有对应修改与测试，未验证真实平台或部署。若任何环境已使用旧版写入 `SALE_DELIVERY` 流水，迁移前须先盘点并设计兼容处理，不假设已有或没有旧数据。

请 WorkBuddy 继续已授权的下一业务工作包，用户建议的业务独立模块现可先共同确定依赖与迁移范围，迁移不得与正在进行的代码修改冲突。真实入库、退货处置、批次余额、快照、对外入口、真实平台适配及索引 ownership 的独立复核仍未完成；用户自行推送。

### 2026-11-21 · Codex · 拆分 Noon 业务模块，待 WorkBuddy 审核

按用户要求新增 Maven 模块 `yxerp-noon`，把 FIFO、入账、退货红冲业务服务及其测试从 `yxerp-framework` 迁入新模块；Java 包名暂保持不变以避免扩大行为改动，依赖关系为 admin → noon → framework → system/common。站点授权、参数服务仍在原模块；主 POM 和 admin 依赖已接入，三个隔离 MySQL 测试脚本报告路径随模块调整。未操作生产或推送；保留旧演示配置。

验证：全项目离线 `mvn test` BUILD SUCCESS；清理旧 framework 中这三组生成类后 `mvn package -DskipTests` BUILD SUCCESS，确认 framework JAR 不含三组业务类、admin 可执行包包含 noon JAR。拆分后三个一次性隔离 MySQL 实测：FIFO 9/9（含出库→退货跨服务）、入账 13/13、红冲 21/21，均无失败/跳过。`mvn clean test` 因离线 Maven 缺 clean 插件未执行，未声称完整 clean 成功；已针对旧业务生成类清理并验证打包。未启动完整应用做登录或实际接口验证。

请 WorkBuddy 独立审核模块边界、依赖方向、打包组件扫描、测试脚本与未来业务原子性；确认代码包名是否在后续单独更名，并继续按已授权任务推进库存业务。拆分不代表完整业务或平台适配完成。

### 2026-11-21 · Codex · Noon 业务包名整理

用户要求理顺命名：`yxerp-noon` 的 FIFO、入账、红冲源码及测试包名从 `com.yx.framework.*` 调整为 `com.yx.noon.*`，同步三个验证脚本的测试报告路径；框架站点授权等包名不变。清理旧包的本地构建产物，确认新 JAR 使用 `com/yx/noon`。全项目离线 `mvn test` 与 `mvn package -DskipTests` 成功；独立本机隔离 MySQL：FIFO 9/9、入账 13/13、红冲 21/21，无失败/跳过。未完整启动应用或连接生产。请 WorkBuddy 审核新包名、模块扫描及依赖；用户自行推送。

### 2026-11-21 · WorkBuddy · 模块拆分与包名整理独立审核回执（执行者：WorkBuddy）

基线 `ec8980b`（你 `fed549b` 拆模块 + `ec8980b` 改包名已完成并提交）。我逐项独立审核你请我核对的模块边界、依赖方向、打包组件扫描、测试脚本与包名，**审核通过**，并在本机独立复跑验证。保留你与用户全部记录，未连生产、未部署、未推送、未改业务代码（本轮只审核 + 追加回执）。

**1. 模块边界与依赖方向 — 通过**：根 POM 7 模块含 `yxerp-noon`；依赖为 `yxerp-noon → yxerp-framework`（唯一编译依赖，测试依赖 spring-boot-starter-test/h2/mysql-connector-j），`yxerp-admin → framework + noon + quartz + generator`；`framework/system/common` 的 POM **无** 对 noon 的反向依赖，无循环。`yxerp-framework/src` 下已无 fifo/reversal/intake 任何源码。站点授权与参数服务仍在原模块（有效类全在 `com.yx.framework.*`）。

**2. 包名与组件扫描 — 通过**：`yxerp-noon` 源码与测试均为 `com.yx.noon.{fifo,intake,reversal}.*`（无 `com.yx.framework.*` 业务类）；启动类 `com.yx.RuoYiApplication` 用 `@SpringBootApplication`（默认扫描 `com.yx` 及子包），noon 业务类在其扫描树下，且 admin 依赖 noon，运行时可被扫描注册。

**3. 打包产物 — 通过**：`yxerp-admin.jar` 含 `BOOT-INF/lib/yxerp-noon-3.9.2.jar`，jar 内业务类为 `com/yx/noon/*`；无 `BOOT-INF/classes/com/yx/framework/{fifo,reversal,intake}` 旧类（业务类在嵌套 noon jar 内，符合多模块打包，不是缺失）。`yxerp-noon/target/classes/com/yx/framework` 仅剩空目录（0 文件，jar 内仅目录项无 class），不影响运行，可后续 `mvn clean` 清除（你已说明离线 Maven 缺 clean 插件未跑 clean，我不谎称 clean 成功）。

**4. 测试脚本路径 — 通过**：三个脚本 surefire 报告路径均已同步 `yx-erp/yxerp-noon/target/surefire-reports/TEST-com.yx.noon.{fifo,intake,reversal}.MySql*IT.xml`，`-Dtest` 类名不变，脚本 cwd=`yx-erp` 全项目跑 test（无 `-pl`），模块拆分不影响执行。

**5. 独立复跑验证（本机）— 全通过**：
- 全项目 `mvn test`（离线 settings + repo）：**BUILD SUCCESS**；`yxerp-noon` 96 tests（红冲 25/入账 45/FIFO 26）、framework 29 tests，0 failures/errors。
- 隔离 MySQL 8.4 三项：FIFO **9/9**、入账 **13/13**、红冲 **21/21**，maven_exit=0，与你报告一致。
- 未完整启动应用做登录/接口验证（同你边界）；未连生产、未部署。

**6. 旧 `SALE_DELIVERY` 流水兼容（你提示）— 认同并纳入迁移前置**：本轮解耦只改新写入，未处理历史。迁移前须先盘点是否存在旧版 `move_type='SALE_DELIVERY'` 的 `inv_batch_consumption` 行（`SELECT count(*) ... WHERE move_type='SALE_DELIVERY'`）；若有，需设计受控兼容/转换方案（旧行按 sign 方向归类、保留原值与审计，不臆造平台字段）并在隔离库验证红冲对旧行的处理，不假设已有或没有旧数据。此项加入未决，不在本包臆断执行。

**结论**：模块拆分与包名整理可采纳，无需我改动代码。**边界不变**：仍无 HTTP/UI、真实事件适配层、完整业务闭环；阶段二入库批次、阶段三退货处置闭环、阶段四跨期快照、阶段五端到端验收仍未实现。请用户决定是否启动下一工作包及是否推送；我未推送。
