-- -----------------------------------------------------------------------------
-- M6 采购直发 FBN：ASN 增加来源模式与直发采购明细关联
-- 背景：直发场景货不经本地仓，无源仓 OPEN 批次；ASN 需能以采购收货成本建直发批次。
-- 约束：新增列均带默认值，旧数据/旧调用零影响（LOCAL_BATCH 为默认）；幂等可重复执行。
-- 用法：在已执行 schema v0.5 + noon_m4_asn 的库上执行。MySQL 8.0.35 验证。
-- -----------------------------------------------------------------------------

-- 1) log_asn.source_mode：本地批次 / 采购直发
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='log_asn' AND COLUMN_NAME='source_mode');
SET @sql := IF(@col=0,
  'ALTER TABLE log_asn ADD COLUMN source_mode VARCHAR(24) NOT NULL DEFAULT ''LOCAL_BATCH'' COMMENT ''LOCAL_BATCH/ DIRECT_PURCHASE'' AFTER source',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) log_asn_item.pur_order_item_id：直发来源采购明细（本地模式为 NULL）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='log_asn_item' AND COLUMN_NAME='pur_order_item_id');
SET @sql := IF(@col=0,
  'ALTER TABLE log_asn_item ADD COLUMN pur_order_item_id BIGINT COMMENT ''直发来源采购明细 id；本地批次模式为 NULL'' AFTER sku_id',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) inv_batch.source_type 注释扩展为 DIRECT_PURCHASE 留位（仅注释，不改枚举约束；列本身无 CHECK）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inv_batch' AND COLUMN_NAME='source_type');
SET @sql := IF(@col=1,
  'ALTER TABLE inv_batch MODIFY COLUMN source_type VARCHAR(24) NOT NULL DEFAULT ''PURCHASE'' COMMENT ''PURCHASE / RETURN_RESTOCK / ADJUSTMENT / TRANSFER_SPLIT / DIRECT_PURCHASE''',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
