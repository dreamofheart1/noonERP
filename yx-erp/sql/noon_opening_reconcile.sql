-- -----------------------------------------------------------------------------
-- M17 期初库存 / 销售送达扣减 / 退货证据 / 差异对账（隔离验证用，不自动执行建账）
-- 所有写入类动作均由"建议→用户确认"驱动；未确认只留建议与证据，不改 ERP 库存账。
-- -----------------------------------------------------------------------------

-- 期初建议：从 Noon 快照生成的建议行；confirmed 后才建 inv_batch/ledger
CREATE TABLE IF NOT EXISTS noon_opening_proposal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  proposal_no VARCHAR(64) NOT NULL COMMENT '建议批次号',
  site_code VARCHAR(8) NOT NULL,
  partner_sku VARCHAR(128),
  sku_id BIGINT,
  warehouse_id BIGINT COMMENT '可比 FBN 仓（来自 md_warehouse）',
  qty_saleable INT NOT NULL DEFAULT 0,
  qty_non_saleable INT NOT NULL DEFAULT 0,
  snapshot_at DATETIME(3) NOT NULL COMMENT 'Noon 快照时间',
  overlap_note VARCHAR(512) COMMENT '与已有 ERP 库存/测试数据/已销售/在途的重叠或待核说明',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
  cost_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PROVIDED；成本未知不得建零成本批次',
  unit_cost DECIMAL(18,4),
  confirmed_by VARCHAR(64),
  confirmed_at DATETIME(3),
  source VARCHAR(32) NOT NULL DEFAULT 'NOON_SELLERLAB',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_opening_prop (proposal_no, site_code, partner_sku, warehouse_id, snapshot_at),
  KEY idx_opening_status (status, site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期初库存建议（确认前不建账）';

-- 期初建账审计：每次确认的批次与证据，append-only
CREATE TABLE IF NOT EXISTS noon_opening_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  proposal_no VARCHAR(64) NOT NULL,
  site_code VARCHAR(8) NOT NULL,
  action VARCHAR(24) NOT NULL COMMENT 'CONFIRM/REJECT',
  operator VARCHAR(64),
  detail_json JSON COMMENT '建议与建账结果摘要（不存凭据）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_opening_audit (proposal_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期初库存确认审计';

-- 销售明细转正式订单的映射：item_nr 快照 -> sal_order/sal_order_item，幂等
CREATE TABLE IF NOT EXISTS noon_sales_link (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code VARCHAR(8) NOT NULL,
  item_nr VARCHAR(128) NOT NULL COMMENT '平台明细标识',
  sal_order_id BIGINT COMMENT 'ERP正式订单（未关联为NULL，不得用0冒充）',
  sal_order_item_id BIGINT COMMENT 'ERP正式订单行（未关联为NULL）',
  status VARCHAR(32) NOT NULL COMMENT 'latest platform status',
  delivered_at DATETIME(3),
  synced_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_sales_link (site_code, item_nr),
  KEY idx_sales_link_order (sal_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon销售明细与ERP正式订单的可追溯映射';

-- 退货证据待核：缺平台重新入仓且可售证据的记录，不自动加库存
CREATE TABLE IF NOT EXISTS noon_return_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code VARCHAR(8) NOT NULL,
  item_nr VARCHAR(128),
  source_return_no VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/VERIFIED/INSUFFICIENT',
  evidence_note VARCHAR(512),
  synced_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_return_evidence (site_code, source_return_no),
  KEY idx_return_item (site_code, item_nr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货恢复可售前的证据闸门（PENDING 不加库存）';

-- 对账快照：每次简单对账留痕，可追溯
CREATE TABLE IF NOT EXISTS noon_reconcile_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_code VARCHAR(8) NOT NULL,
  partner_sku VARCHAR(128),
  sku_id BIGINT,
  noon_saleable INT,
  noon_non_saleable INT,
  erp_saleable INT,
  erp_non_saleable INT,
  diff_saleable INT,
  compare_status VARCHAR(24) NOT NULL COMMENT 'COMPARABLE/INCOMPARABLE/UNMATCHED',
  noon_snapshot_at DATETIME(3),
  erp_snapshot_at DATETIME(3) NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'NOON_SELLERLAB',
  UNIQUE KEY uk_reconcile (site_code, partner_sku, noon_snapshot_at, erp_snapshot_at),
  KEY idx_reconcile_site (site_code, erp_snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Noon与ERP库存简单对账快照';
