-- M3 增量迁移：采购单供应商改为可选（幂等）。
-- 前置：pur_order 已存在。新装库请直接使用更新后的 noon_m3_purchasing.sql。
-- 仅在 supplier_id 当前为 NOT NULL 时执行 MODIFY；不触碰任何业务数据。
-- MySQL 8.0.35 验证；不连接生产，不输出凭据。

SET @col_nullable = (
  SELECT IS_NULLABLE
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'pur_order'
    AND COLUMN_NAME = 'supplier_id'
);

SET @alter_sql = IF(
  @col_nullable = 'NO',
  'ALTER TABLE pur_order MODIFY COLUMN supplier_id BIGINT NULL COMMENT ''可空：供应商非必填，非空须为 ACTIVE 供应商''',
  'SELECT 1'
);

PREPARE stmt FROM @alter_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
