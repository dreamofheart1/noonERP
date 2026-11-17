-- =============================================================================
-- Noon ERP schema v0.3  ·  MySQL 8.0 / utf8mb4
-- 范围：FBN 单履约模式 | FIFO 批次成本 | 双层利润 | 无审批/无刊登/无广告/无 VAT 申报
-- 配套文档：docs/noon-erp-prd-v1.0.md（PRD v1.0.1 决议落地修订）
-- 基线版本：docs/noon-erp-schema-v0.2.sql（44 表 + 4 视图，原文件保持不变）
-- 修订时间：2026-11-13 20:22 (GMT+8)
--
-- v0.3 修订清单（PRD §6.4 的 D-01~D-03，用户授权「按推荐执行」）：
--   D-01 [修复] v_ai_sku_overview 原按 l.site_code = p.site_code 直接关联，
--        而 inv_stock_ledger 以 (sku_id, warehouse_id) 唯一 —— 同站点多仓会产生
--        多行、使 AI 读到的库存数量被放大。现改为按 (sku, site) 聚合 SUM(qty_*)
--        并输出 warehouse_count；同一 SKU 若有多条 Partner SKU，每行展示的是
--        同一份站点汇总，禁止跨行相加。
--   D-02 [口径] sal_order 冗余字段仅用于导入快速落库，成本核算粒度以
--        sal_order_item 为准；导入事务必须校验主表 qty = SUM(明细 qty)、
--        gross_amount 与行金额合计一致，见 PRD FR-13.2。
--   D-03 [口径] 全局时间统一以 Asia/Shanghai (GMT+8) 存储；平台原始时间字符串
--        保留在 raw_payload / raw_json，站点本地时间仅在展示层换算。
--
-- 环境策略（PRD 决议 A-03）：生产 / 沙箱共用同一份 Service Account .json 凭据，
--   由 md_partner_account.env (SANDBOX / PRODUCTION) + base_url / sandbox_url 区分；
--   凭据本身不携带环境标识（channel_identifier 中的 test 是用户名，不是环境）。
--   credential_ref 只存服务端加密文件路径引用，私钥不入库、不进 Git。
--
-- 设计约定：
--   1. 不使用物理外键，全部逻辑关联，便于单体模块拆分与批量导入。
--   2. 所有金额 DECIMAL(18,4)，汇率 DECIMAL(18,8)，数量 INT。
--   3. raw_payload / raw_json 保存平台原始报文，字段不删减（用户要求）。
--   4. 所有日志/单据带业务编号 + created_at 时间戳。
--   5. 平台规则一律落 sys_config，不写死在 Java 常量。
--   6. 所有 DATETIME 统一 GMT+8 存储（D-03），与 PRD NFR-11 对齐。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- A. 主数据与配置
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS md_partner_account (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_code    VARCHAR(64)  NOT NULL COMMENT 'Noon 项目码，如 PRJ468049',
  account_name    VARCHAR(128) COMMENT 'Partner 账号名',
  credential_ref  VARCHAR(512) COMMENT 'Service Account .json 在服务端加密存储的路径引用，私钥不入库',
  base_url        VARCHAR(256) NOT NULL DEFAULT 'https://noon-api-gateway.noon.partners',
  sandbox_url     VARCHAR(256) DEFAULT 'https://noon-sandbox-api-gateway.noon.partners',
  user_agent      VARCHAR(128) NOT NULL DEFAULT 'NoonErpClient/1.0' COMMENT '所有 API 请求必须携带',
  env             VARCHAR(16)  NOT NULL DEFAULT 'SANDBOX' COMMENT 'SANDBOX / PRODUCTION',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_project (project_code)
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺（Q2：当前 1 个）';

CREATE TABLE IF NOT EXISTS md_site (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code       VARCHAR(16)  NOT NULL COMMENT 'AE / SA',
  store_id        BIGINT       NOT NULL,
  country         VARCHAR(64),
  currency        CHAR(3)      NOT NULL COMMENT 'AED / SAR',
  vat_rate        DECIMAL(6,4) DEFAULT 0 COMMENT 'AE 0.05 / SA 0.15，v0.2 仅记录不参与计算',
  timezone        VARCHAR(64)  DEFAULT 'Asia/Dubai',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_store_site (store_id, site_code)
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
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wh_code (warehouse_code, site_code),
  KEY idx_site (site_code)
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
  KEY idx_sup (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU-供应商报价（历史价保留）';

CREATE TABLE IF NOT EXISTS sys_config (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key      VARCHAR(128) NOT NULL COMMENT 'vat_mode / payout_threshold / return_window_days / cost_method / allocation_policy / return_restock_cost ...',
  config_value    VARCHAR(1024) NOT NULL,
  scope_key       VARCHAR(128) DEFAULT 'GLOBAL' COMMENT '可按 site_code 覆盖',
  effective_from  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  remark          VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cfg (config_key, scope_key, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台规则参数化：v0.1 的 C-01~C-07 全部落此表';

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
  operator_id     BIGINT,
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
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  fx_rate         DECIMAL(18,8) DEFAULT 1,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_pur (pur_order_id),
  KEY idx_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单行';

CREATE TABLE IF NOT EXISTS inv_batch (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_no        VARCHAR(64)  NOT NULL COMMENT 'FIFO 成本批次号',
  sku_id          BIGINT       NOT NULL,
  pur_order_item_id BIGINT     COMMENT '采购来源；退货二次上架时为空',
  warehouse_id    BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  received_at     DATETIME(3)  NOT NULL COMMENT 'FIFO 排序第一键',
  qty_in          INT          NOT NULL,
  qty_remaining   INT          NOT NULL COMMENT '剩余数量，扣到 0 后关闭批次',
  unit_cost       DECIMAL(18,4) NOT NULL COMMENT '采购单价（不含头程/关税，见 Q8）',
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  fx_rate         DECIMAL(18,8) DEFAULT 1,
  source_type     VARCHAR(24)  NOT NULL DEFAULT 'PURCHASE' COMMENT 'PURCHASE / RETURN_RESTOCK / ADJUSTMENT',
  status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_batch_no (batch_no),
  KEY idx_fifo (sku_id, received_at, id),
  KEY idx_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ FIFO 成本批次，Q5 核心表';

CREATE TABLE IF NOT EXISTS inv_batch_consumption (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumption_no  VARCHAR(64)  NOT NULL,
  batch_id        BIGINT       NOT NULL COMMENT 'inv_batch.id',
  sal_order_item_id BIGINT     COMMENT '销售订单行',
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  unit_cost       DECIMAL(18,4) NOT NULL,
  amount          DECIMAL(18,4) NOT NULL COMMENT '= qty * unit_cost，汇总即 COGS',
  consumed_at     DATETIME(3)  NOT NULL,
  move_type       VARCHAR(24)  NOT NULL DEFAULT 'SALE' COMMENT 'SALE / RETURN_LOSS / STOCKTAKE',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_consumption_no (consumption_no),
  KEY idx_batch (batch_id),
  KEY idx_order_item (sal_order_item_id),
  KEY idx_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ FIFO 批次消耗流水，COGS 唯一来源';

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
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存四态台账';

CREATE TABLE IF NOT EXISTS inv_stock_move (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  move_no         VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  batch_id        BIGINT,
  change_type     VARCHAR(32)  NOT NULL COMMENT 'INBOUND/OUTBOUND/TRANSFER_IN/TRANSFER_OUT/STOCKTAKE_GAIN/STOCKTAKE_LOSS/RETURN_RESTOCK',
  qty_delta       INT          NOT NULL,
  ref_doc_type    VARCHAR(32),
  ref_doc_no      VARCHAR(64),
  operator_id     BIGINT,
  raw_payload     JSON         COMMENT '平台原始字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_move_no (move_no),
  KEY idx_sku_wh (sku_id, warehouse_id),
  KEY idx_ref (ref_doc_type, ref_doc_no)
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
  KEY idx_inbound (inbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单行，到货即生成 FIFO 批次';

CREATE TABLE IF NOT EXISTS inv_transfer (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_no     VARCHAR(64)  NOT NULL,
  from_warehouse_id BIGINT     NOT NULL,
  to_warehouse_id BIGINT       NOT NULL,
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_TRANSIT/RECEIVED/CLOSED',
  shipped_at      DATETIME(3),
  received_at     DATETIME(3),
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
  diff_qty        INT          DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_transfer (transfer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单行，在途与收货差异';

CREATE TABLE IF NOT EXISTS inv_stocktake (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  stocktake_no    VARCHAR(64)  NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  period          CHAR(7)      COMMENT 'YYYY-MM',
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
  qty_diff        INT          NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_stocktake (stocktake_id)
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
  sealed_at       DATETIME(3)  COMMENT '术语表：seal 之后才能预约仓库 slot',
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
  KEY idx_asn (asn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ASN 明细与收货差异';

CREATE TABLE IF NOT EXISTS log_shipment_cost (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  cost_no         VARCHAR(64)  NOT NULL,
  asn_id          BIGINT,
  site_code       VARCHAR(16)  NOT NULL,
  period          CHAR(7)      COMMENT 'YYYY-MM，L2 归集期间',
  fee_type        VARCHAR(32)  NOT NULL COMMENT 'FREIGHT / DUTY / CUSTOMS / PACKAGING / LABEL',
  amount          DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  fx_rate         DECIMAL(18,8) DEFAULT 1,
  allocation_flag VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE=不摊进批次成本（Q8 默认）/ PARTIAL / FULL',
  remark          VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cost_no (cost_no),
  KEY idx_asn (asn_id),
  KEY idx_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程/关税/包材费用：只登记，不摊进 FIFO 批次';

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
  gross_amount    DECIMAL(18,4) NOT NULL COMMENT '含税售价（注意 v0.2 不计 VAT，见 R7）',
  vat_amount      DECIMAL(18,4) DEFAULT NULL COMMENT 'Q6 不做 VAT，字段预留可空',
  currency        CHAR(3)      NOT NULL,
  fx_rate         DECIMAL(18,8) DEFAULT 1,
  payment_type    VARCHAR(16)  DEFAULT 'PAID' COMMENT 'COD / PAID，中东 COD 占比高',
  status          VARCHAR(32)  NOT NULL DEFAULT 'IMPORTED' COMMENT 'IMPORTED/DELIVERED/RETURNED/CANCELLED',
  ordered_at      DATETIME(3),
  delivered_at    DATETIME(3)  COMMENT '周账单按妥投归期',
  source_channel  VARCHAR(24)  NOT NULL DEFAULT 'EXPORT' COMMENT 'API / EXPORT / WEBHOOK / MANUAL',
  raw_payload     JSON         COMMENT '平台原始订单报文，字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_no (order_no, site_code),
  KEY idx_sku (sku_id),
  KEY idx_delivered (delivered_at),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表（FBN 订单无公开 API，按导入设计）';

CREATE TABLE IF NOT EXISTS sal_order_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sal_order_id    BIGINT       NOT NULL,
  order_no        VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  unit_price      DECIMAL(18,4) NOT NULL,
  cogs_amount     DECIMAL(18,4) DEFAULT 0 COMMENT '由 inv_batch_consumption 汇总回填',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_order (sal_order_id),
  KEY idx_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单行，FIFO COGS 归集粒度';

CREATE TABLE IF NOT EXISTS sal_return (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  return_no       VARCHAR(64)  NOT NULL,
  order_no        VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  grade           VARCHAR(8)   COMMENT 'A / B / C，关联 Seller Protection Fund 补偿',
  compensation_amount DECIMAL(18,4) DEFAULT 0,
  restock_batch_no VARCHAR(64) COMMENT '二次上架生成的新 FIFO 批次，插队尾',
  window_deadline DATETIME(3)  COMMENT '退货窗口截止（14/15 天口径冲突，由 sys_config 配）',
  appeal_deadline DATETIME(3)  COMMENT '申诉截止（7/14 天）',
  status          VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_return_no (return_no),
  KEY idx_order (order_no),
  KEY idx_appeal (appeal_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货与申诉时效';

CREATE TABLE IF NOT EXISTS fin_statement (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_no    VARCHAR(64)  NOT NULL COMMENT '平台账单唯一参考号',
  statement_type  VARCHAR(16)  NOT NULL COMMENT 'WEEKLY / STORAGE（月度仓储账单独立核对）',
  site_code       VARCHAR(16)  NOT NULL,
  period_start    DATE         NOT NULL,
  period_end      DATE         NOT NULL,
  generated_at    DATETIME(3)  NOT NULL COMMENT '周三生成',
  payout_date     DATE         COMMENT '次周周四',
  gross_amount    DECIMAL(18,4) DEFAULT 0,
  fee_amount      DECIMAL(18,4) DEFAULT 0,
  net_amount      DECIMAL(18,4) DEFAULT 0,
  currency        CHAR(3)      NOT NULL,
  dispute_deadline DATETIME(3) COMMENT '★ generated_at + 15 天，逾期不受理',
  reconcile_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/MATCHED/DISPUTED/CLOSED',
  source_channel  VARCHAR(24)  NOT NULL DEFAULT 'EXPORT',
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_statement_no (statement_no),
  KEY idx_site_period (site_code, period_start),
  KEY idx_dispute (dispute_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周结算单 + 月度仓储账单';

CREATE TABLE IF NOT EXISTS fin_statement_line (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_id    BIGINT       NOT NULL,
  statement_no    VARCHAR(64)  NOT NULL,
  order_no        VARCHAR(64)  COMMENT '可关联到订单时填',
  sku_id          BIGINT,
  line_type       VARCHAR(32)  NOT NULL COMMENT 'SALE / REFERRAL_FEE / FBN_OUTBOUND / STORAGE / RETURN_FEE / AD_SPEND / COMPENSATION / OTHER',
  amount          DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL,
  matched         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否与本地订单/费用匹配',
  raw_json        JSON         COMMENT '账单原始行，字段不删减',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_statement (statement_id),
  KEY idx_order (order_no),
  KEY idx_type (line_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单逐行对账；AD_SPEND 保留（Q7 不做广告模块但账单会扣）';

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
  source          VARCHAR(128) COMMENT '费率来源（Seller Lab 费率表 / 官方政策）',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_lookup (fee_code, site_code, category_code, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ 版本化费用字典，费率年内会变，历史版本必须保留以支持重算';

CREATE TABLE IF NOT EXISTS fin_payout (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  payout_no       VARCHAR(64)  NOT NULL,
  statement_no    VARCHAR(64),
  site_code       VARCHAR(16)  NOT NULL,
  bank_txn_id     VARCHAR(128) COMMENT '平台 Bank Transaction ID',
  amount          DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL,
  fx_rate         DECIMAL(18,8) DEFAULT 1,
  paid_at         DATETIME(3),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_payout_no (payout_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回款流水';

CREATE TABLE IF NOT EXISTS fin_expense_pool (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_no      VARCHAR(64)  NOT NULL,
  fee_type        VARCHAR(32)  NOT NULL COMMENT 'FREIGHT / DUTY / CUSTOMS / PACKAGING / FX_LOSS / PAYMENT_FEE / AD_SPEND',
  site_code       VARCHAR(16)  NOT NULL,
  period          CHAR(7)      NOT NULL COMMENT 'YYYY-MM',
  asn_id          BIGINT       COMMENT '可按 ASN 批次归集',
  amount          DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL,
  allocation_policy VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PARTIAL / FULL，Q8 三档',
  included_in_partial TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'PARTIAL 模式下是否被勾选合并',
  remark          VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_expense_no (expense_no),
  KEY idx_period (period, site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ L2 期间/批次费用池，与 FIFO 成本物理分离';

CREATE TABLE IF NOT EXISTS fin_profit_snapshot (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  period          CHAR(7)      NOT NULL,
  sku_id          BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  revenue         DECIMAL(18,4) DEFAULT 0,
  platform_fee    DECIMAL(18,4) DEFAULT 0 COMMENT '佣金+FBN出库+仓储+退货费',
  cogs_fifo       DECIMAL(18,4) DEFAULT 0 COMMENT '★ 来自 inv_batch_consumption 汇总',
  contribution_margin DECIMAL(18,4) DEFAULT 0 COMMENT 'L1 = revenue - platform_fee - cogs_fifo',
  allocated_expense DECIMAL(18,4) DEFAULT 0 COMMENT '按 allocation_policy 从 L2 合并进来',
  net_profit      DECIMAL(18,4) DEFAULT 0 COMMENT 'L1 + L2 合并口径',
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  formula_version VARCHAR(32)  NOT NULL COMMENT '自定义公式版本，支持全量重算',
  recalculated_at DATETIME(3),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_profit (period, sku_id, site_code, formula_version),
  KEY idx_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ SKU 利润快照：L1 贡献毛利 + L1/L2 合并净利';

CREATE TABLE IF NOT EXISTS fx_rate (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  rate_date       DATE         NOT NULL,
  from_currency   CHAR(3)      NOT NULL,
  to_currency     CHAR(3)      NOT NULL,
  rate            DECIMAL(18,8) NOT NULL,
  source          VARCHAR(64),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fx (rate_date, from_currency, to_currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日汇率：AED/SAR 不可直接相加，跨站点对比先归一';

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
  reviewed_by     BIGINT,
  reviewed_at     DATETIME(3),
  converted_doc_type VARCHAR(24) COMMENT 'PUR_ORDER / TRANSFER / ASN',
  converted_doc_no   VARCHAR(64),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sug_no (suggestion_no),
  KEY idx_sku_site (sku_id, site_code),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货建议单，人工确认后才落单';

-- -----------------------------------------------------------------------------
-- F. 集成 · 日志 · 权限 · AI 外挂
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
  next_run_at     DATETIME(3),
  last_error_code VARCHAR(64),
  last_error_msg  VARCHAR(1024),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_no (task_no),
  KEY idx_status_next (status, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务：429 指数退避、失败补偿';

CREATE TABLE IF NOT EXISTS int_webhook_event (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(128) NOT NULL COMMENT '★ 幂等键，平台事件 ID',
  event_type      VARCHAR(128) NOT NULL,
  site_code       VARCHAR(16),
  payload_raw     JSON         NOT NULL COMMENT '原始报文，字段不删减',
  received_at     DATETIME(3)  NOT NULL,
  processed_at    DATETIME(3),
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

CREATE TABLE IF NOT EXISTS int_export_job (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  export_code     VARCHAR(128) COMMENT 'CreateExport 返回的 export_code',
  export_category_code VARCHAR(128) NOT NULL COMMENT 'R4：需实调 GetExportCategoryList 确认可用值',
  params          JSON,
  site_code       VARCHAR(16),
  status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/READY/FAILED',
  file_url        VARCHAR(512),
  imported_count  INT          DEFAULT 0,
  raw_payload     JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_export_code (export_code),
  KEY idx_category (export_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reports(impex) 异步导出任务';

CREATE TABLE IF NOT EXISTS sys_user (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  username        VARCHAR(64)  NOT NULL,
  display_name    VARCHAR(128),
  password_hash   VARCHAR(256) NOT NULL,
  site_scope      VARCHAR(256) COMMENT '可访问的 site_code 列表，逗号分隔',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='10 人以内，简单 RBAC';

CREATE TABLE IF NOT EXISTS sys_role (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code       VARCHAR(64)  NOT NULL COMMENT 'ADMIN / OPERATOR / PURCHASE / WAREHOUSE / FINANCE / SERVICE',
  role_name       VARCHAR(128),
  permissions     JSON,
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_user_role (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL,
  role_id         BIGINT       NOT NULL,
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS sys_audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  log_no          VARCHAR(64)  NOT NULL COMMENT '★ 日志编号',
  user_id         BIGINT,
  action          VARCHAR(64)  NOT NULL,
  entity_type     VARCHAR(64)  NOT NULL,
  entity_id       VARCHAR(64),
  before_json     JSON,
  after_json      JSON,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_log_no (log_no),
  KEY idx_entity (entity_type, entity_id),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计：Q11 无审批流，靠状态机 + 审计日志';

CREATE TABLE IF NOT EXISTS ai_suggestion (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  suggestion_no   VARCHAR(64)  NOT NULL,
  scene           VARCHAR(64)  NOT NULL COMMENT 'STATEMENT_RECON / EN_AR_COPY / REPLENISH / PRICING',
  source_agent    VARCHAR(64)  NOT NULL COMMENT 'WorkBuddy / Codex / Claude Code / 官方 MCP',
  site_code       VARCHAR(16),
  payload_json    JSON         NOT NULL COMMENT 'AI 输出（建议内容，不是指令）',
  confidence      DECIMAL(6,4),
  status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT '★ PENDING/APPROVED/REJECTED，AI 不得直接改业务表',
  reviewed_by     BIGINT,
  reviewed_at     DATETIME(3),
  converted_doc_type VARCHAR(24),
  converted_doc_no   VARCHAR(64),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ai_sug_no (suggestion_no),
  KEY idx_scene_status (scene, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='★ AI 外挂唯一可写表：建议单，人工确认后核心系统落单';

-- -----------------------------------------------------------------------------
-- AI 外挂只读视图（配合官方 MCP Read 权限，物理隔离写操作）
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

CREATE OR REPLACE VIEW v_ai_profit AS
SELECT period, sku_id, site_code, revenue, platform_fee, cogs_fifo,
       contribution_margin, allocated_expense, net_profit, formula_version
FROM fin_profit_snapshot;

CREATE OR REPLACE VIEW v_ai_statement_open AS
SELECT statement_no, statement_type, site_code, period_start, period_end,
       generated_at, net_amount, currency, dispute_deadline, reconcile_status
FROM fin_statement
WHERE reconcile_status <> 'CLOSED';

CREATE OR REPLACE VIEW v_ai_replenish AS
SELECT suggestion_no, sku_id, site_code, days_of_cover, qty_available,
       qty_in_transit, suggested_qty, status, source_agent
FROM rp_replenish_suggestion;

-- =============================================================================
-- 初始化参数（v0.1 的 C-01~C-07 冲突项，全部参数化，不写死）
-- =============================================================================

INSERT INTO sys_config (config_key, config_value, scope_key, effective_from, remark) VALUES
  ('cost_method',            'FIFO',      'GLOBAL', '2026-11-12 00:00:00', 'Q5：FIFO 批次成本'),
  ('vat_mode',               'IGNORE',    'GLOBAL', '2026-11-12 00:00:00', 'Q6：不做 VAT；见 R7 毛利虚高风险'),
  ('allocation_policy',      'NONE',      'GLOBAL', '2026-11-12 00:00:00', 'Q8：默认不合并 L2，可切 PARTIAL / FULL'),
  ('return_window_days',     '15',        'GLOBAL', '2026-11-12 00:00:00', 'C-03：14/15 天冲突，取宽口径'),
  ('appeal_window_days',     '14',        'GLOBAL', '2026-11-12 00:00:00', 'C-03：Directship 7 天需按场景覆盖'),
  ('statement_dispute_days', '15',        'GLOBAL', '2026-11-12 00:00:00', 'F-10：逾期争议不受理'),
  ('payout_threshold',       '1000',      'GLOBAL', '2026-11-12 00:00:00', 'C-02：100/1000 冲突，按站点可覆盖'),
  ('return_restock_cost',    'ORIGINAL',  'GLOBAL', '2026-11-12 00:00:00', '退货二次上架批次成本口径'),
  ('stock_update_mode',      'ABSOLUTE',  'GLOBAL', '2026-11-12 00:00:00', 'N-11：Stock API 是绝对值 set，不是增量'),
  ('api_max_destinations',   '5',         'GLOBAL', '2026-11-12 00:00:00', 'N-13：每项目最多 5 个 webhook destination')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), remark = VALUES(remark);

-- =============================================================================
-- END OF SCHEMA v0.3
-- =============================================================================
