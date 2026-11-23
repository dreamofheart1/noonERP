-- -----------------------------------------------------------------------------
-- Noon 商品首图：md_sku 与 md_partner_sku 各新增 image_url
-- 背景：真图来自 Noon Content API 的 images[]，按站点映射的 Noon SKU 回读；
--       SKU 主数据存一份，映射表存该站点对应的一分，避免跨站点图片差异被覆盖。
-- 约束：仅新增列、不改数据；MySQL 8.0 幂等可重复执行。
-- -----------------------------------------------------------------------------

SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='md_sku' AND COLUMN_NAME='image_url');
SET @s1 := IF(@c1=0,
  'ALTER TABLE md_sku ADD COLUMN image_url VARCHAR(512) NULL COMMENT ''Noon 商品首图，由官方 Content API 回读'' AFTER brand',
  'SELECT 1');
PREPARE st1 FROM @s1; EXECUTE st1; DEALLOCATE PREPARE st1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='md_partner_sku' AND COLUMN_NAME='image_url');
SET @s2 := IF(@c2=0,
  'ALTER TABLE md_partner_sku ADD COLUMN image_url VARCHAR(512) NULL COMMENT ''该站点映射对应的 Noon 商品首图'' AFTER noon_sku',
  'SELECT 1');
PREPARE st2 FROM @s2; EXECUTE st2; DEALLOCATE PREPARE st2;
