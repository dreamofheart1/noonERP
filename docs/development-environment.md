# Noon ERP 开发环境规范

更新时间：2026-11-22。适用于 Codex、WorkBuddy 与豆包的后续开发及演示；用户最新指示优先。

- **MySQL**：统一使用用户指定服务器 `43.166.14.35:3308` 上的开发库 `noon_erp_dev`。2026-11-22 实测 MySQL **8.0.35**。编写 DDL、SQL 与迁移脚本时，以 MySQL **8.0.35** 为兼容基线；尤其不得仅因在 8.4 或其他数据库上通过便认为兼容，应在 8.0.35 验证关键语法、约束、迁移和查询。不得将凭据写入仓库、日志或协作记录。
- **Redis**：本机 `localhost:6379`、数据库 0、无密码；2026-11-22 实测服务版本 **7.4.12**。开发与演示连接本机服务；不得默认连接 MySQL 所在的远程主机。
- **Java**：沿用若依项目已有规范：`yx-erp/README.md` 的当前分支要求 **JDK 17+**，`yx-erp/pom.xml` 的 Maven 编译目标为 **Java 17**；代码与依赖须兼容项目基线，不另设一套 Java 版本规范。本机当前 `java` 为 Temurin **21.0.13**，运行验证须记录实际版本，不能以 Java 21 测试成功代替 Java 17 兼容验证。
- **Node.js**：当前可用工具链为 **24.22.3**；前端开发构建统一采用 Node.js 24 系列，并记录实际小版本。本项目当前未声明 engines；如需升级或固定其他版本，先验证依赖及构建再同步团队。

## 配置与初始化

本机开发配置位于 Git 忽略的 `config/secrets/application-noon-dev.yml`；包含远程 MySQL 与本机 Redis 设置。启动 Spring Boot 时使用 `--spring.config.additional-location=optional:file:/Users/dream/Work/noonERP/config/secrets/application-noon-dev.yml`，启动后核对实际连接目标，不要输出连接密码。其他协作者在同一工作区使用该配置；若在另一台机器执行，Redis 的 localhost 指该机器自身，先确认服务可用。

截至本记录，`noon_erp_dev` 已创建，但仍是**空库**；若依与业务表、菜单和必要初始化尚未执行，不能假设应用已经能连库正常运行。初始化前先核对表结构与迁移顺序，避免覆盖现有数据或把测试种子误作业务数据；验证使用 MySQL 8.0.35。原先独立本地临时演示已停止，后续开发演示按用户指示使用上述开发环境。

已授权团队开发；是否部署生产、访问真实平台或远程推送仍按用户既有授权执行。用户负责 Git 远程推送。

## 开发期间访问

用户希望开发和演示环境在开发过程中尽量保持运行，以便随时查看进度。初始化并验证开发库后，请团队提供可访问地址和必要的使用方式；日常开发不主动关闭服务，确需重启或停机时说明影响并在完成后恢复。环境是否已经可用必须以实际启动和访问验证为准，不能将计划或进程启动当作已可访问。开发库可能包含用户测试数据，迁移、重置与测试前先核对状态，避免随意清空或覆盖。

## 2026-11-22 实际初始化与访问记录（WorkBuddy）

- 连接前核对：`noon_erp_dev` 表数 0，确认空库后初始化；未连接/清空/覆盖任何其他库，未读取、展示或提交连接密码。
- 实测版本：MySQL **8.0.35**、Redis **7.4.12**（`localhost:6379`，PONG）、本机 Java **Temurin 21.0.13**（项目编译目标仍为 Java 17）、本机 Node **22.22.2**（与规范的 Node 24 不一致，已记录差异；本轮前后端构建/启动均在该版本完成，未声称已在 Node 24 验证）。
- 初始化顺序（均在 MySQL 8.0.35 执行成功）：`ry_20260320.sql` → `quartz.sql` → `noon_wp1_site_auth.sql` → `noon_m2_masterdata.sql` → `noon_m2_menu.sql` → `noon_m3_purchasing.sql` → `noon_m3_menu.sql` → `noon_m4_inventory.sql` → `noon_m4_menu.sql` → `noon_m4_transfer.sql` → `noon_m4_transfer_menu.sql` → `noon_m4_stocktake.sql` → `noon_m4_stocktake_menu.sql` → `noon_m4_shipment_cost.sql` → `noon_m4_shipment_cost_menu.sql` → `noon_m4_asn.sql` → `noon_m4_asn_menu.sql` → `noon_m5_sales.sql` → `noon_m5_sales_menu.sql` → 三份 review 审计表 → `docs/noon-erp-schema-v0.5.sql` 中 10 张业务基础表（`inv_batch_consumption`、`sal_order`、`sal_order_item`、`sal_return`、`int_webhook_event`、`inv_stocktake`、`inv_stocktake_item`、`log_asn`、`log_asn_item`、`log_shipment_cost`）→ `noon_menu_route_name_fix.sql`。
- 初始化结果：79 张表；`sys_menu` 147 行且无重复 `route_name`；验证码已关闭；业务单据表为空。仅写入明确标记的 **DEMO** 种子：一个 DEMO Partner/Store、AE/SA 两个站点、两个 DEMO 仓库。这些是隔离开发种子，不是真实业务数据。
- 启动命令（不含任何密码）：后端 `LOG_PATH=/Users/dream/Work/noonERP/.workbuddy/runtime/logs java -jar yx-erp/yxerp-admin/target/yxerp-admin.jar --spring.config.additional-location=optional:file:/Users/dream/Work/noonERP/config/secrets/application-noon-dev.yml`；因若依原 `logback.xml` 硬编码 `/home/ruoyi/logs`，已改为 `${LOG_PATH:-/home/ruoyi/logs}`（默认值不变）。前端 `npm run dev -- --config vite.noon-dev.config.js`，该本地配置仅把 Vite 缓存目录移出 `node_modules`，避免批量删除保护；`.env.development` 设置 `VITE_APP_BASE_API=/dev-api`（均被 Git 忽略）。
- 可访问地址：前端 **http://127.0.0.1:8081/**，后端代理前缀 **`/dev-api`**（直连后端 **http://127.0.0.1:8080/**）。已实测登录成功、`getInfo` 返回 admin、`/noon/masterdata/site/list` 返回 AE/SA 两条、采购单菜单可打开且无 404。
- 本环境为开发/演示用途，服务在本轮工作期间保持运行；如需重启，按上述命令恢复，并先确认数据库现状，不重跑初始化脚本。
