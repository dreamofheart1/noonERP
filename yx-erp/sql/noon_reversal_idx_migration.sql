-- NOON-20261121-REVERSAL：为 inv_batch_consumption.sal_return_id 补二级索引（幂等迁移，不改主 schema v0.5）
--
-- 背景：ReversalService.sumReversedOnReturn 使用当前读
--   SELECT id,qty FROM inv_batch_consumption WHERE sal_return_id=? AND sign=-1 FOR UPDATE。
-- 主 schema v0.5 仅有 idx_reversal(reversal_of_id)，无 sal_return_id 索引；无索引时该当前读
-- 全表扫描并扩大锁范围，跨原行并发可致死锁（三次复核可控同步测试已实证）。
--
-- 幂等 + 列定义校验（五次复核意见 2）：
--   先查 information_schema.STATISTICS 中同名索引的列序列（GROUP_CONCAT ORDER BY SEQ_IN_INDEX）：
--     * 索引不存在           -> ADD INDEX 创建；
--     * 索引存在且列恰为 sal_return_id -> DO 0，幂等跳过；
--     * 索引存在但列不匹配   -> 仍执行 ADD INDEX，MySQL 报 Duplicate key name 'idx_sal_return'
--       从而【明确报错】，绝不静默跳过错列索引（避免当前读继续全表扫描）。
--   可重复执行；适用 MySQL 8.0+（ADD INDEX 支持 ALGORITHM=INPLACE, LOCK=NONE 在线加索引）。
--
-- 备份与部署顺序：
--   1) 低峰期执行；先确认无长事务/大批量未提交写入（information_schema.innodb_trx）。
--   2) 备份【结构】：SHOW CREATE TABLE inv_batch_consumption（仅 DDL，不含数据）。
--      如需【数据】备份，另用 mysqldump --single-transaction 导出该表；本脚本只加二级索引、不改动行数据，
--      但按发布规范仍建议在变更前完成数据备份。（五次复核：勿把 SHOW CREATE TABLE 当作数据备份。）
--   3) 可重复执行本脚本；首次执行生效，重复执行无副作用；同名错列会明确报错并中止。
--   4) 验证执行计划：EXPLAIN SELECT id,qty FROM inv_batch_consumption
--        WHERE sal_return_id=? AND sign=-1 FOR UPDATE，确认 key=idx_sal_return（非 ALL）。
--   5) 灰度观察一段时间锁等待/死锁指标（performance_schema.data_lock_waits / SHOW ENGINE INNODB STATUS）。
--
-- 回滚（幂等，按需单独执行 noon_reversal_idx_rollback.sql，或手工执行以下语句；每条均带结束分号）：
--   SET @has=(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
--     AND TABLE_NAME='inv_batch_consumption' AND INDEX_NAME='idx_sal_return');
--   SET @ddl=IF(@has>0, 'ALTER TABLE inv_batch_consumption DROP INDEX idx_sal_return', 'DO 0');
--   PREPARE noon_idx_rollback FROM @ddl;
--   EXECUTE noon_idx_rollback;
--   DEALLOCATE PREPARE noon_idx_rollback;
--   回滚后本迁移脚本可再次执行恢复索引（已由真库 IT 验证）。
--
-- 注意：索引不能保证绝无死锁；service 仍保留锁异常事务整体回滚，调用方需有界同键重试。

SET @noon_db = DATABASE();
-- 同名索引的列序列；NULL 表示索引不存在，单列 'sal_return_id' 表示列正确，其它值表示同名但列不匹配。
SET @noon_idx_cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
                      FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = @noon_db
                        AND TABLE_NAME = 'inv_batch_consumption'
                        AND INDEX_NAME = 'idx_sal_return');
-- 列正确则幂等跳过；不存在或错列都走 ADD INDEX：不存在→创建成功，错列→Duplicate key name 明确报错。
SET @noon_idx_ddl = IF(@noon_idx_cols = 'sal_return_id',
    'DO 0',
    'ALTER TABLE inv_batch_consumption ADD INDEX idx_sal_return (sal_return_id), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE noon_idx_stmt FROM @noon_idx_ddl;
EXECUTE noon_idx_stmt;
DEALLOCATE PREPARE noon_idx_stmt;
