-- -----------------------------------------------------------------------------
-- Noon SKU 同步：新增 md_partner_sku.noon_sku（平台 SKU 回读列）
-- 背景：Offer API 的 partner_sku 与 Noon 平台 sku 是两个不同标识；
--       FBN 台账导出可同时取到两者，需要分别留存以便订单/利润归因。
-- 约束：仅新增一列，不改数据；MySQL 8.0 幂等可重复执行。
-- -----------------------------------------------------------------------------

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='md_partner_sku' AND COLUMN_NAME='noon_sku');
SET @sql := IF(@col=0,
  'ALTER TABLE md_partner_sku ADD COLUMN noon_sku VARCHAR(128) NULL COMMENT ''Noon 平台 SKU，由商品同步回读'' AFTER barcode',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
