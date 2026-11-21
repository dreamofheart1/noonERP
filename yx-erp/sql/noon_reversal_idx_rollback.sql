-- NOON-20261121-REVERSAL：安全回滚 idx_sal_return 二级索引（幂等，与 noon_reversal_idx_migration.sql 配套）
--
-- 安全契约（六次复核 · Codex 意见）：回滚脚本只删除【本工作包创建且完整定义匹配】的索引，
-- 绝不按名称盲删——若同名索引是他人创建或定义被改动，明确报错并中止，避免误删既有索引。
--   * 不存在                              -> DO 0，幂等跳过；
--   * ownership=本包 且 完整定义匹配       -> DROP INDEX（安全）；
--   * ownership≠本包                      -> 明确报错：非本包索引，拒绝删除；
--   * ownership=本包 但定义被改            -> 明确报错：本包索引定义被改，需人工核对。
--
-- ownership 标记：INDEX_COMMENT='owner:NOON-20261121-REVERSAL:v1'（由迁移脚本创建时写入）。
-- 完整定义指纹：列序/唯一性/前缀/排序/类型/可见性/表达式，与迁移脚本 @noon_fp_expected 一致。
-- 回滚后重新执行 noon_reversal_idx_migration.sql 可恢复索引（真库 IT 已验证恢复/重跑行为）。
-- 适用 MySQL 8.0+（DROP INDEX 支持 ALGORITHM=INPLACE, LOCK=NONE）。
--
-- 注意：本脚本只删除二级索引，不改动任何行数据；执行前仍建议按发布规范完成数据备份
--   （mysqldump --single-transaction），SHOW CREATE TABLE 仅为结构备份。

SET @noon_db = DATABASE();
SET @noon_owner = 'owner:NOON-20261121-REVERSAL:v1';

SET @noon_idx_rows = (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = @noon_db
                        AND TABLE_NAME = 'inv_batch_consumption'
                        AND INDEX_NAME = 'idx_sal_return');

SET @noon_fp = (SELECT GROUP_CONCAT(
                    CONCAT(COLUMN_NAME, '|', NON_UNIQUE, '|', IFNULL(SUB_PART, 'FULL'), '|',
                           IFNULL(COLLATION, 'NONE'), '|', INDEX_TYPE, '|', IS_VISIBLE, '|',
                           IFNULL(EXPRESSION, 'NONE'))
                    ORDER BY SEQ_IN_INDEX SEPARATOR '~')
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = @noon_db
                  AND TABLE_NAME = 'inv_batch_consumption'
                  AND INDEX_NAME = 'idx_sal_return');
SET @noon_fp_expected = 'sal_return_id|1|FULL|A|BTREE|YES|NONE';
SET @noon_owner_actual = (SELECT INDEX_COMMENT FROM information_schema.STATISTICS
                          WHERE TABLE_SCHEMA = @noon_db
                            AND TABLE_NAME = 'inv_batch_consumption'
                            AND INDEX_NAME = 'idx_sal_return'
                            AND SEQ_IN_INDEX = 1);

-- 决策：不存在 -> DO 0；非本包 -> 抛错拒绝；定义被改 -> 抛错拒绝；否则安全 DROP。
-- 报错用不存在的 database 回显原因码 "Unknown database 'noon_abort_<原因>'"，见真库 IT 断言。
SET @noon_ddl = CASE
    WHEN @noon_idx_rows = 0 THEN 'DO 0'
    WHEN @noon_owner_actual IS NULL OR @noon_owner_actual <> @noon_owner THEN
        'SELECT * FROM `NOON_ABORT_NOT_OWNED_REFUSE_DROP`.`t`'
    WHEN @noon_idx_rows <> 1 OR @noon_fp IS NULL OR @noon_fp <> @noon_fp_expected THEN
        'SELECT * FROM `NOON_ABORT_IDX_DEFINITION_MISMATCH_REFUSE_DROP`.`t`'
    ELSE 'ALTER TABLE inv_batch_consumption DROP INDEX idx_sal_return, ALGORITHM=INPLACE, LOCK=NONE'
END;
PREPARE noon_idx_rollback FROM @noon_ddl;
EXECUTE noon_idx_rollback;
DEALLOCATE PREPARE noon_idx_rollback;
