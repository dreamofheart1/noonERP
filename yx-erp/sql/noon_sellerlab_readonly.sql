-- -----------------------------------------------------------------------------
-- M15 Seller Lab 只读查询接入：FBN 库存快照 + Sales 销售明细 + ASN 只读快照
-- 原则：新表独立存放平台快照，不覆盖 ERP 业务账表、不过账，带 source 与 synced_at
-- 幂等：MySQL 8.0 下 CREATE TABLE IF NOT EXISTS 可重复执行。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS noon_fbn_inventory_snapshot (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code       VARCHAR(8)   NOT NULL COMMENT 'AE/SA（ERP 白名单）',
  partner_sku     VARCHAR(128) NOT NULL,
  sku             VARCHAR(128),
  warehouse_code  VARCHAR(128) NOT NULL,
  saleable        INT          NOT NULL DEFAULT 0,
  non_saleable    INT          NOT NULL DEFAULT 0,
  qty             INT          NOT NULL DEFAULT 0,
  captured_at     DATETIME(3)  NOT NULL COMMENT '平台返回的数据日期/抓取时刻',
  source          VARCHAR(32)  NOT NULL DEFAULT 'NOON_SELLERLAB',
  synced_at       DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_fbn_snap (site_code, partner_sku, warehouse_code, captured_at),
  KEY idx_fbn_site (site_code, synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon Seller Lab FBN 库存只读快照';

CREATE TABLE IF NOT EXISTS noon_sales_item (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code         VARCHAR(8)   NOT NULL COMMENT 'AE/SA（ERP 白名单）',
  item_nr           VARCHAR(128) NOT NULL COMMENT '平台订单明细标识（非整单号）',
  partner_sku       VARCHAR(128),
  sku               VARCHAR(128),
  status            VARCHAR(64),
  fulfillment_model VARCHAR(64),
  order_ts          DATETIME(3),
  shipment_ts       DATETIME(3),
  delivered_ts      DATETIME(3),
  price_amount      DECIMAL(18,4),
  currency_code     CHAR(3),
  awb_nr            VARCHAR(128),
  source            VARCHAR(32)  NOT NULL DEFAULT 'NOON_SELLERLAB',
  synced_at         DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_sales_item (site_code, item_nr),
  KEY idx_sales_site (site_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon Seller Lab 销售明细（item_nr 为明细标识）';


-- ASN（用户 2026-11-24 优先级提前：只读拉取留存；平台编号与 ERP 内部编号分开，不自动关联/不过账）
CREATE TABLE IF NOT EXISTS noon_asn_platform (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code VARCHAR(8) NOT NULL,
  platform_asn_nr VARCHAR(128) NOT NULL COMMENT 'Noon 平台 ASN 编号',
  status VARCHAR(64),
  dst_warehouse_code VARCHAR(128),
  expected_qty INT,
  qty_received INT,
  qty_putaway INT,
  platform_updated_at DATETIME(3),
  items_json JSON COMMENT '平台可得明细原文',
  source VARCHAR(32) NOT NULL DEFAULT 'NOON_SELLERLAB',
  synced_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_asn_platform (site_code, platform_asn_nr),
  KEY idx_asn_site (site_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon Seller Lab ASN 只读快照（非 ERP 内部 ASN）'
