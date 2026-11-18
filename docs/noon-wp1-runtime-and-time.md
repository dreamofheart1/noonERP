# WP1 续做：隔离运行验证与平台时间基础

日期：2026-11-18；执行者：Codex；WorkBuddy 独立审核待完成。

## 本次验证范围

使用官方 MySQL 8.4.7 macOS ARM64 包、从官方源码编译的 Redis 7.4.2、已有 JDK 21.0.13、Maven 3.9.11。所有实例由脚本自建，随机本机端口、全新临时数据目录和随机凭据，不连接仓库默认外部数据源、不安装系统服务、不操作生产、不部署。应用以 Java 17 目标编译，实际运行在 JDK 21；不据此声明 JDK 17 运行环境已验收。

- 默认 `mvn package`：7 模块成功；20 项测试（原 H2 权限/事务 14 + 时间边界 6），无失败/错误/跳过。
- 原生 MySQL opt-in：`MySqlSiteAuthorizationIT` 继承全部 14 项权限测试，并增加默认 RR 隔离级别检查、确定性锁等待回归，共 16 项，无失败/错误/跳过。每个测试新建独立随机数据库、执行未经 H2 变换的原始迁移并在结束后删除自己的测试数据库。
- 锁等待回归实际观察 `performance_schema.data_lock_waits`，然后提交阻塞方 AE→SA 的变更。被阻塞的服务最终必须把 SA→AE 写入并审计；若替换前误用旧 RR 快照 AE，会错误地视为无变更，测试因此失败。测试不是靠固定睡眠碰撞线程。
- 另建完整应用数据库，执行原始若依初始化及站点增量迁移，确认用户仍保留；重复站点迁移明确失败，不静默覆盖。
- 启动真正的应用 JAR（Tomcat / Spring Security / JWT filter / 实际 TokenService / FastJson Redis serializer / 实际 MyBatis / MySQL），通过 HTTP 获取验证码，并从本次 Redis 读取其期望值后提交密码登录；没有注入 LoginUser 或伪造 token 来代替登录。
- 运行断言包括：未登录拒绝、Redis 会话存在、超管双站、普通用户空站点、普通用户不能自授权、授权后原 JWT 读取 AE、非法站点不改授权、超管不可覆盖、撤权后旧 JWT 为空、审计前后值、停用后旧 JWT 拒绝、篡改签名拒绝、退出清 Redis 会话、Redis TTL 到期后 JWT 拒绝。

默认测试未启动服务器；原生 MySQL/Redis 套件必须显式运行下面脚本，不会因日常 `mvn test` 自动访问本机或远程数据库。原生套件单独匹配 `*IT`，脚本还检查 JUnit XML 的数量、失败、错误和跳过，避免仅凭 Maven 退出码把空跑当通过。

## 可复现命令

先准备平台适用的官方 MySQL 与 Redis 二进制（当前下载和编译均放在 `/tmp`，不纳入仓库），然后：

```sh
cd /Users/dream/Work/noonERP/yx-erp
mvn package
cd /Users/dream/Work/noonERP
python3 scripts/verify-site-auth-runtime.py \
  --mysql-bin-dir /tmp/mysql-8.4.7-macos15-arm64/bin \
  --redis-bin-dir /tmp/redis-7.4.2/src \
  --maven /tmp/apache-maven-3.9.11/bin/mvn \
  --maven-settings /tmp/noon-maven-settings.xml \
  --maven-repo /tmp/noon-m2
```

`--maven-settings`、`--maven-repo` 可省略；Maven 和二进制路径按本机调整。脚本只初始化它自己创建的临时目录，忽略 MySQL 默认配置，只连接 127.0.0.1，禁用 HTTP 代理，关闭 Quartz 自动运行并暂停初始化任务。应用仅加载显式指定的基础配置和隔离配置、移除可覆盖连接的相关环境变量；不启用仓库 druid profile。首次运行时发现普通 Spring 配置生效前原始 logback 会触碰固定 `/home/ruoyi/logs`，已在 JVM 启动阶段指定独立控制台配置，未修改默认部署日志设置或申请该目录写权限。

输出 `PASS` 和证据目录；不打印密码、JWT 或验证码。脚本正常/异常结束都只终止自己创建的子进程，保留本次临时数据和日志供复核。目录权限 0700、隔离配置 0600；不要把临时目录中的随机凭据或原始会话数据提交到仓库。构建日志、JUnit XML 和运行结果各自留存；最终执行证据路径见协作记录。

官方包来源：

- [MySQL 固定测试版本](https://cdn.mysql.com/archives/mysql-8.4/mysql-8.4.7-macos15-arm64.tar.gz)
- [Redis 固定测试版本源码](https://download.redis.io/releases/redis-7.4.2.tar.gz)

这些是本地验证用固定版本，不是对生产部署版本的选型或验收。

## 下一项基础能力：保留原始瞬间的中国时间换算

新增 `com.yx.common.time.BusinessTimestamp`。只接收严格 ISO 时间文本；带偏移的文本用 `fromOffset`，无偏移文本必须同时给平台明确的 `ZoneId` 并用 `fromLocal`。保留 `sourceValue`、`sourceZone`、`Instant`，派生 `chinaTime()`；不依赖服务器默认时区，不修改 JVM 默认时区，不自动截断精度。

例如平台截止 `2026-11-18T23:59:59.123+04:00`，对应 `Instant=2026-11-18T19:59:59.123Z`、中国时间 `2026-11-19T03:59:59.123`。不能把它改成中国当日 23:59:59。SA `Asia/Riyadh` 的本地截止也保留其来源并准确跨日换算。

6 项边界测试覆盖 AE/SA 跨日、缺少时区、非法日期、DST 缺口/重叠、显式偏移区分重叠瞬间、纳秒保留，以及不同主机默认时区下的相同结果。DST 有歧义或不存在时刻明确报错，不用 Java 默认的自动纠正替平台猜测；重叠须提供明确偏移。

这是供后续配置/账单/订单导入接入的值对象，**尚未接入业务落库或平台样本解析**。后续 WorkBuddy 设计 schema 时应分别保留原始文本、时区/偏移和实际瞬间；若业务表 DATETIME(3) 只能保存毫秒，必须另保留原始值并明确精度处理，不能悄悄改变截止时刻。配置有效期的站点优先/全局回退与历史重算另包实施。

## 未覆盖及审核重点

- 当前仍无采购/库存/订单/财务正式业务接口，因此未声称列表、详情、跨表、导入导出已全面站点隔离；本包 HTTP 验证针对实际已实现的站点授权接口。
- 未做前端授权管理、任务/AI 机器主体认证、AI 隔离审批、配置历史表、多店铺扩展、生产平台调用、部署或目标生产环境迁移。
- 撤权不能追溯取消已开始操作，业务写路径的短事务、撤权并发锁定仍需后续入口逐项设计。
- 请 WorkBuddy 独立复跑脚本/测试，复核隔离配置是否仍可能回落外部数据源、原生迁移及 RR 当前读审计、真实退出/TTL 语义和平台时刻保留。不得把本地运行证据直接当上线验收。
