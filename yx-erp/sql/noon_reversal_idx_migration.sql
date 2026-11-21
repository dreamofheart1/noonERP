-- NOON-20261121-REVERSAL：为 inv_batch_consumption.sal_return_id 补二级索引（幂等迁移，不改主 schema v0.5）
--
-- 背景：ReversalService.sumReversedOnReturn 使用当前读
--   SELECT id,qty FROM inv_batch_consumption WHERE sal_return_id=? AND sign=-1 FOR UPDATE。
-- 主 schema v0.5 仅有 idx_reversal(reversal_of_id)，无 sal_return_id 索引；无索引时该当前读
-- 全表扫描并扩大锁范围，跨原行并发可致死锁（三次复核可控同步测试已实证）。
--
-- 幂等：先查 information_schema.STATISTICS，索引存在则跳过（DO 0 占位），可重复执行。
-- 适用：MySQL 8.0+（ADD INDEX 支持 ALGORITHM=INPLACE, LOCK=NONE 在线加索引）。
--
-- 部署顺序：
--   1) 低峰期执行；先确认无长事务/大批量未提交写入（information_schema.innodb_trx）。
--   2) 备份表结构与数据：SHOW CREATE TABLE inv_batch_consumption。
--   3) 可重复执行本脚本；首次执行生效，重复执行无副作用。
--   4) 验证执行计划：EXPLAIN SELECT id,qty FROM inv_batch_consumption
--        WHERE sal_return_id=? AND sign=-1 FOR UPDATE，确认 key=idx_sal_return（非 ALL）。
--   5) 灰度观察一段时间锁等待/死锁指标（performance_schema.data_lock_waits / SHOW ENGINE INNODB STATUS）。
--
-- 回滚（同样幂等，按需单独执行）：
--   SET @has=(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
--     AND TABLE_NAME='inv_batch_consumption' AND INDEX_NAME='idx_sal_return')
--   SET @ddl=IF(@has>0, 'ALTER TABLE inv_batch_consumption DROP INDEX idx_sal_return', 'DO 0')
--   PREPARE noon_idx_rollback FROM @ddl
--   EXECUTE noon_idx_rollback
--   DEALLOCATE PREPARE noon_idx_rollback
--
-- 注意：索引不能保证绝无死锁；service 仍保留锁异常事务整体回滚，调用方需有界同键重试。

SET @noon_db = DATABASE();
SET @noon_has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = @noon_db
                       AND TABLE_NAME = 'inv_batch_consumption'
                       AND INDEX_NAME = 'idx_sal_return');
SET @noon_idx_ddl = IF(@noon_has_idx = 0,
    'ALTER TABLE inv_batch_consumption ADD INDEX idx_sal_return (sal_return_id), ALGORITHM=INPLACE, LOCK=NONE',
    'DO 0');
PREPARE noon_idx_stmt FROM @noon_idx_ddl;
EXECUTE noon_idx_stmt;
DEALLOCATE PREPARE noon_idx_stmt;
