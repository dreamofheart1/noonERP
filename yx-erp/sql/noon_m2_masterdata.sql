-- M2 商品与基础资料：md_ 主数据表增量迁移（执行者：豆包，2026-11-21）
-- 设计源：docs/noon-erp-schema-v0.5.sql 第 44–136 行；本文件不改主 schema 文件。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，重复执行只跳过已存在表，不修改既有结构。
-- 前置条件：库为 MySQL 8.0+ / utf8mb4；本文件不依赖若依业务表（sys_user_site 由 WP1 独立维护）。
-- 范围：仅 6 张 md_ 表；不含种子数据、不含菜单权限（noon_m2_menu.sql 属 WorkBuddy）。

-- -----------------------------------------------------------------------------
-- A. 主数据（与 schema v0.5 逐字段一致）
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
  noon_sku        VARCHAR(128) COMMENT 'Noon 平台 SKU，由商品同步回读',
  live_status     TINYINT(1)   DEFAULT NULL COMMENT '由 Offer API 回读，本模块不写入',
  offer_issues    JSON         COMMENT 'GetProductOffers 的 offer_issues 原文，本模块不写入',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  synced_at       DATETIME(3),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_partner_sku (partner_sku, site_code, business_model),
  KEY idx_sku (sku_id),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Q4 不做刊登，此表是唯一必需的商品表：订单回查与利润归因';

-- -----------------------------------------------------------------------------
-- B. 变更审计（FR-01.4）：append-only，与业务写入同事务；业务回滚时审计同样不落库。
-- 应用侧只 INSERT/SELECT，不提供 update/delete 入口；before/after 为行快照 JSON。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS noon_m2_audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_type   VARCHAR(32)  NOT NULL COMMENT 'STORE / SITE / WAREHOUSE / SKU / PARTNER_SKU',
  entity_id     BIGINT       NOT NULL,
  action        VARCHAR(16)  NOT NULL COMMENT 'INSERT / UPDATE / STATUS',
  operator_id   BIGINT       COMMENT '若依 user_id；无法取到登录人时为 NULL',
  operator_name VARCHAR(64)  COMMENT '登录名；取不到时 UNKNOWN',
  before_json   JSON         COMMENT '变更前行快照；新增为 NULL',
  after_json    JSON         COMMENT '变更后行快照；含 DB 生成列',
  changed_at    DATETIME(3)  NOT NULL COMMENT '业务时刻（UTC+8）',
  KEY idx_entity (entity_type, entity_id, changed_at),
  CONSTRAINT chk_m2_audit_action CHECK (action IN ('INSERT','UPDATE','STATUS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M2 主数据变更审计（append-only）';
