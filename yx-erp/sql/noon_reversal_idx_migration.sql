-- NOON-20261121-REVERSAL：为 inv_batch_consumption.sal_return_id 补二级索引（幂等迁移，不改主 schema v0.5）
--
-- 背景：ReversalService.sumReversedOnReturn 使用当前读
--   SELECT id,qty FROM inv_batch_consumption WHERE sal_return_id=? AND sign=-1 FOR UPDATE。
-- 主 schema v0.5 仅有 idx_reversal(reversal_of_id)，无 sal_return_id 索引；无索引时该当前读
-- 全表扫描并扩大锁范围，跨原行并发可致死锁（三次复核可控同步测试已实证）。
--
-- ownership 与完整定义校验（六次复核 · Codex 意见）：
--   本工作包创建的 idx_sal_return 带 INDEX_COMMENT='owner:NOON-20261121-REVERSAL:v1' 作为归属标记。
--   同名索引的完整属性（列序/唯一性/可见性/前缀/排序/类型/表达式）也逐项核对，不只看列名：
--     * 不存在                       -> 创建（带 ownership COMMENT）；
--     * 存在且 ownership=本包 且 完整定义匹配 -> DO 0，幂等跳过；
--     * 存在但 ownership≠本包        -> 明确报错：非本工作包索引，拒绝接管/拒绝删除（防误删既有索引）；
--     * 存在、ownership=本包 但定义被改 -> 明确报错：本包索引定义被改动，需人工核对后再处理。
--   绝不静默跳过、绝不安全检查就 DROP。可重复执行；MySQL 8.0+（ADD INDEX 支持 ALGORITHM=INPLACE, LOCK=NONE）。
--
-- 备份与部署顺序：
--   1) 低峰期执行；先确认无长事务/大批量未提交写入（information_schema.innodb_trx）。
--   2) 备份【结构】：SHOW CREATE TABLE inv_batch_consumption（仅 DDL，不含数据）。
--      如需【数据】备份，另用 mysqldump --single-transaction 导出该表；本脚本只加二级索引、不改动行数据，
--      但按发布规范仍建议在变更前完成数据备份（勿把 SHOW CREATE TABLE 当作数据备份）。
--   3) 可重复执行本脚本；首次执行生效，重复执行无副作用；同名非本包索引或错定义会明确报错并中止。
--   4) 验证执行计划：EXPLAIN SELECT id,qty FROM inv_batch_consumption
--        WHERE sal_return_id=? AND sign=-1 FOR UPDATE，确认 key=idx_sal_return（非 ALL）。
--   5) 灰度观察一段时间锁等待/死锁指标（performance_schema.data_lock_waits / SHOW ENGINE INNODB STATUS）。
--   6) 回滚只删【本包 ownership 且定义匹配】的索引：执行 noon_reversal_idx_rollback.sql。
--
-- 注意：索引不能保证绝无死锁；service 仍保留锁异常事务整体回滚，调用方需有界同键重试。
-- 本脚本未部署到任何生产/共享库；是否执行由运维在维护窗口按上述顺序决定。

SET @noon_db = DATABASE();
SET @noon_owner = 'owner:NOON-20261121-REVERSAL:v1';

-- 同名索引行数（一个索引可能多列，用 COUNT(*) 计列数）。
SET @noon_idx_rows = (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = @noon_db
                        AND TABLE_NAME = 'inv_batch_consumption'
                        AND INDEX_NAME = 'idx_sal_return');

-- 完整定义指纹：列序(NON_UNIQUE 放每组)、SUB_PART(前缀)、COLLATION(排序)、INDEX_TYPE、IS_VISIBLE、EXPRESSION，
-- 以及 INDEX_COMMENT 归属标记（取首列 SEQ_IN_INDEX=1 的注释）。
SET @noon_fp = (SELECT GROUP_CONCAT(
                    CONCAT(COLUMN_NAME, '|', NON_UNIQUE, '|', IFNULL(SUB_PART, 'FULL'), '|',
                           IFNULL(COLLATION, 'NONE'), '|', INDEX_TYPE, '|', IS_VISIBLE, '|',
                           IFNULL(EXPRESSION, 'NONE'))
                    ORDER BY SEQ_IN_INDEX SEPARATOR '~')
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = @noon_db
                  AND TABLE_NAME = 'inv_batch_consumption'
                  AND INDEX_NAME = 'idx_sal_return');
-- 期望：单列 sal_return_id、非唯一(1)、无前缀(FULL)、升序(A)、BTREE、可见(YES)、无表达式(NONE)、列数 1。
SET @noon_fp_expected = 'sal_return_id|1|FULL|A|BTREE|YES|NONE';
SET @noon_owner_actual = (SELECT INDEX_COMMENT FROM information_schema.STATISTICS
                          WHERE TABLE_SCHEMA = @noon_db
                            AND TABLE_NAME = 'inv_batch_consumption'
                            AND INDEX_NAME = 'idx_sal_return'
                            AND SEQ_IN_INDEX = 1);

-- 决策：
--   行数=0 -> 创建（带 ownership COMMENT）；
--   ownership 不符 -> 执行伪装 DDL 抛错（非本包索引，拒绝接管）；
--   定义不符 -> 执行伪装 DDL 抛错（本包索引定义被改）；
--   否则 -> DO 0 幂等。
-- 用 PREPARE 执行一条必然失败的语句来"明确报错"：SELECT 一个不存在的 database，
-- MySQL 会回显 "Unknown database 'noon_abort_<原因>'"，原因码可从异常消息读取（见真库 IT 断言）。
SET @noon_idx_ddl = CASE
    WHEN @noon_idx_rows = 0 THEN
        CONCAT('ALTER TABLE inv_batch_consumption ADD INDEX idx_sal_return (sal_return_id) ',
               'COMMENT ''', @noon_owner, ''', ALGORITHM=INPLACE, LOCK=NONE')
    WHEN @noon_owner_actual IS NULL OR @noon_owner_actual <> @noon_owner THEN
        'SELECT * FROM `NOON_ABORT_NOT_OWNED_BY_THIS_PACKAGE`.`t`'
    WHEN @noon_idx_rows <> 1 OR @noon_fp IS NULL OR @noon_fp <> @noon_fp_expected THEN
        'SELECT * FROM `NOON_ABORT_IDX_DEFINITION_MISMATCH`.`t`'
    ELSE 'DO 0'
END;
PREPARE noon_idx_stmt FROM @noon_idx_ddl;
EXECUTE noon_idx_stmt;
DEALLOCATE PREPARE noon_idx_stmt;
