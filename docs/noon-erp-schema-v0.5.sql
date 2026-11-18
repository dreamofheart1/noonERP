-- =============================================================================
-- Noon ERP schema v0.5  ·  MySQL 8.0+ / utf8mb4  ·  2026-11-18 (GMT+8)
-- 基线：docs/noon-erp-schema-v0.4.sql（45 表 + 4 视图）完整保留并演进，历史版本不覆盖。
-- 配套：docs/noon-erp-prd-v1.0.md、docs/noon-erp-requirements-v0.2.md、
--       docs/noon-erp-schema-review-v1.0.md、docs/agent-collaboration.md（双方决议）、
--       docs/noon-erp-schema-v0.5-er.md（ER 图，与本文一致）
--
-- v0.5 目标：在不侵入若依框架表的前提下，落地二开对接所需的可验证契约：
--   1. 若依表冲突：用户/角色/权限 100% 复用若依，本文件不再建 sys_user/sys_role/
--      sys_user_role；平台参数 sys_config 归若依，Noon 业务规则独立为 noon_rule_config。
--      审计表更名 noon_audit_log，统一「sys_* 属若依，noon_*/业务前缀属 Noon」。
--      站点授权 sys_user_site / sys_user_site_audit 由 yx-erp/sql/noon_wp1_site_auth.sql
--      管理（已实现），本文件仅引用 site_code，不重复建表。
--   2. 版本化参数：noon_rule_config（append-only + 撤销生效时刻 + scope GLOBAL|AE|SA）、
--      noon_rule_config_lock（(config_key,scope) 持久锁行，解决首次写入无版本行）、
--      noon_rule_config_audit（写/撤销审计，与版本同事务）、
--      noon_config_snapshot（可信快照，业务同事务落库，重算只读快照不查当前配置）。
--   3. 修复审查项：P0-1 消耗/退货业务幂等键；P1-1 外币 fx_rate 不默认 1；
--      P1-2 利润视图拆 SITE/SKU；P1-4 ON DUPLICATE KEY 行别名语法；
--      P2-1 关键 CHECK；P2-2 退货幂等键；P2-5 is_usable 生成列。
--   4. 决议固化：超管全站点（若依 userId=1）、普通用户空授权默认拒绝、
--      AI 写入隔离暂存+审批转正+自动审核开关、统一 UTC+8 且保留平台原始截止瞬间。
--
-- 设计约定（沿用 v0.4 并补充）：
--   1. 业务表不使用物理外键（逻辑关联）；站点授权表沿用若依侧外键，不在此重复定义。
--   2. 金额 DECIMAL(18,4)，汇率 DECIMAL(18,8)，数量 INT；amount 均为原币，
--      跨站点归一在查询时按 fx_rate 显式进行（缺失报错，禁止 1 兜底）。
--   3. raw_payload / raw_json / *_raw_text 保留平台原始字段与原始时间，字段不删减。
--   4. 所有业务时刻 DATETIME(3) 统一 Asia/Shanghai (UTC+8)；平台原始时间文本与
--      时区偏移另存 *_raw_text / *_raw_tz，仅展示层换算站点本地时间。
--   5. 平台规则一律落 noon_rule_config，不写死 Java 常量；若依框架参数留 sys_config。
--   6. CHECK 仅加在语义确定、不会随业务扩展误伤的字段；枚举扩展需走 DDL 变更。
--   7. 「空站点列表」的列表/统计查询必须 WHERE 1=0，禁止退化成全表。
--   8. 追溯性更正/双时态、正式 Repository、管理 API、MySQL 并发测试尚未实现，
--      标为待定，不虚构完成。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- A. 主数据
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS md_partner_account (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_code    VARCHAR(64)  NOT NULL COMMENT 'Noon 项目码，如 PRJ468049',
  account_name    VARCHAR(128) COMMENT 'Partner 账号名',
  credential_ref  VARCHAR(512) COMMENT 'Service Account .json 服务端加密路径引用，私钥不入库',
  base_url        VARCHAR(256) NOT NULL DEFAULT 'https://noon-api-gateway.noon.partners',
  sandbox_url     VARCHAR(256) DEFAULT 'https://noon-sandbox-api-gateway.noon.partners',
  user_agent      VARCHAR(128) NOT NULL DEFAULT 'NoonErpClient/1.0' COMMENT '所有 API 请求必须携带',
  env             VARCHAR(16)  NOT NULL DEFAULT 'SANDBOX' COMMENT 'SANDBOX / PRODUCTION',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_project (project_code),
  CONSTRAINT chk_partner_env CHECK (env IN ('SANDBOX','PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon Partner 账号与 API 凭据引用';

CREATE TABLE IF NOT EXISTS md_store (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  store_no        VARCHAR(64)  NOT NULL COMMENT '店铺业务编号',
  partner_id      BIGINT       NOT NULL COMMENT 'md_partner_account.id',
  name_en         VARCHAR(256),
  name_ar         VARCHAR(256),
  legal_entity    VARCHAR(256) COMMENT '执照主体',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_store_no (store_no),
  KEY idx_partner (partner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺（Q2：1 个）';

CREATE TABLE IF NOT EXISTS md_site (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code       VARCHAR(16)  NOT NULL COMMENT 'AE / SA；与若依 sys_user_site.site_code 同义',
  store_id        BIGINT       NOT NULL,
  country         VARCHAR(64),
  currency        CHAR(3)      NOT NULL COMMENT 'AED / SAR',
  vat_rate        DECIMAL(6,4) DEFAULT 0 COMMENT 'AE 0.05 / SA 0.15；v0.2 仅记录不参与计算',
  timezone        VARCHAR(64)  DEFAULT 'Asia/Dubai' COMMENT '仅展示层换算用；业务时刻一律 UTC+8',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_store_site (store_id, site_code),
  CONSTRAINT chk_site_code CHECK (site_code IN ('AE','SA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点（Q2：AE + SA 两条）';

CREATE TABLE IF NOT EXISTS md_warehouse (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  warehouse_code    VARCHAR(64) NOT NULL COMMENT 'Noon warehouse_code 或本地编码',
  site_code         VARCHAR(16) NOT NULL,
  warehouse_name    VARCHAR(256),
  warehouse_type    VARCHAR(24) NOT NULL COMMENT 'LOCAL / TRANSIT / FBN / 3PL',
  fulfillment_mode  VARCHAR(24) NOT NULL DEFAULT 'FBN' COMMENT 'FBN / GLOBAL / FBP，创建后不可变更',
  is_fbn            TINYINT(1)  NOT NULL DEFAULT 0,
  status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wh_code (warehouse_code, site_code),
  KEY idx_site (site_code),
  CONSTRAINT chk_wh_fbn CHECK (is_fbn IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库，绑定站点+履约模式';

CREATE TABLE IF NOT EXISTS md_sku (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_code        VARCHAR(64)  NOT NULL COMMENT '本地 SKU 编码',
  spu_id          VARCHAR(64)  COMMENT '本地 SPU',
  name_en         VARCHAR(512),
  name_ar         VARCHAR(512) COMMENT '阿语字段保留，后期刊登使用',
  category_code   VARCHAR(64)  COMMENT 'Noon 类目码，关联费率字典',
  hs_code         VARCHAR(32),
  origin_country  VARCHAR(32),
  brand           VARCHAR(128),
  spec_json       JSON         COMMENT '规格/尺寸/重量，FBN 计费档依据',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sku_code (sku_code),
  KEY idx_category (category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地 SKU 主数据';

CREATE TABLE IF NOT EXISTS md_partner_sku (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  partner_sku     VARCHAR(128) NOT NULL COMMENT 'Noon Partner SKU',
  sku_id          BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  business_model  VARCHAR(24)  NOT NULL DEFAULT 'noon' COMMENT 'noon / supermall / Global',
  offer_code      VARCHAR(128) COMMENT 'GetProductOffers 返回的 offer_code',
  barcode         VARCHAR(128),
  live_status     TINYINT(1)   DEFAULT NULL COMMENT '由 Offer API 回读',
  offer_issues    JSON         COMMENT 'GetProductOffers 的 offer_issues 原文',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  synced_at       DATETIME(3),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_partner_sku (partner_sku, site_code, business_model),
  KEY idx_sku (sku_id),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Q4 不做刊登，此表是唯一必需的商品表：订单回查与利润归因';

CREATE TABLE IF NOT EXISTS md_supplier (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  supplier_no     VARCHAR(64)  NOT NULL,
  supplier_name   VARCHAR(256) NOT NULL,
  contact_json    JSON,
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_supplier_no (supplier_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商';

CREATE TABLE IF NOT EXISTS md_sku_supplier (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id          BIGINT       NOT NULL,
  supplier_id     BIGINT       NOT NULL,
  unit_price      DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  moq             INT          DEFAULT 0,
  lead_time_days  INT          DEFAULT 0 COMMENT '生产/交货周期，补货模型用',
  effective_from  DATE         NOT NULL,
  effective_to    DATE,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sku_sup_eff (sku_id, supplier_id, effective_from),
  KEY idx_sup (supplier_id),
  CONSTRAINT chk_sku_sup_qty CHECK (moq >= 0 AND lead_time_days >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU-供应商报价（历史价保留）';

-- -----------------------------------------------------------------------------
-- A2. 版本化业务参数与可信快照（对接阻塞项，v0.5 新增）
-- -----------------------------------------------------------------------------

-- ★ P1-3：Noon 业务规则参数从 v0.4 的 sys_config 迁出，独立命名，避免与若依
--   sys_config（框架 KV 参数）同名冲突。若依继续用自己的 sys_config 管 sys.* 键。
-- 契约与 Codex 框架侧对齐（EffectiveConfigResolver / VersionedConfigReader / ConfigIntervalPolicy）：
--   Version = (key, scope, value, from, to)，有效区间 [from, to)，to NULL 表示无穷；
--   Row = (id, version, revokedAt)，id 即 versionId；
--   Snapshot = (versionId, key, requestedSite, resolvedScope, value, businessTime)。
-- 关键口径：
--   * append-only：新版本只 INSERT，禁止原地改仍生效版本；撤销只更新 revoked_by/revoked_at/status。
--   * 撤销生效时刻 revoked_at（UTC+8）：业务时刻 < revoked_at 时旧版本仍可用，等于即排除。
--   * 历史解析按业务时刻判有效，不得用当前 status 或 revoked_at IS NULL 无条件过滤。
--   * 站点优先：scope=真实站点 命中优先于 GLOBAL；同 scope 多于 1 个有效版本 → 失败关闭。
--   * 区间重叠：end = min(effective_to, revoked_at)，半开边界相接允许；生效前已撤销
--     的版本无有效区间（由 ConfigIntervalPolicy.requireNoOverlap 判定）。
--   * proposed 的 from<to 由 Version 构造器保证；DB 层 chk 独立保护外部输入路径。
--   * 首行创建竞争：写前必须先确保 (config_key,scope) 锁行存在并 SELECT ... FOR UPDATE。
CREATE TABLE IF NOT EXISTS noon_rule_config (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'versionId，快照与审计引用此 ID',
  config_key      VARCHAR(128) NOT NULL COMMENT '如 allocation_policy / return_window_days',
  scope           VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / AE / SA；站点专属优先于 GLOBAL',
  config_value    VARCHAR(1024) NOT NULL COMMENT '不可变值快照文本',
  version_no      INT          NOT NULL COMMENT '同 key+scope 内单调递增版本',
  effective_from  DATETIME(3)  NOT NULL COMMENT 'UTC+8，生效下界（含）',
  effective_to    DATETIME(3)  DEFAULT NULL COMMENT 'UTC+8，失效上界（不含）；NULL=无穷',
  status          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=当前有效(派生/展示用),0=已撤销；历史解析不看此列',
  revoked_by      BIGINT       DEFAULT NULL COMMENT '若依 sys_user.user_id',
  revoked_at      DATETIME(3)  DEFAULT NULL COMMENT 'UTC+8 撤销生效时刻；业务时刻 >= 此值即排除',
  created_by      BIGINT       NOT NULL COMMENT '若依 sys_user.user_id；机器主体另按授权表',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  remark          VARCHAR(512),
  UNIQUE KEY uk_cfg_eff (config_key, scope, effective_from),
  UNIQUE KEY uk_cfg_version (config_key, scope, version_no),
  KEY idx_cfg_lookup (config_key, scope, effective_from, effective_to),
  CONSTRAINT chk_cfg_scope CHECK (scope IN ('GLOBAL','AE','SA')),
  CONSTRAINT chk_cfg_interval CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT chk_cfg_status CHECK (status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 版本化业务参数（append-only + 撤销生效时刻）；对接阻塞项';

-- ★ 持久锁行：首次写入时可能没有任何版本行，锁版本行会失效。所有写路径统一：
--   ① INSERT IGNORE 确保 (config_key,scope) 锁行存在（GLOBAL/AE/SA 预置）；
--   ② SELECT ... FOR UPDATE 当前读锁住该行；
--   ③ 查完整候选 → requireNoOverlap → INSERT 新版本 → 写审计，同一事务提交。
--   唯一键是补充保护，不替代锁与重叠检查；首次竞争/锁等待/回滚释放须在隔离 MySQL 并发测试。
CREATE TABLE IF NOT EXISTS noon_rule_config_lock (
  config_key      VARCHAR(128) NOT NULL,
  scope           VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / AE / SA，每 key 预置三行',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (config_key, scope),
  CONSTRAINT chk_cfg_lock_scope CHECK (scope IN ('GLOBAL','AE','SA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 配置写入持久锁行；(config_key,scope) 粒度，先锁后查';

-- 写/撤销审计与版本行同事务；before/after 保留版本 ID 与关键字段，不改历史快照。
CREATE TABLE IF NOT EXISTS noon_rule_config_audit (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  audit_no        VARCHAR(64)  NOT NULL COMMENT '审计编号',
  config_key      VARCHAR(128) NOT NULL,
  scope           VARCHAR(16)  NOT NULL,
  action          VARCHAR(16)  NOT NULL COMMENT 'CREATE / REVOKE',
  version_id      BIGINT       NOT NULL COMMENT 'noon_rule_config.id',
  version_no      INT          NOT NULL,
  before_json     JSON         COMMENT '撤销前 revoked_*/status 等；CREATE 为 NULL',
  after_json      JSON         COMMENT '写/撤销后的关键字段',
  actor_id        BIGINT       NOT NULL COMMENT '若依 sys_user.user_id',
  actor_name      VARCHAR(64),
  act_at          DATETIME(3)  NOT NULL COMMENT 'UTC+8 操作时刻，应用显式传，非 DB 默认',
  UNIQUE KEY uk_cfg_audit_no (audit_no),
  KEY idx_cfg_audit (config_key, scope, act_at),
  CONSTRAINT chk_cfg_audit_scope CHECK (scope IN ('GLOBAL','AE','SA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化参数写/撤销审计，与版本行同事务';

-- ★ 可信快照：业务解析参数后与本业务同事务落库；重算只读本表，不查当前配置，
--   不受后续撤销/删除影响。snapshot_source 区分实时解析与快照重放。
--   不接受客户端提交的快照作为重算输入（与 VersionedConfigReader.Snapshot 一致）。
--   保留 requested_site / resolved_scope 供权限复核；重算仍需 requireSite(requested_site)。
CREATE TABLE IF NOT EXISTS noon_config_snapshot (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  snapshot_batch_no VARCHAR(64)  NOT NULL COMMENT '一次业务解析为一批；业务表用此号引用一组参数',
  biz_type          VARCHAR(32)  NOT NULL COMMENT 'PUR_ORDER / SAL_ORDER / FIN_STATEMENT / FIN_PROFIT / RP_SUGGEST',
  biz_no            VARCHAR(64)  COMMENT '业务单号；先解析后落单时可空',
  config_key        VARCHAR(128) NOT NULL,
  version_id        BIGINT       NOT NULL COMMENT 'noon_rule_config.id',
  requested_site    VARCHAR(16)  NOT NULL COMMENT '业务真实站点（已经 requireSite）',
  resolved_scope    VARCHAR(16)  NOT NULL COMMENT '实际命中 GLOBAL 或真实站点',
  value_snapshot    VARCHAR(1024) NOT NULL COMMENT '不可变值快照',
  business_time     DATETIME(3)  NOT NULL COMMENT 'UTC+8 业务时刻，解析基准',
  resolved_at       DATETIME(3)  NOT NULL COMMENT 'UTC+8 实际解析时刻',
  snapshot_source   VARCHAR(32)  NOT NULL DEFAULT 'LIVE_RESOLVE' COMMENT 'LIVE_RESOLVE / STORED_REPLAY',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_snapshot_batch (snapshot_batch_no, config_key),
  KEY idx_snapshot_biz (biz_type, biz_no),
  CONSTRAINT chk_snap_scope CHECK (resolved_scope IN ('GLOBAL','AE','SA')),
  -- ★ Codex REPRO 反例修正：站点优先/GLOBAL 回退下，解析结果只能是请求站点本身或
  --   GLOBAL，禁止 AE 业务解析出 SA 参数（跨站污染）。STORED_REPLAY 同理不得跨站。
  CONSTRAINT chk_snap_owner CHECK (resolved_scope = 'GLOBAL' OR resolved_scope = requested_site),
  CONSTRAINT chk_snap_source CHECK (snapshot_source IN ('LIVE_RESOLVE','STORED_REPLAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 可信配置快照：业务同事务落库，重算只读本表';

-- ★ Codex REPRO 反例修正：快照声称不可变但无强制，UPDATE 可原地篡改 value_snapshot，
--   破坏「重算只读本表」的可信基础。MySQL 8 无 immutable 约束，用 BEFORE UPDATE
--   SIGNAL 拒绝一切原地更新；更正只能插新快照（新 batch_no/version_id）并让业务改引用。
--   DROP IF EXISTS + CREATE 保证幂等重跑不报错。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_snapshot_immutable$$
CREATE TRIGGER trg_snapshot_immutable BEFORE UPDATE ON noon_config_snapshot
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'noon_config_snapshot 不可原地更新；更正须插新快照并改业务引用';
END$$
DROP TRIGGER IF EXISTS trg_snapshot_no_delete$$
CREATE TRIGGER trg_snapshot_no_delete BEFORE DELETE ON noon_config_snapshot
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'noon_config_snapshot 禁止物理删除；快照须保留供历史重算，归档走受控迁移';
END$$
DELIMITER ;
-- 删除边界说明（Codex 二轮第4点）：UPDATE + DELETE 触发器在 DDL 层封死普通应用的
-- 原地篡改与删除；重算始终能读到快照。归档/迁移例外必须走受控流程：由 DBA 在维护窗口
-- 先停用触发器、经审批导出归档、再恢复；不得作为应用运行期能力开放。
-- 权限模型：应用账号只授 SELECT/INSERT，UPDATE/DELETE 仅 DBA  migrations；
-- 触发器是第二道防线，不替代数据库账号权限。普通用户/AI 主体无删改授权。

-- -----------------------------------------------------------------------------
-- B. 采购 · FIFO 批次 · 库存
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS pur_order (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no        VARCHAR(64)  NOT NULL COMMENT '采购单业务编号',
  supplier_id     BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  total_amount    DECIMAL(18,4) DEFAULT 0,
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/CLOSED（Q11 无审批流）',
  source          VARCHAR(24)  DEFAULT 'MANUAL' COMMENT 'MANUAL / REPLENISH_SUGGESTION / AI_SUGGESTION',
  source_ref_no   VARCHAR(64)  COMMENT '来源建议单号',
  operator_id     BIGINT       COMMENT '若依 sys_user.user_id',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：本单参数可信快照批号，引用 noon_config_snapshot',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pur_no (order_no),
  KEY idx_supplier (supplier_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单';

CREATE TABLE IF NOT EXISTS pur_order_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  pur_order_id    BIGINT       NOT NULL,
  sku_id          BIGINT       NOT NULL,
  qty_ordered     INT          NOT NULL,
  qty_received    INT          NOT NULL DEFAULT 0,
  unit_price      DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY' COMMENT 'CNY 本币采购；外币采购须显式赋值',
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL 不再兜底 1；本币 CNY 由应用显式写 1，缺失报错',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_pur (pur_order_id),
  KEY idx_sku (sku_id),
  CONSTRAINT chk_pur_item_qty CHECK (qty_ordered >= 0 AND qty_received >= 0 AND qty_received <= qty_ordered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单行';

CREATE TABLE IF NOT EXISTS inv_batch (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_no        VARCHAR(64)  NOT NULL COMMENT 'FIFO 成本批次号',
  sku_id          BIGINT       NOT NULL,
  pur_order_item_id BIGINT     COMMENT '采购来源；退货二次上架时为空',
  warehouse_id    BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  received_at     DATETIME(3)  NOT NULL COMMENT 'UTC+8；FIFO 排序第一键',
  qty_in          INT          NOT NULL,
  qty_remaining   INT          NOT NULL COMMENT '剩余数量，扣到 0 后关闭批次',
  unit_cost       DECIMAL(18,4) NOT NULL COMMENT '采购单价（不含头程/关税，见 Q8）',
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL；CNY 本币应用显式写 1，缺失报错',
  source_type     VARCHAR(24)  NOT NULL DEFAULT 'PURCHASE' COMMENT 'PURCHASE / RETURN_RESTOCK / ADJUSTMENT / TRANSFER_SPLIT',
  parent_batch_id BIGINT       COMMENT 'C-5：拆批来源批次 inv_batch.id；原始采购批次为 NULL',
  split_seq       INT          NOT NULL DEFAULT 0 COMMENT 'C-5：父批次下第几段拆分；0=原始批次',
  split_reason    VARCHAR(24)  COMMENT 'C-5：TRANSFER / ASN_SHORT / PARTIAL_RECEIPT / RETURN_RESTOCK',
  transfer_no     VARCHAR(64)  COMMENT 'C-5：调拨单号（拆批来源之一）',
  asn_no          VARCHAR(64)  COMMENT 'C-5：ASN 号（拆批来源之一）',
  status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / IN_TRANSIT / CLOSED',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_batch_no (batch_no),
  KEY idx_fifo (sku_id, received_at, id),
  KEY idx_warehouse (warehouse_id),
  KEY idx_parent (parent_batch_id),
  CONSTRAINT chk_batch_qty CHECK (qty_in > 0 AND qty_remaining >= 0 AND qty_remaining <= qty_in),
  CONSTRAINT chk_batch_split CHECK (split_seq >= 0),
  CONSTRAINT chk_batch_status CHECK (status IN ('OPEN','IN_TRANSIT','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ FIFO 成本批次，Q5 核心表；C-5 支持拆批血缘与在途段';

-- ★ P0-1 / 幂等键契约（经两轮 Codex 反例修正后的最终口径）：
--   MySQL 唯一键对 NULL 不相等，故用显式非空 biz_event_key 作业务幂等键。
--   ★ 核心原则：幂等键必须绑定「每次实际过账事件」，不是订单行/原消耗对象 ID。
--     同一订单行可以先后多次部分妥投；同一原 FIFO 消耗可以先后多次部分退货冲回；
--     若按对象 ID 生成 key，第二次动作会被唯一键误拒。事件键由应用按确定规则生成：
--       SALE            = 'SALE:' + delivery_event_id（每次妥投/妥投批次一个事件号）
--       RETURN_REVERSAL = 'REV:'  + return_event_id（每次部分退货一个事件号，非原消耗ID）
--       RETURN_LOSS     = 'LOSS:' + sal_return_id + ':' + grade
--       STOCKTAKE       = 'STK:'  + stocktake_item_id + ':' + count_event_id
--       TRANSFER_SHORT  = 'TSHORT:' + transfer_item_id + ':' + short_event_id
--   唯一键 (biz_event_key, batch_id) 保证三条同时成立：
--     (a) 同一次过账事件跨多批次 → 同 key 多行（FIFO 正常形态，不误拒）；
--     (b) 同一次事件重传/重导入 → 同 key + 同 batch 被拦截，COGS 不双计；
--     (c) 不同部分妥投/退货事件 → 不同 key，可再次消费同一成本批次。
--   ★ 事件 ID 来源：优先取平台每次履约/退货动作的幂等号（delivery_no / return line id /
--     webhook event_id）；平台不提供时，应用按 (单号, 行号, 事件序号) 生成确定性 ID，
--     并在 int_webhook_event / import_fingerprint 层去重；仍无法可靠识别时进入
--     NEEDS_REVIEW 人工确认，禁止猜测事件边界自动过账。
--   ★ 累计数量守恒（退货累计 ≤ 原消耗 qty）无法用单行 CHECK 表达，须应用层事务
--     用 SELECT ... FOR UPDATE 锁原消耗行、SUM 已冲回数量后校验；DDL 只负责单次幂等，
--     不承担累计守恒，也不宣称完整审批/过账闭环已实现。
--   source_event_id 为平台事件/CSV 行指纹，可空；「事件级」标识会随跨批次多行重复，
--     故仅建索引供追溯；事件级去重在 webhook/sal_order 指纹层承担。
CREATE TABLE IF NOT EXISTS inv_batch_consumption (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumption_no  VARCHAR(64)  NOT NULL,
  biz_event_key   VARCHAR(160) NOT NULL COMMENT '★ P0-1：业务幂等键（非空、唯一），规则见上方注释',
  source_event_id VARCHAR(128) DEFAULT NULL COMMENT '平台事件ID/CSV行指纹；可空，多 NULL 不冲突',
  batch_id        BIGINT       NOT NULL COMMENT 'inv_batch.id',
  sal_order_item_id BIGINT     COMMENT '销售订单行；非销售消耗可空',
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  unit_cost       DECIMAL(18,4) NOT NULL,
  amount          DECIMAL(18,4) NOT NULL COMMENT '= qty * unit_cost 正值；汇总时乘 sign 即 COGS',
  consumed_at     DATETIME(3)  NOT NULL COMMENT 'UTC+8',
  move_type       VARCHAR(24)  NOT NULL DEFAULT 'SALE' COMMENT 'SALE / RETURN_LOSS / STOCKTAKE / TRANSFER_SHORT / RETURN_REVERSAL',
  sign            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '+1 正常扣减 / -1 红冲；金额存正值，运算乘 sign',
  reversal_of_id  BIGINT       COMMENT '被红冲的原 inv_batch_consumption.id；A/B/C 级退货必填',
  sal_return_id   BIGINT       COMMENT '退货单 id',
  refund_amount   DECIMAL(18,4) DEFAULT 0 COMMENT '退款收入冲减额（进入 revenue 减项）',
  period          CHAR(7)      COMMENT 'YYYY-MM UTC+8 归属期间，默认取原消耗层期间',
  cross_period    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '退货与原消耗跨期=1，需重算原期快照',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_consumption_no (consumption_no),
  UNIQUE KEY uk_consume_biz_event (biz_event_key, batch_id),
  KEY idx_consume_source_event (source_event_id),
  KEY idx_batch (batch_id),
  KEY idx_order_item (sal_order_item_id),
  KEY idx_sku (sku_id),
  KEY idx_reversal (reversal_of_id),
  CONSTRAINT chk_consume_qty CHECK (qty > 0 AND amount >= 0),
  CONSTRAINT chk_consume_sign CHECK (sign IN (-1,1)),
  CONSTRAINT chk_consume_cross CHECK (cross_period IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ FIFO 批次消耗流水，COGS 唯一来源；含正负双向冲回与幂等键';

CREATE TABLE IF NOT EXISTS inv_stock_ledger (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id          BIGINT       NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  qty_available   INT          NOT NULL DEFAULT 0,
  qty_in_transit  INT          NOT NULL DEFAULT 0,
  qty_reserved    INT          NOT NULL DEFAULT 0 COMMENT 'Noon reserved stock，平台自动扣，不通过 Stock API 设置',
  qty_unsellable  INT          NOT NULL DEFAULT 0 COMMENT '退货待检/损坏',
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sku_wh (sku_id, warehouse_id),
  KEY idx_site (site_code),
  CONSTRAINT chk_ledger_qty CHECK (qty_available >= 0 AND qty_in_transit >= 0 AND qty_reserved >= 0 AND qty_unsellable >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存四态台账';

CREATE TABLE IF NOT EXISTS inv_stock_move (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  move_no         VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  batch_id        BIGINT,
  change_type     VARCHAR(32)  NOT NULL COMMENT 'INBOUND/OUTBOUND/TRANSFER_IN/TRANSFER_OUT/STOCKTAKE_GAIN/STOCKTAKE_LOSS/RETURN_RESTOCK',
  qty_delta       INT          NOT NULL COMMENT '正负表示增减，台账变动非零',
  ref_doc_type    VARCHAR(32),
  ref_doc_no      VARCHAR(64),
  operator_id     BIGINT,
  raw_payload     JSON         COMMENT '平台原始字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_move_no (move_no),
  KEY idx_sku_wh (sku_id, warehouse_id),
  KEY idx_ref (ref_doc_type, ref_doc_no),
  CONSTRAINT chk_move_delta CHECK (qty_delta <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存变动流水，带编号与时间戳';

CREATE TABLE IF NOT EXISTS inv_inbound (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  inbound_no      VARCHAR(64)  NOT NULL,
  pur_order_id    BIGINT,
  warehouse_id    BIGINT       NOT NULL,
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/CLOSED',
  operator_id     BIGINT,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inbound_no (inbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单';

CREATE TABLE IF NOT EXISTS inv_inbound_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  inbound_id      BIGINT       NOT NULL,
  pur_order_item_id BIGINT,
  sku_id          BIGINT       NOT NULL,
  batch_no        VARCHAR(64)  NOT NULL COMMENT '对应 inv_batch.batch_no',
  qty             INT          NOT NULL,
  qty_qualified   INT          NOT NULL COMMENT '质检合格量',
  unit_cost       DECIMAL(18,4) NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_inbound (inbound_id),
  CONSTRAINT chk_inbound_item_qty CHECK (qty > 0 AND qty_qualified >= 0 AND qty_qualified <= qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单行，到货即生成 FIFO 批次';

CREATE TABLE IF NOT EXISTS inv_transfer (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_no     VARCHAR(64)  NOT NULL,
  from_warehouse_id BIGINT     NOT NULL,
  to_warehouse_id BIGINT       NOT NULL,
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_TRANSIT/RECEIVED/CLOSED',
  shipped_at      DATETIME(3)  COMMENT 'UTC+8',
  received_at     DATETIME(3)  COMMENT 'UTC+8',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_transfer_no (transfer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单（国内仓→中转仓→FBN 多段）';

CREATE TABLE IF NOT EXISTS inv_transfer_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_id     BIGINT       NOT NULL,
  sku_id          BIGINT       NOT NULL,
  batch_no        VARCHAR(64)  NOT NULL,
  qty_shipped     INT          NOT NULL,
  qty_received    INT          DEFAULT 0,
  diff_qty        INT          DEFAULT 0 COMMENT '收货差异，需回写库存与批次',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_transfer (transfer_id),
  CONSTRAINT chk_transfer_item_qty CHECK (qty_shipped > 0 AND qty_received >= 0 AND qty_received <= qty_shipped)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单行，在途与收货差异';

CREATE TABLE IF NOT EXISTS inv_stocktake (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  stocktake_no    VARCHAR(64)  NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  period          CHAR(7)      COMMENT 'YYYY-MM UTC+8',
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_stocktake_no (stocktake_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单';

CREATE TABLE IF NOT EXISTS inv_stocktake_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  stocktake_id    BIGINT       NOT NULL,
  sku_id          BIGINT       NOT NULL,
  batch_no        VARCHAR(64),
  qty_book        INT          NOT NULL,
  qty_actual      INT          NOT NULL,
  qty_diff        INT          NOT NULL COMMENT '= qty_actual - qty_book，应用显式写入',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_stocktake (stocktake_id),
  CONSTRAINT chk_stocktake_qty CHECK (qty_book >= 0 AND qty_actual >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单行';

-- -----------------------------------------------------------------------------
-- C. 头程与 FBN 入仓
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS log_asn (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  asn_no          VARCHAR(64)  NOT NULL COMMENT 'ASN 编号（Seller Lab 登记或未来 API 回写）',
  site_code       VARCHAR(16)  NOT NULL,
  warehouse_code  VARCHAR(64)  NOT NULL COMMENT 'FBN 仓编码',
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SEALED/SLOT_BOOKED/IN_TRANSIT/RECEIVED',
  sealed_at       DATETIME(3)  COMMENT 'UTC+8；seal 之后才能预约仓库 slot',
  slot_date       DATE,
  carrier         VARCHAR(128),
  tracking_no     VARCHAR(128),
  source          VARCHAR(24)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / API（FBN Inbound API 待索取，见 R1）',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_asn_no (asn_no),
  KEY idx_site (site_code),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBN 入仓 ASN 登记';

CREATE TABLE IF NOT EXISTS log_asn_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  asn_id          BIGINT       NOT NULL,
  sku_id          BIGINT       NOT NULL,
  partner_sku     VARCHAR(128),
  batch_no        VARCHAR(64)  NOT NULL,
  qty_shipped     INT          NOT NULL,
  qty_received    INT          DEFAULT 0,
  diff_qty        INT          DEFAULT 0 COMMENT '收货差异，需回写库存与批次',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_asn (asn_id),
  CONSTRAINT chk_asn_item_qty CHECK (qty_shipped > 0 AND qty_received >= 0 AND qty_received <= qty_shipped)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ASN 明细与收货差异';

CREATE TABLE IF NOT EXISTS log_shipment_cost (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  cost_no         VARCHAR(64)  NOT NULL,
  asn_id          BIGINT,
  site_code       VARCHAR(16)  NOT NULL,
  period          CHAR(7)      COMMENT 'YYYY-MM UTC+8，L2 归集期间',
  fee_type        VARCHAR(32)  NOT NULL COMMENT 'FREIGHT / DUTY / CUSTOMS / PACKAGING / LABEL',
  amount          DECIMAL(18,4) NOT NULL COMMENT '原币金额',
  currency        CHAR(3)      NOT NULL COMMENT 'P1-1：显式币种，不再隐含 CNY',
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL；缺失报错，不兜底 1',
  allocation_flag VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE=不摊进批次成本（Q8 默认）/ PARTIAL / FULL',
  remark          VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cost_no (cost_no),
  KEY idx_asn (asn_id),
  KEY idx_period (period),
  CONSTRAINT chk_ship_cost_alloc CHECK (allocation_flag IN ('NONE','PARTIAL','FULL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程/关税/包材费用：只登记，不摊进 FIFO 批次';

-- =============================================================================
-- 下半部分（D/E/F 组、视图、初始化）接续于本文件
-- =============================================================================

-- -----------------------------------------------------------------------------
-- D. 订单 · 退货 · 结算 · 利润
-- -----------------------------------------------------------------------------

-- D-02：主表 partner_sku / qty / gross_amount 为导入冗余字段；成本核算粒度以
--       sal_order_item 为准，导入事务须校验主表数量与行级合计一致（FR-13.2）。
CREATE TABLE IF NOT EXISTS sal_order (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no        VARCHAR(64)  NOT NULL COMMENT 'Noon 订单号',
  site_code       VARCHAR(16)  NOT NULL,
  store_id        BIGINT       NOT NULL,
  partner_sku     VARCHAR(128) NOT NULL,
  sku_id          BIGINT       COMMENT '由 md_partner_sku 回查',
  qty             INT          NOT NULL,
  gross_amount    DECIMAL(18,4) NOT NULL COMMENT '含税售价原币（注意 v0.2 不计 VAT，见 R7）',
  vat_amount      DECIMAL(18,4) DEFAULT NULL COMMENT 'Q6 不做 VAT，字段预留可空',
  currency        CHAR(3)      NOT NULL COMMENT 'AED / SAR 原币',
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL；外币归一缺失报错，不兜底 1',
  payment_type    VARCHAR(16)  DEFAULT 'PAID' COMMENT 'COD / PAID，中东 COD 占比高',
  status          VARCHAR(32)  NOT NULL DEFAULT 'IMPORTED' COMMENT 'IMPORTED/DELIVERED/RETURNED/CANCELLED',
  ordered_at      DATETIME(3)  COMMENT 'UTC+8 业务时刻',
  ordered_at_raw_text VARCHAR(64) COMMENT '平台原始下单时间文本，字段不删减',
  delivered_at    DATETIME(3)  COMMENT 'UTC+8；周账单按妥投归期',
  delivered_at_raw_text VARCHAR(64) COMMENT '平台原始妥投时间文本',
  source_channel  VARCHAR(24)  NOT NULL DEFAULT 'EXPORT' COMMENT 'API / EXPORT / WEBHOOK / MANUAL',
  import_fingerprint VARCHAR(64) COMMENT 'C-4：raw_payload 规范化指纹(SHA256)；同指纹 ACK，不重复过账',
  state_version   INT          NOT NULL DEFAULT 1 COMMENT 'C-4：每次状态推进 +1；FIFO 只过账一次由 consumption 幂等键保证',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：本单参数可信快照批号，引用 noon_config_snapshot',
  raw_payload     JSON         COMMENT '平台原始订单报文，字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no, site_code),
  KEY idx_fingerprint (source_channel, import_fingerprint),
  KEY idx_sku (sku_id),
  KEY idx_delivered (delivered_at),
  KEY idx_site (site_code),
  CONSTRAINT chk_sal_qty CHECK (qty > 0),
  CONSTRAINT chk_sal_state_version CHECK (state_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表（FBN 订单无公开 API，按导入设计）；保留平台原始时间';

-- C-4：行级粒度。主表 order_no 唯一用于幂等 ACK；行级以 platform_item_no 为
--   平台唯一行 ID，部分妥投/部分退货按行数量表达，避免整单状态切换误伤未变行。
--   COGS 过账唯一性由 inv_batch_consumption.uk_consume_biz_event 保证。
CREATE TABLE IF NOT EXISTS sal_order_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sal_order_id    BIGINT       NOT NULL,
  order_no        VARCHAR(64)  NOT NULL COMMENT '平台订单号（冗余展示）；跨站可同号，站点身份以 sal_order_id 为准',
  platform_item_no VARCHAR(64) COMMENT 'C-4：平台订单行 ID，部分妥投/退货粒度锚点',
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL COMMENT '订单行数量',
  qty_delivered   INT          NOT NULL DEFAULT 0 COMMENT 'C-4：已妥投数量，部分妥投可 < qty',
  qty_returned    INT          NOT NULL DEFAULT 0 COMMENT 'C-4：已退货数量',
  qty_cancelled   INT          NOT NULL DEFAULT 0 COMMENT 'C-4：已取消数量',
  unit_price      DECIMAL(18,4) NOT NULL,
  cogs_amount     DECIMAL(18,4) DEFAULT 0 COMMENT '由 inv_batch_consumption 汇总回填（含红冲净额）',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_order (sal_order_id),
  KEY idx_sku (sku_id),
  -- ★ Codex 三轮复核修正（P1 跨站同号）：唯一键锚点从平台 order_no 改为内部 sal_order_id。
  --   sal_order 以 (order_no,site_code) 唯一，AE/SA 可各有同号订单；平台行号作用域未经
  --   样本证实，若按 order_no 去重会误拒跨站同号。sal_order_id 是内部可信订单身份（自增
  --   主键），天然区分站点与该订单实例：同一订单内重复行号拦截、跨站同号订单行号可各自
  --   存在。关联一致性（sal_order_id 必须指向本单真实存在的父订单、不得跨站伪造关联）
  --   由应用层在事务内按 requireSite + 父订单归属校验，DDL 不虚构平台号全局唯一。
  --   可空：手工单无平台行号时多 NULL 不冲突；待平台样本证实行号作用域后再评估收紧。
  UNIQUE KEY uk_platform_item (sal_order_id, platform_item_no),
  -- ★ Codex REPRO 反例修正：退货是已妥投量的子集（妥投10退货2合法，净在手8），
  --   不是与妥投并列的独立占用，不能与 qty_delivered 相加后再与 qty 比较。
  --   约束语义：妥投与取消共同占用订单量（支持部分取消+部分妥投，如妥投8取消2）；
  --   退货从已妥投量中扣除，不得超过妥投量。
  CONSTRAINT chk_item_qty CHECK (qty > 0 AND qty_delivered >= 0 AND qty_returned >= 0 AND qty_cancelled >= 0
    AND qty_delivered + qty_cancelled <= qty
    AND qty_returned <= qty_delivered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单行，FIFO COGS 归集粒度；C-4 支持部分妥投/退货';

-- P2-2：退货幂等键。平台退货单号（若有）作为 source_return_no；无平台号时用
--   biz_return_key = 'RET:' + order_no + ':' + sku_id + ':' + platform_return_no 之类
--   由应用生成的非空业务键；UNIQUE 阻止重复退货单重复红冲与重复计损。
-- 时间口径：window_deadline / appeal_deadline 为 UTC+8 计算值，另存 *_raw_text
--   平台原始文本与 *_raw_tz 原始时区，保留平台原始截止瞬间（Codex BusinessTimestamp 契约）。
CREATE TABLE IF NOT EXISTS sal_return (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  return_no       VARCHAR(64)  NOT NULL,
  biz_return_key  VARCHAR(160) NOT NULL COMMENT '★ P2-2：非空业务幂等键，应用按确定规则生成',
  source_return_no VARCHAR(128) DEFAULT NULL COMMENT '平台退货单号；可空，多 NULL 不冲突',
  order_no        VARCHAR(64)  NOT NULL COMMENT '平台订单号（冗余展示）；跨站可同号',
  site_code       VARCHAR(16)  DEFAULT NULL COMMENT '★三轮修正：站点身份，与父订单一致；可空兼容历史导入，跨站同号隔离锚点',
  -- ★四轮修正：NULL 站点在 MySQL 唯一键中不相等（NULL<>NULL），导致"未确定站点"的同订单
  --   同来源号退货可重复插入。生成列把 NULL 归一为保留值 '_UNSET_'，使唯一键对未确定站点
  --   同样生效；AE/SA 仍各自区分。'_UNSET_' 非合法站点码，不代表真实站点，落正式表前
  --   应由应用按父订单回填 site_code 并人工核对（见 delivery 写服务合同）。
  site_key        VARCHAR(16)  GENERATED ALWAYS AS (COALESCE(site_code,'_UNSET_')) STORED
                  COMMENT '★四轮:NULL站点归一,进 uk_return_source',
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  grade           VARCHAR(8)   COMMENT 'A / B / C，关联 Seller Protection Fund 补偿',
  compensation_amount DECIMAL(18,4) DEFAULT 0 COMMENT 'C-2b：赔偿只在 COMPENSATION(platform_fee 减项)确认一次',
  restock_batch_no VARCHAR(64) COMMENT '二次上架生成的新 FIFO 批次，插队尾',
  sal_order_item_id BIGINT     COMMENT 'C-2：关联订单行，支持部分退货',
  refund_amount   DECIMAL(18,4) DEFAULT 0 COMMENT 'C-2：退款收入冲减额',
  return_loss_amount DECIMAL(18,4) DEFAULT 0 COMMENT 'C-2b：B/C 级 = 原采购层成本全额，禁止扣赔偿',
  period          CHAR(7)      COMMENT 'C-2：YYYY-MM UTC+8 退货归属期间',
  original_period CHAR(7)      COMMENT 'C-2：原销售期间，用于跨期判定',
  window_deadline DATETIME(3)  COMMENT 'UTC+8 退货窗口截止（14/15 天冲突，由 noon_rule_config 配）',
  window_deadline_raw_text VARCHAR(64) COMMENT '平台原始退货窗口截止文本',
  window_deadline_raw_tz VARCHAR(64) COMMENT '平台原始时区偏移/ID，保留原始瞬间',
  appeal_deadline DATETIME(3)  COMMENT 'UTC+8 申诉截止（7/14 天）',
  appeal_deadline_raw_text VARCHAR(64) COMMENT '平台原始申诉截止文本',
  appeal_deadline_raw_tz VARCHAR(64) COMMENT '平台原始时区偏移/ID',
  status          VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：退货时效参数可信快照批号',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_return_no (return_no),
  UNIQUE KEY uk_return_biz (biz_return_key),
  -- ★ 三轮复核修正（P1 跨站同号）：sal_return 原无 site_code，按 (order_no,source_return_no)
  --   去重会误拒 AE/SA 同号订单的相同平台退货来源号。新增可空 site_code，唯一键取
  --   (site_code, order_no, source_return_no)：同站同号同源拦截、跨站同号可区分。
  -- ★ 四轮修正（NULL 唯一键失效）：改用生成列 site_key（NULL→'_UNSET_'）替代裸 site_code，
  --   避免两条 site_code=NULL 的相同订单/来源号因 NULL<>NULL 而重复落库。真实幂等仍以
  --   biz_return_key 为准；站点归属一致性由应用层按 requireSite + 父订单校验。
  UNIQUE KEY uk_return_source (site_key, order_no, source_return_no),
  KEY idx_order (order_no),
  KEY idx_appeal (appeal_deadline),
  CONSTRAINT chk_return_qty CHECK (qty > 0),
  CONSTRAINT chk_return_amount CHECK (compensation_amount >= 0 AND refund_amount >= 0 AND return_loss_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货与申诉时效；★ 幂等键 + 平台原始截止瞬间';

CREATE TABLE IF NOT EXISTS fin_statement (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_no    VARCHAR(64)  NOT NULL COMMENT '平台账单唯一参考号',
  statement_type  VARCHAR(16)  NOT NULL COMMENT 'WEEKLY / STORAGE（月度仓储账单独立核对）',
  site_code       VARCHAR(16)  NOT NULL,
  period_start    DATE         NOT NULL,
  period_end      DATE         NOT NULL,
  generated_at    DATETIME(3)  NOT NULL COMMENT 'UTC+8 周三生成',
  payout_date     DATE         COMMENT 'UTC+8 次周周四',
  gross_amount    DECIMAL(18,4) DEFAULT 0,
  fee_amount      DECIMAL(18,4) DEFAULT 0,
  net_amount      DECIMAL(18,4) DEFAULT 0,
  currency        CHAR(3)      NOT NULL,
  dispute_deadline DATETIME(3) COMMENT '★ UTC+8 generated_at + 15 天，逾期不受理（天数由 noon_rule_config 配）',
  dispute_deadline_raw_text VARCHAR(64) COMMENT '平台原始争议截止文本',
  dispute_deadline_raw_tz VARCHAR(64) COMMENT '平台原始时区偏移/ID',
  reconcile_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/MATCHED/DISPUTED/CLOSED',
  source_channel  VARCHAR(24)  NOT NULL DEFAULT 'EXPORT',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：争议天数等参数可信快照批号',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_statement_no (statement_no),
  KEY idx_site_period (site_code, period_start),
  KEY idx_dispute (dispute_deadline),
  CONSTRAINT chk_statement_period CHECK (period_end >= period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周结算单 + 月度仓储账单；保留平台原始争议截止瞬间';

CREATE TABLE IF NOT EXISTS fin_statement_line (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_id    BIGINT       NOT NULL,
  statement_no    VARCHAR(64)  NOT NULL,
  order_no        VARCHAR(64)  COMMENT '可关联到订单时填',
  sku_id          BIGINT,
  line_type       VARCHAR(32)  NOT NULL COMMENT 'SALE / REFERRAL_FEE / FBN_OUTBOUND / STORAGE / RETURN_FEE / AD_SPEND / COMPENSATION / OTHER',
  amount          DECIMAL(18,4) NOT NULL COMMENT '原币；AD_SPEND 保留（Q7 不做广告模块但账单会扣）',
  currency        CHAR(3)      NOT NULL,
  matched         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否与本地订单/费用匹配',
  raw_json        JSON         COMMENT '账单原始行，字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_statement (statement_id),
  KEY idx_order (order_no),
  KEY idx_type (line_type),
  CONSTRAINT chk_stmt_line_matched CHECK (matched IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单逐行对账';

CREATE TABLE IF NOT EXISTS fin_fee_dict (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  fee_code        VARCHAR(32)  NOT NULL COMMENT 'REFERRAL_FEE / FBN_OUTBOUND / STORAGE / RETURN_ADMIN / VAT ...',
  site_code       VARCHAR(16)  NOT NULL,
  category_code   VARCHAR(64)  DEFAULT '*' COMMENT '* 表示全类目',
  fulfillment_mode VARCHAR(24) NOT NULL DEFAULT 'FBN',
  rate_type       VARCHAR(16)  NOT NULL COMMENT 'PERCENT / FIXED / TIER',
  rate_value      DECIMAL(18,6) NOT NULL,
  min_amount      DECIMAL(18,4) DEFAULT 0 COMMENT '如 KSA 每单最低 1 SAR',
  currency        CHAR(3)      NOT NULL,
  effective_from  DATE         NOT NULL,
  effective_to    DATE,
  source          VARCHAR(128) COMMENT '费率来源（Seller Lab 费率表 / 官方政策），不把未确认字段当事实',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_lookup (fee_code, site_code, category_code, effective_from),
  CONSTRAINT chk_fee_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 版本化费用字典，费率年内会变，历史版本必须保留以支持重算';

CREATE TABLE IF NOT EXISTS fin_payout (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  payout_no       VARCHAR(64)  NOT NULL,
  statement_no    VARCHAR(64),
  site_code       VARCHAR(16)  NOT NULL,
  bank_txn_id     VARCHAR(128) COMMENT '平台 Bank Transaction ID',
  amount          DECIMAL(18,4) NOT NULL COMMENT '原币',
  currency        CHAR(3)      NOT NULL,
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL；缺失报错，不兜底 1',
  paid_at         DATETIME(3)  COMMENT 'UTC+8',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_payout_no (payout_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回款流水';

CREATE TABLE IF NOT EXISTS fin_expense_pool (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_no      VARCHAR(64)  NOT NULL,
  fee_type        VARCHAR(32)  NOT NULL COMMENT 'FREIGHT / DUTY / CUSTOMS / PACKAGING / FX_LOSS / PAYMENT_FEE / AD_SPEND',
  site_code       VARCHAR(16)  NOT NULL,
  period          CHAR(7)      NOT NULL COMMENT 'YYYY-MM UTC+8',
  asn_id          BIGINT       COMMENT '可按 ASN 批次归集',
  amount          DECIMAL(18,4) NOT NULL COMMENT '原币',
  currency        CHAR(3)      NOT NULL,
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL；缺失报错，不兜底 1',
  allocation_policy VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '维度A：NONE / PARTIAL / FULL，Q8 三档',
  included_in_partial TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'PARTIAL 模式下是否被勾选合并',
  remark          VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_expense_no (expense_no),
  KEY idx_period (period, site_code),
  CONSTRAINT chk_pool_alloc CHECK (allocation_policy IN ('NONE','PARTIAL','FULL')),
  CONSTRAINT chk_pool_partial CHECK (included_in_partial IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ L2 期间/批次费用池，与 FIFO 成本物理分离';

-- C-3：利润快照。唯一键加入 allocation_policy + sku_allocation_enabled + calc_batch_no + scope_type，
--   使 NONE/PARTIAL/FULL 三档并存、同公式多次重算各成一批次互不覆盖；维度B开关不同也各成快照。
--   两个维度独立：allocation_policy(维度A)只定 SITE 行 L2 合并范围；sku_allocation_enabled(维度B)
--   才决定是否把 SITE 行 L2 摊到 SKU。维度B关闭时 SKU 行 allocated_expense=0、net=自身 L1。
--   scope_type=SITE 时 sku_id=0，存站点总利润（L2 池全额）；仅维度B开启时 SKU 行按
--   allocation_basis 分摊，ΣSKU 分摊额 = SITE 行 allocated_expense（守恒）。
CREATE TABLE IF NOT EXISTS fin_profit_snapshot (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  period          CHAR(7)      NOT NULL,
  scope_type      VARCHAR(8)   NOT NULL DEFAULT 'SKU' COMMENT 'C-3：SKU / SITE；SITE 行 sku_id=0',
  sku_id          BIGINT       NOT NULL COMMENT 'C-3：SITE 行填 0',
  site_code       VARCHAR(16)  NOT NULL,
  revenue         DECIMAL(18,4) DEFAULT 0,
  platform_fee    DECIMAL(18,4) DEFAULT 0 COMMENT '佣金+FBN出库+仓储+退货费−补偿',
  cogs_fifo       DECIMAL(18,4) DEFAULT 0 COMMENT '★ 来自 inv_batch_consumption 汇总（含红冲净额）',
  contribution_margin DECIMAL(18,4) DEFAULT 0 COMMENT 'L1 = revenue - platform_fee - cogs_fifo',
  allocated_expense DECIMAL(18,4) DEFAULT 0 COMMENT '本行承担的 L2；SKU 行按 basis 分摊，不重复扣整池',
  net_profit      DECIMAL(18,4) DEFAULT 0 COMMENT 'L1 − allocated_expense（非 L1+L2，见 FR-08.6）',
  allocation_policy VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '维度A：NONE / PARTIAL / FULL，仅定 SITE 行 L2 合并范围',
  sku_allocation_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '维度B：0=SKU行不分摊(默认)；1=按basis分摊SITE行L2,ΣSKU=SITE',
  calc_batch_no   VARCHAR(64)  NOT NULL COMMENT 'C-3：每次计算的独立批次号，重算生成新批次',
  calc_input_version VARCHAR(64) NOT NULL COMMENT 'C-3：输入版本（费率/汇率/流水截止/费用池批次）',
  calc_input_snapshot JSON      COMMENT 'C-3：计算输入快照，保证结果可复算',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：本次计算所用参数的可信快照批号，引用 noon_config_snapshot',
  allocation_basis VARCHAR(16)  COMMENT '维度B方法：sku_allocation_enabled=1 时填 REVENUE_SHARE/QTY_SHARE/EQUAL；否则 NULL',
  basis_value     DECIMAL(18,6) DEFAULT 0 COMMENT 'C-3：该 SKU 的分摊基数（收入或数量）',
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY' COMMENT '归一后展示币种',
  formula_version VARCHAR(32)  NOT NULL COMMENT '自定义公式版本，支持全量重算',
  recalculated_at DATETIME(3)  COMMENT 'UTC+8',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_profit (period, site_code, scope_type, sku_id, formula_version, allocation_policy, sku_allocation_enabled, calc_batch_no),
  KEY idx_period (period),
  KEY idx_batch (calc_batch_no),
  CONSTRAINT chk_profit_scope CHECK (scope_type IN ('SKU','SITE')),
  CONSTRAINT chk_profit_alloc CHECK (allocation_policy IN ('NONE','PARTIAL','FULL')),
  CONSTRAINT chk_profit_sku_alloc CHECK (sku_allocation_enabled IN (0,1)),
  CONSTRAINT chk_profit_site_sku CHECK (scope_type <> 'SITE' OR sku_id = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 利润快照：三档 × 多批次 × SKU/SITE 双层，L1 贡献毛利 + 净利';

CREATE TABLE IF NOT EXISTS fx_rate (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  rate_date       DATE         NOT NULL COMMENT 'UTC+8 日切',
  from_currency   CHAR(3)      NOT NULL,
  to_currency     CHAR(3)      NOT NULL,
  rate            DECIMAL(18,8) NOT NULL COMMENT '缺失查表报错，禁止 DEFAULT 1 兜底',
  source          VARCHAR(64),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fx (rate_date, from_currency, to_currency),
  CONSTRAINT chk_fx_rate CHECK (rate > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日汇率：AED/SAR 不可直接相加，跨站点对比先归一；缺失报错';

-- -----------------------------------------------------------------------------
-- E. 补货
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rp_replenish_suggestion (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  suggestion_no   VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  warehouse_id    BIGINT       NOT NULL COMMENT '目标 FBN 仓',
  avg_daily_sales_7  DECIMAL(12,4) DEFAULT 0,
  avg_daily_sales_30 DECIMAL(12,4) DEFAULT 0,
  days_of_cover   DECIMAL(12,2) DEFAULT 0,
  qty_available   INT          DEFAULT 0,
  qty_in_transit  INT          DEFAULT 0,
  lead_time_days  INT          DEFAULT 0 COMMENT '生产+头程+入仓',
  safety_stock    INT          DEFAULT 0,
  event_factor    DECIMAL(8,4) DEFAULT 1 COMMENT '斋月 / Yellow Friday 大促系数',
  suggested_qty   INT          NOT NULL,
  source          VARCHAR(24)  NOT NULL DEFAULT 'MANUAL' COMMENT 'AI / MANUAL',
  source_agent    VARCHAR(64)  COMMENT 'WorkBuddy / Codex / 系统算法',
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT '★ DRAFT/CONFIRMED/CONVERTED，AI 只能写 DRAFT',
  reviewed_by     BIGINT       COMMENT '若依 sys_user.user_id',
  reviewed_at     DATETIME(3)  COMMENT 'UTC+8',
  converted_doc_type VARCHAR(24) COMMENT 'PUR_ORDER / TRANSFER / ASN',
  converted_doc_no   VARCHAR(64),
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：大促系数/安全库存参数可信快照批号',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sug_no (suggestion_no),
  KEY idx_sku_site (sku_id, site_code),
  KEY idx_status (status),
  CONSTRAINT chk_sug_qty CHECK (suggested_qty >= 0 AND qty_available >= 0 AND qty_in_transit >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货建议单，人工确认后才落单；AI 隔离写入';

-- -----------------------------------------------------------------------------
-- F. 集成 · 日志 · AI 外挂（权限/用户/角色由若依系统表承担，见文件头 P1-3）
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS int_sync_task (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_no         VARCHAR(64)  NOT NULL,
  api_domain      VARCHAR(64)  NOT NULL COMMENT 'CATALOG / PRICING / STOCK / OFFER / IMPEX / EVENT / FBPI ...',
  endpoint        VARCHAR(256) NOT NULL,
  site_code       VARCHAR(16),
  direction       VARCHAR(16)  NOT NULL COMMENT 'PULL / PUSH',
  payload         JSON,
  status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/DEAD',
  retry_count     INT          NOT NULL DEFAULT 0,
  next_run_at     DATETIME(3)  COMMENT 'UTC+8',
  last_error_code VARCHAR(64),
  last_error_msg  VARCHAR(1024),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_no (task_no),
  KEY idx_status_next (status, next_run_at),
  CONSTRAINT chk_sync_retry CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务：429 指数退避、失败补偿';

CREATE TABLE IF NOT EXISTS int_webhook_event (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(128) NOT NULL COMMENT '★ 幂等键，平台事件 ID',
  event_type      VARCHAR(128) NOT NULL,
  site_code       VARCHAR(16),
  payload_raw     JSON         NOT NULL COMMENT '原始报文，字段不删减',
  received_at     DATETIME(3)  NOT NULL COMMENT 'UTC+8',
  processed_at    DATETIME(3)  COMMENT 'UTC+8',
  status          VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/ACKNOWLEDGED/PROCESSED/FAILED/KILLED',
  retry_count     INT          NOT NULL DEFAULT 0,
  error_msg       VARCHAR(1024),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_type_time (event_type, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Event Notifications webhook 接收（每项目最多 5 个 destination）';

CREATE TABLE IF NOT EXISTS int_api_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id      VARCHAR(128) COMMENT '★ 响应头 X-Request-Id，提 Support Case 必用',
  endpoint        VARCHAR(256) NOT NULL,
  http_method     VARCHAR(8)   NOT NULL,
  http_status     INT,
  error_code      VARCHAR(64)  COMMENT '如 PERMISSION_DENIED / INVALID_ARGUMENT',
  ratelimit_remaining INT,
  burst_remaining INT,
  request_payload JSON,
  response_payload JSON,
  latency_ms      INT,
  site_code       VARCHAR(16),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_request_id (request_id),
  KEY idx_created (created_at),
  KEY idx_error (error_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量 API 留痕，带编号与时间戳';

-- C-1：导出类别码字典。三层验证状态分开存，禁止把「发现」写成「可用」。
--   P2-5：is_usable 改为 GENERATED STORED 列，由两层验证时间派生，杜绝状态漂移。
CREATE TABLE IF NOT EXISTS int_export_category (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  export_category_code VARCHAR(128) NOT NULL,
  erp_use              VARCHAR(128) COMMENT '对 ERP 的潜在用途（候选/后期/不用）',
  params_schema        JSON         COMMENT '原始响应 params.fields 完整保留',
  discovered_at        DATETIME(3)  COMMENT 'UTC+8 类别已发现（实测层）',
  create_tested_at     DATETIME(3)  COMMENT 'UTC+8 CreateExport 权限验证层；NULL=未验证',
  headers_tested_at    DATETIME(3)  COMMENT 'UTC+8 文件下载与列头验证层；NULL=未验证',
  is_usable            TINYINT(1)   GENERATED ALWAYS AS (
                         CASE WHEN create_tested_at IS NOT NULL AND headers_tested_at IS NOT NULL
                              THEN 1 ELSE 0 END) STORED COMMENT '★ P2-5 派生列：两层均验证才为 1；O-07 未验证前恒 0',
  request_id           VARCHAR(64)  COMMENT '发现时的 x-request-id，可回溯',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_export_category (export_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ C-1：导出类别三层验证字典，is_usable 为生成列';

CREATE TABLE IF NOT EXISTS int_export_job (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  export_code     VARCHAR(128) COMMENT 'CreateExport 返回的 export_code',
  export_category_code VARCHAR(128) NOT NULL COMMENT 'C-1：必须是 int_export_category 中已发现的码；is_usable=0 时仅可登记不可自动创建',
  params          JSON,
  site_code       VARCHAR(16),
  status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/READY/FAILED/BLOCKED(O-07 未验证)',
  file_url        VARCHAR(512),
  imported_count  INT          DEFAULT 0,
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_export_code (export_code),
  KEY idx_category (export_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reports(impex) 异步导出任务';

-- P1-3：原 v0.4 sys_audit_log 更名 noon_audit_log，避免与若依 sys_* 命名空间冲突。
--       若依 sys_oper_log / sys_loginlog 管框架操作审计；本表管 Noon 业务变更审计。
--       审核者/操作者统一存若依 sys_user.user_id，不再自建用户体系。
CREATE TABLE IF NOT EXISTS noon_audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  log_no          VARCHAR(64)  NOT NULL COMMENT '★ 日志编号',
  user_id         BIGINT       COMMENT '若依 sys_user.user_id；机器主体用授权主体 ID',
  action          VARCHAR(64)  NOT NULL,
  entity_type     VARCHAR(64)  NOT NULL,
  entity_id       VARCHAR(64),
  site_code       VARCHAR(16)  COMMENT 'v0.5：审计站点，配合站点授权',
  before_json     JSON,
  after_json      JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC+8',
  UNIQUE KEY uk_log_no (log_no),
  KEY idx_entity (entity_type, entity_id),
  KEY idx_created (created_at),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon 业务操作审计（更名自 sys_audit_log，避免若依冲突）';

-- ★ 用户决议 A：AI 正常读；写入走数据隔离（暂存层，不进正式表），审批通过才转正，
--   审核支持自动审核开关（默认人工，自动规则需显式配置与验证）。
--   未审批数据不参与库存/利润/账单计算：本表即隔离层，不写任何正式业务表。
--   ai_auto_review_enabled 与 ai_auto_review_rules 落 noon_rule_config，由管理端配置。
CREATE TABLE IF NOT EXISTS ai_suggestion (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  suggestion_no   VARCHAR(64)  NOT NULL,
  scene           VARCHAR(64)  NOT NULL COMMENT 'STATEMENT_RECON / EN_AR_COPY / REPLENISH / PRICING',
  source_agent    VARCHAR(64)  NOT NULL COMMENT 'WorkBuddy / Codex / Claude Code / 官方 MCP',
  site_code       VARCHAR(16)  COMMENT '业务真实站点；requireSite 校验后才能转正',
  payload_json    JSON         NOT NULL COMMENT 'AI 输出（建议内容，不是指令）；暂存不进正式表',
  confidence      DECIMAL(6,4),
  status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT '★ PENDING/APPROVED/REJECTED/CONVERTED，AI 不得直接改业务表',
  review_mode     VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AUTO；AUTO 需命中 noon_rule_config 自动审核规则',
  auto_rule_ref   VARCHAR(64)  COMMENT '命中的自动审核规则标识（config_key 或规则号）',
  reviewed_by     BIGINT       COMMENT '若依 sys_user.user_id；AUTO 模式记机器主体 ID',
  reviewed_at     DATETIME(3)  COMMENT 'UTC+8',
  review_note     VARCHAR(1024) COMMENT '人工/自动审核结论说明',
  converted_doc_type VARCHAR(24),
  converted_doc_no   VARCHAR(64),
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：审核规则参数快照批号（如启用自动审核）',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ai_sug_no (suggestion_no),
  KEY idx_scene_status (scene, status),
  CONSTRAINT chk_ai_review_mode CHECK (review_mode IN ('MANUAL','AUTO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ AI 写入隔离暂存表：正常读、审批转正、自动审核开关';

-- ★ Codex REPRO 反例修正：原表允许 INSERT 直接写 status='CONVERTED'、review_mode='AUTO'，
--   等于 AI 绕过暂存层与审批直接转正，违背「未审批数据不进正式链路」决议。
--   BEFORE INSERT 闸门：新建建议一律 PENDING + MANUAL；AUTO 只能由审核流程在
--   UPDATE 阶段按 noon_rule_config 自动审核规则命中后置位（不拦 UPDATE 转正）。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_ai_suggestion_insert_gate$$
CREATE TRIGGER trg_ai_suggestion_insert_gate BEFORE INSERT ON ai_suggestion
FOR EACH ROW
BEGIN
  IF NEW.status <> 'PENDING' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ai_suggestion 新建状态必须为 PENDING；转正须经审核 UPDATE';
  END IF;
  IF NEW.review_mode <> 'MANUAL' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ai_suggestion 新建 review_mode 必须为 MANUAL；AUTO 仅由审核流程置位';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_ai_suggestion_update_gate$$
CREATE TRIGGER trg_ai_suggestion_update_gate BEFORE UPDATE ON ai_suggestion
FOR EACH ROW
BEGIN
  -- ★ 三轮修正（Codex 第2点）：二轮仅禁终态回 PENDING，REJECTED 仍可改判 APPROVED/
  --   CONVERTED。改为显式状态转移白名单，DDL 固化合法流转，服务端再做主体/站点鉴权与审计。
  -- 合法转移：PENDING→APPROVED / PENDING→REJECTED / APPROVED→CONVERTED，
  --   以及保持原态（同状态 UPDATE 仅补充审核字段/说明）。
  -- 终态：REJECTED、CONVERTED 不得转出到任何其它状态；APPROVED 不得回退 PENDING。
  IF NEW.status <> OLD.status THEN
    IF NOT ((OLD.status = 'PENDING'   AND NEW.status IN ('APPROVED','REJECTED'))
         OR (OLD.status = 'APPROVED'  AND NEW.status = 'CONVERTED')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
        '非法状态转移：仅允许 PENDING→APPROVED/REJECTED、APPROVED→CONVERTED；终态不可改判';
    END IF;
  END IF;
  -- 1) 任何进入已审批态（含 REJECTED→改判被上面拦下）都必须有审核主体与时间，
  --    不再只约束 OLD=PENDING，防止缺审核信息直接写终态。
  IF NEW.status IN ('APPROVED','CONVERTED','REJECTED')
     AND (NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '审批态必须填写 reviewed_by 与 reviewed_at（UTC+8）';
  END IF;
  -- 2) 置 AUTO 必须给出 auto_rule_ref 证据；规则是否真实命中 noon_rule_config 的
  --    ai_auto_review_rules 且 ai_auto_review_enabled=true 由服务端审批接口校验
  --    （触发器无法查配置表，不在此虚构规则命中判断）。
  IF NEW.review_mode = 'AUTO' AND NEW.auto_rule_ref IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_mode=AUTO 必须提供 auto_rule_ref；无规则应走 MANUAL';
  END IF;
  -- 3) 转正（CONVERTED）必须有审批依据：自动规则引用或人工审核意见之一；
  IF NEW.status = 'CONVERTED' AND OLD.status <> 'CONVERTED'
     AND NEW.auto_rule_ref IS NULL AND NEW.review_note IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CONVERTED 必须有 auto_rule_ref 或 review_note 作为审批依据';
  END IF;
  -- 4) ★ 五轮修正：审批转换的同一条 UPDATE 不得夹带修改载荷。
  --    审核所见即 OLD.payload_json；PENDING→APPROVED/REJECTED 时 payload 必须保持原值。
  --    要改载荷只能在 PENDING 阶段由提案方改，审批方只改状态/审核字段；
  --    "审核所见 qty=1、审批瞬间改 qty=999"在 DDL 层直接拒绝，所见即所批。
  IF OLD.status = 'PENDING' AND NEW.status IN ('APPROVED','REJECTED')
     AND NOT (NEW.payload_json <=> OLD.payload_json) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
      '审批转换不得夹带修改 payload_json；审核所见载荷须与审批载荷一致，改载荷须新建建议';
  END IF;
  -- 5) ★ 四轮修正：审核一旦发生（OLD 已非 PENDING），审核对象与证据字段不可再变。
  --    防止"先正常批 PENDING→APPROVED，再同态改 payload_json qty 1→999、改 reviewed_by，
  --    随后照常 CONVERTED"的载荷掉包。终态 REJECTED/CONVERTED 同样锁定。
  --    review_note 允许后续补充审计说明；NULL-safe 比较用 <=>。
  IF OLD.status <> 'PENDING' THEN
    IF NOT (NEW.payload_json <=> OLD.payload_json)
       OR NOT (NEW.reviewed_by  <=> OLD.reviewed_by)
       OR NOT (NEW.reviewed_at  <=> OLD.reviewed_at)
       OR NOT (NEW.auto_rule_ref <=> OLD.auto_rule_ref)
       OR NOT (NEW.site_code    <=> OLD.site_code)
       OR NOT (NEW.source_agent <=> OLD.source_agent)
       OR NOT (NEW.scene        <=> OLD.scene) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
        '审批后 payload_json/reviewed_by/reviewed_at/auto_rule_ref/site_code/source_agent/scene 不可变；载荷掉包须新建建议';
    END IF;
  END IF;
END$$
DELIMITER ;
-- 审批闭环边界：上述触发器是数据库层不变量，不等于完整审批服务。服务端审批接口仍须
-- 校验 review_mode=AUTO 时 auto_rule_ref 命中 noon_rule_config 的 ai_auto_review_rules
-- 且 ai_auto_review_enabled=true，记录独立审核主体 sys_user.user_id 与站点 requireSite，
-- 写 noon_audit_log，转正才写正式业务表；并发转正需行锁。普通应用/AI 主体无 UPDATE 高权限，
-- 触发器与账号权限叠加，未经审批不可正式写入。

-- -----------------------------------------------------------------------------
-- G. AI 外挂只读视图（配合官方 MCP Read 权限，物理隔离写操作）
-- -----------------------------------------------------------------------------

-- D-01 修复：库存按 (sku_id, site_code) 聚合，避免同站点多仓导致行数放大。
-- 口径说明：warehouse_count 为该 SKU 在该站点的仓数；同一 SKU 若有多条
-- Partner SKU，每行是同一份站点汇总值，AI 不得把多行库存相加。
CREATE OR REPLACE VIEW v_ai_sku_overview AS
SELECT s.sku_code,
       s.name_en,
       s.category_code,
       p.partner_sku,
       p.site_code,
       COALESCE(SUM(l.qty_available),  0) AS qty_available,
       COALESCE(SUM(l.qty_in_transit), 0) AS qty_in_transit,
       COALESCE(SUM(l.qty_reserved),   0) AS qty_reserved,
       COALESCE(SUM(l.qty_unsellable), 0) AS qty_unsellable,
       COUNT(DISTINCT l.warehouse_id)     AS warehouse_count
FROM md_sku s
JOIN md_partner_sku p ON p.sku_id = s.id
LEFT JOIN inv_stock_ledger l ON l.sku_id = s.id AND l.site_code = p.site_code
GROUP BY s.id, s.sku_code, s.name_en, s.category_code,
         p.id, p.partner_sku, p.site_code;

-- P1-2：原 v_ai_profit 混合 SITE（汇总）与 SKU（明细）行，AI SUM 会翻倍。
--   v0.5 拆成两个物理隔离视图，并补 sku_allocation_enabled（维度B）供口径判断。
--   禁止跨 scope_type 相加；SITE 行是汇总，SKU 行是明细。
CREATE OR REPLACE VIEW v_ai_profit_site AS
SELECT period, site_code, scope_type, sku_id, revenue, platform_fee, cogs_fifo,
       contribution_margin, allocated_expense, net_profit,
       allocation_policy, sku_allocation_enabled, allocation_basis,
       formula_version, calc_batch_no, calc_input_version, config_snapshot_batch_no
FROM fin_profit_snapshot
WHERE scope_type = 'SITE' AND allocation_policy IN ('NONE','PARTIAL','FULL');

CREATE OR REPLACE VIEW v_ai_profit_sku AS
SELECT period, site_code, scope_type, sku_id, revenue, platform_fee, cogs_fifo,
       contribution_margin, allocated_expense, net_profit,
       allocation_policy, sku_allocation_enabled, allocation_basis,
       formula_version, calc_batch_no, calc_input_version, config_snapshot_batch_no
FROM fin_profit_snapshot
WHERE scope_type = 'SKU' AND allocation_policy IN ('NONE','PARTIAL','FULL');
-- 注意：维度B关闭（sku_allocation_enabled=0）时 SKU 行 net_profit = 自身 L1，
--       allocated_expense = 0；只有启用且同一 calc_batch_no 下 ΣSKU 分摊才等于 SITE 行。

CREATE OR REPLACE VIEW v_ai_statement_open AS
SELECT statement_no, statement_type, site_code, period_start, period_end,
       generated_at, net_amount, currency, dispute_deadline,
       dispute_deadline_raw_text, dispute_deadline_raw_tz, reconcile_status
FROM fin_statement
WHERE reconcile_status <> 'CLOSED';

CREATE OR REPLACE VIEW v_ai_replenish AS
SELECT suggestion_no, sku_id, site_code, days_of_cover, qty_available,
       qty_in_transit, suggested_qty, status, source_agent
FROM rp_replenish_suggestion;

-- -----------------------------------------------------------------------------
-- H. 初始化（P1-4：ON DUPLICATE KEY 用 MySQL 8.0.20+ 行别名写法）
-- -----------------------------------------------------------------------------

-- 预置三种 scope 的持久锁行（首次写入前先锁，避免无版本行时锁空集）。
INSERT INTO noon_rule_config_lock (config_key, scope) VALUES
  ('cost_method','GLOBAL'), ('cost_method','AE'), ('cost_method','SA'),
  ('vat_mode','GLOBAL'), ('vat_mode','AE'), ('vat_mode','SA'),
  ('allocation_policy','GLOBAL'), ('allocation_policy','AE'), ('allocation_policy','SA'),
  ('return_window_days','GLOBAL'), ('return_window_days','AE'), ('return_window_days','SA'),
  ('appeal_window_days','GLOBAL'), ('appeal_window_days','AE'), ('appeal_window_days','SA'),
  ('statement_dispute_days','GLOBAL'), ('statement_dispute_days','AE'), ('statement_dispute_days','SA'),
  ('payout_threshold','GLOBAL'), ('payout_threshold','AE'), ('payout_threshold','SA'),
  ('return_restock_cost','GLOBAL'), ('return_restock_cost','AE'), ('return_restock_cost','SA'),
  ('stock_update_mode','GLOBAL'), ('stock_update_mode','AE'), ('stock_update_mode','SA'),
  ('api_max_destinations','GLOBAL'), ('api_max_destinations','AE'), ('api_max_destinations','SA'),
  ('sku_allocation_enabled','GLOBAL'), ('sku_allocation_enabled','AE'), ('sku_allocation_enabled','SA'),
  ('profit_allocation_basis','GLOBAL'), ('profit_allocation_basis','AE'), ('profit_allocation_basis','SA'),
  ('order_idempotent_mode','GLOBAL'), ('order_idempotent_mode','AE'), ('order_idempotent_mode','SA'),
  ('ai_auto_review_enabled','GLOBAL'), ('ai_auto_review_enabled','AE'), ('ai_auto_review_enabled','SA'),
  ('ai_auto_review_rules','GLOBAL'), ('ai_auto_review_rules','AE'), ('ai_auto_review_rules','SA')
AS new ON DUPLICATE KEY UPDATE created_at = noon_rule_config_lock.created_at;

-- P1-3：Noon 业务规则参数从 sys_config 迁到 noon_rule_config（基调 GLOBAL）。
-- 字段语义：scope=GLOBAL|AE|SA；version_no=1；effective_from UTC+8；
-- created_by=1 表示若依超管；撤销与审计按上文契约执行。
INSERT INTO noon_rule_config
 (config_key, scope, config_value, version_no, effective_from, created_by, remark) VALUES
  ('cost_method','GLOBAL','FIFO',1,'2026-11-12 00:00:00',1,'Q5：FIFO 批次成本'),
  ('vat_mode','GLOBAL','IGNORE',1,'2026-11-12 00:00:00',1,'Q6：不做 VAT；见 R7 毛利虚高风险'),
  ('allocation_policy','GLOBAL','NONE',1,'2026-11-12 00:00:00',1,'维度A：默认不合并 L2，可切 PARTIAL / FULL；仅定 SITE 行'),
  ('return_window_days','GLOBAL','15',1,'2026-11-12 00:00:00',1,'C-03：14/15 天冲突，取宽口径'),
  ('appeal_window_days','GLOBAL','14',1,'2026-11-12 00:00:00',1,'C-03：Directship 7 天需按场景覆盖'),
  ('statement_dispute_days','GLOBAL','15',1,'2026-11-12 00:00:00',1,'F-10：逾期争议不受理'),
  ('payout_threshold','GLOBAL','1000',1,'2026-11-12 00:00:00',1,'C-02：100/1000 冲突，按站点可覆盖'),
  ('return_restock_cost','GLOBAL','ORIGINAL',1,'2026-11-12 00:00:00',1,'退货二次上架批次成本口径'),
  ('stock_update_mode','GLOBAL','ABSOLUTE',1,'2026-11-12 00:00:00',1,'N-11：Stock API 是绝对值 set，不是增量'),
  ('api_max_destinations','GLOBAL','5',1,'2026-11-12 00:00:00',1,'N-13：每项目最多 5 个 webhook destination'),
  ('sku_allocation_enabled','GLOBAL','0',1,'2026-11-18 00:00:00',1,'维度B开关：0=关闭(默认,L2仅SITE行合并)；1=按basis分摊SITE行L2,ΣSKU=SITE'),
  ('profit_allocation_basis','GLOBAL','REVENUE_SHARE',1,'2026-11-17 00:00:00',1,'维度B方法参数(非开关)：收入占比分摊；用户确认默认'),
  ('order_idempotent_mode','GLOBAL','FINGERPRINT_ACK',1,'2026-11-17 00:00:00',1,'同指纹 ACK；同订单新状态按状态机更新'),
  ('ai_auto_review_enabled','GLOBAL','false',1,'2026-11-18 00:00:00',1,'用户决议A：自动审核开关默认关(人工)；显式配置才启用'),
  ('ai_auto_review_rules','GLOBAL','[]',1,'2026-11-18 00:00:00',1,'用户决议A：自动审核规则(JSON)，启用前需验证；空数组表示无规则')
AS new ON DUPLICATE KEY UPDATE remark = new.remark;

-- 新配置键需同步补锁行（上面已预置四类新键；若有遗漏由管理端 INSERT IGNORE 补齐）。

-- C-1：12 个 export_category_code 初始化。全部来自 INT-NOON-ERP-001 §3.2 原始响应。
-- P2-5：is_usable 为生成列，INSERT 不再写该列，由两个 tested_at 派生（当前均 NULL → 0）。
INSERT INTO int_export_category
 (export_category_code, erp_use, discovered_at, create_tested_at, headers_tested_at, request_id) VALUES
  ('noon_catalog_catalogexport', '商品目录基线', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_catalog_content_contentrejections', '内容驳回(Q4不用)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('fbn_inventory_fbninventorydetailreport', 'FBN库存日结明细(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_financeweb_invoicesandcreditnotes', '发票与贷项(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_catalog_reports_productviewsandsalesdata', '销量数据(后期)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_catalog_globalcatalogexport', 'Global目录(本版不用)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_financeweb_transactionviewreportonitemlevel', 'item级交易(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('fbn_inventoryv2_ledgerdetailedview', 'FBN库存台账明细(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('fbn_inventoryv2_ledgersummaryview', 'FBN库存汇总(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('fbn_inventoryv2_aging', 'FBN库龄(后期)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('noon_noonoms_ordersexport', 'FBN订单导出(候选)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e'),
  ('namshi_financestatement', 'Namshi财务报表(Q9不用)', '2026-11-13 21:22:00', NULL, NULL, 'req_000_01a1d5128fac7614870d3d4526eb900e')
AS new ON DUPLICATE KEY UPDATE erp_use = new.erp_use, discovered_at = new.discovered_at;

-- =============================================================================
-- 待定（v0.5 不虚构完成，需与 Codex 对接后另开工包）
--   * 正式 Repository 实现：noon_rule_config 历史查询/锁行/审计的事务代码与原生 MySQL
--     并发测试（首次竞争、锁等待、回滚释放）；框架侧 VersionedConfigReader 已就位但未接表。
--   * 管理 API：参数写/撤销、站点授权（若依侧已有界面）、AI 审核规则配置。
--   * 追溯性更正/双时态：撤销生效时刻已建模，但 record time / 双时态未实现。
--   * 业务落快照：各业务表 config_snapshot_batch_no 已留字段，写入时机与事务由 WP2 接入。
--   * 从若依 sys_user_site 读取授权：超管 userId=1 全站点、空授权默认拒绝，框架侧已实现。
--   * 数据迁移脚本：旧 sys_config → noon_rule_config、sys_audit_log → noon_audit_log 的
--     生产迁移需单独评审，本文只给目标结构，不含生产写操作。
-- ★四轮修正（父子真实身份一致性）：只在父记录真实存在时强制关联一致，兼容历史导入/
--   隔离测试裸插（父不存在则不校验）。这不证明普通应用能越权（业务 API 未实现）；
--   站点归属与 requireSite 仍由服务端写服务保证，DDL 只封"引用了真实父记录却声明矛盾身份"。
--   置于文件末尾是因为触发器依赖 sal_order/sal_order_item/sal_return 三表均已创建。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_sal_item_parent_check$$
CREATE TRIGGER trg_sal_item_parent_check BEFORE INSERT ON sal_order_item
FOR EACH ROW
BEGIN
  DECLARE p_order_no VARCHAR(64);
  SELECT order_no INTO p_order_no FROM sal_order WHERE id = NEW.sal_order_id;
  IF p_order_no IS NOT NULL AND NEW.order_no <> p_order_no THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sal_order_item.order_no 与父订单不一致';
  END IF;
END$$
-- ★ 五轮修正：INSERT 校验可被随后 UPDATE 绕过（合法行改成错误 order_no）。
--   同口径补 BEFORE UPDATE：父订单存在时 order_no 不得改成与父不一致；父不存在放行。
DROP TRIGGER IF EXISTS trg_sal_item_parent_check_upd$$
CREATE TRIGGER trg_sal_item_parent_check_upd BEFORE UPDATE ON sal_order_item
FOR EACH ROW
BEGIN
  DECLARE p_order_no VARCHAR(64);
  SELECT order_no INTO p_order_no FROM sal_order WHERE id = NEW.sal_order_id;
  IF p_order_no IS NOT NULL AND NEW.order_no <> p_order_no THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sal_order_item.order_no 与父订单不一致（UPDATE 篡改）';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_sal_return_parent_check$$
CREATE TRIGGER trg_sal_return_parent_check BEFORE INSERT ON sal_return
FOR EACH ROW
BEGIN
  DECLARE p_site VARCHAR(16);
  DECLARE p_order_no VARCHAR(64);
  SELECT so.site_code, so.order_no INTO p_site, p_order_no
    FROM sal_order_item si JOIN sal_order so ON so.id = si.sal_order_id
   WHERE si.id = NEW.sal_order_item_id;
  -- ★ 五轮修正：关联订单行真实存在时，除站点外 order_no 也须与父订单一致，
  --   防"站点对但 order_no 乱填"的伪造关联。行不存在则放行（兼容历史导入/隔离裸插）。
  IF p_site IS NOT NULL AND NEW.site_code IS NOT NULL AND NEW.site_code <> p_site THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sal_return.site_code 与关联订单行的父订单站点不一致';
  END IF;
  IF p_order_no IS NOT NULL AND NEW.order_no <> p_order_no THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sal_return.order_no 与关联订单行的父订单不一致';
  END IF;
END$$
DELIMITER ;
-- =============================================================================
-- END OF SCHEMA v0.5
-- =============================================================================
