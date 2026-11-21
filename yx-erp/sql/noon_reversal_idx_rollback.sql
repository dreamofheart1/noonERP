-- NOON-20261121-REVERSAL：回滚 idx_sal_return 二级索引（幂等，与 noon_reversal_idx_migration.sql 配套）
--
-- 幂等：先查 information_schema.STATISTICS，索引存在才 DROP，否则 DO 0；可重复执行。
-- 回滚后重新执行 noon_reversal_idx_migration.sql 可恢复索引（真库 IT 已验证恢复/重跑行为）。
-- 适用 MySQL 8.0+（DROP INDEX 支持 ALGORITHM=INPLACE, LOCK=NONE）。
--
-- 注意：本脚本只删除二级索引，不改动任何行数据；执行前仍建议按发布规范完成数据备份
--   （mysqldump --single-transaction），SHOW CREATE TABLE 仅为结构备份。

SET @noon_rollback_db = DATABASE();
SET @noon_rollback_has = (SELECT COUNT(*) FROM information_schema.STATISTICS
                          WHERE TABLE_SCHEMA = @noon_rollback_db
                            AND TABLE_NAME = 'inv_batch_consumption'
                            AND INDEX_NAME = 'idx_sal_return');
SET @noon_rollback_ddl = IF(@noon_rollback_has > 0,
    'ALTER TABLE inv_batch_consumption DROP INDEX idx_sal_return, ALGORITHM=INPLACE, LOCK=NONE',
    'DO 0');
PREPARE noon_idx_rollback FROM @noon_rollback_ddl;
EXECUTE noon_idx_rollback;
DEALLOCATE PREPARE noon_idx_rollback;
