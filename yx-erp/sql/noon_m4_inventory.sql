-- M4 库存与 FIFO 第一轮：增量迁移（执行者：WorkBuddy，2026-11-22）
-- 设计源：docs/noon-erp-schema-v0.5.sql 第 326–469 行（inv_batch / inv_batch_consumption /
--         inv_stock_ledger / inv_stock_move / inv_inbound / inv_inbound_item）；契约
--         docs/verification/m4-inventory/contract.md 第 1 节。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，重复执行只跳过已存在表，不修改既有结构。
-- 前置：库为 MySQL 8.0+ / utf8mb4；md_warehouse / md_sku（M2）、pur_order / noon_m3_receipt（M3）已初始化。
-- 范围：仅第一轮入库闭环的 5 张 schema 表 + 2 张 M4 幂等/审计表；
--       不含调拨/头程/ASN/盘点（第二轮）、不含业务种子、不含菜单（noon_m4_menu.sql 另文）。
-- 注意：inv_batch_consumption（FIFO 消耗流水）沿用 schema v0.5 原表，由现有 FIFO 服务建表/使用，
--       本文件不重复建它；端到端入库→出库验证时会用到。

-- -----------------------------------------------------------------------------
-- 1. FIFO 成本批次（schema v0.5 第 326–354 行逐字段，不改语义）
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 2. 四态台账（schema v0.5 第 412–425 行）
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 3. 库存变动流水（schema v0.5 第 427–444 行）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_stock_move (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  move_no         VARCHAR(64)  NOT NULL,
  sku_id          BIGINT       NOT NULL,
  warehouse_id    BIGINT       NOT NULL,
  batch_id        BIGINT,
  change_type     VARCHAR(32)  NOT NULL COMMENT 'INBOUND/OUTBOUND/TRANSFER_IN/OUT/STOCKTAKE_GAIN/LOSS/RETURN_RESTOCK',
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

-- -----------------------------------------------------------------------------
-- 4. 入库单 / 入库行（schema v0.5 第 446–469 行）
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 5. M4 入库幂等请求表（契约 §1；语义对齐 M3 noon_m3_receipt_request 的 v2.1 口径）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m4_inbound_request (
  request_id          VARCHAR(64)  NOT NULL COMMENT '业务幂等键：调用方 UUID',
  pur_order_id        BIGINT       NOT NULL,
  content_fingerprint CHAR(64)     NOT NULL COMMENT 'SHA-256(purOrderId|warehouseId|按purOrderItemId升序的 purOrderItemId:qtyQualified)',
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json         JSON         COMMENT 'DONE 时缓存 InboundResult，重放原样返回',
  operator_id         BIGINT       COMMENT '若依 user_id；取不到为 NULL',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id),
  KEY idx_order (pur_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 入库幂等请求头（append-only，PENDING/DONE）';

-- -----------------------------------------------------------------------------
-- 6. M4 审计表（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m4_inventory_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_type    VARCHAR(32)  NOT NULL COMMENT 'INBOUND 等',
  biz_id      VARCHAR(64)  COMMENT '业务主键（inbound_id / request_id）',
  action      VARCHAR(32)  NOT NULL COMMENT 'CONFIRM / REPLAY_DENIED 等',
  detail      VARCHAR(1024) COMMENT '摘要，不写完整大字段',
  operator_id BIGINT       COMMENT '若依 user_id；取不到为 NULL',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 库存变更审计（append-only）';
