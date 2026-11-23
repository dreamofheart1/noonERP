-- -----------------------------------------------------------------------------
-- M6 v2 采购数量守恒：跨路径占用计数索引
-- 背景：直发 create 与本地入库 confirm 都按 pur_order_item_id 做跨路径占用求和，
--       且用 FOR UPDATE 锁定读绕过 RR 快照；无索引时可能锁住全表扫描行。
-- 约束：仅新增普通索引，不改数据/不改列；MySQL 8.0.35 下幂等可重复执行。
-- 用法：在已执行 schema v0.5 + noon_m6_direct_fbn.sql 的库上执行。
-- -----------------------------------------------------------------------------

-- 1) log_asn_item.pur_order_item_id：直发已发运量求和（AsnService.directShippedQty / availableItems）
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='log_asn_item' AND INDEX_NAME='idx_asn_item_poi');
SET @sql := IF(@idx=0,
  'CREATE INDEX idx_asn_item_poi ON log_asn_item (pur_order_item_id)',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) inv_inbound_item.pur_order_item_id：已入库合格量求和（InboundService.inboundAlready / AsnService.inboundReceivedQty）
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inv_inbound_item' AND INDEX_NAME='idx_inbound_item_poi');
SET @sql := IF(@idx=0,
  'CREATE INDEX idx_inbound_item_poi ON inv_inbound_item (pur_order_item_id)',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
