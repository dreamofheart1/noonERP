-- =============================================================================
-- M4 库存第三轮第一批 · 盘点闭环迁移（幂等表）
-- 业务表 inv_stocktake / inv_stocktake_item 已在 docs/noon-erp-schema-v0.5.sql 定义，
-- 本脚本只追加盘点专属幂等表；菜单见 noon_m4_stocktake_menu.sql。
-- 重复执行不报错、不覆盖已有数据。
-- =============================================================================

CREATE TABLE IF NOT EXISTS noon_m4_stocktake_request (
  request_id         VARCHAR(64)  NOT NULL,
  stocktake_id       BIGINT       NOT NULL DEFAULT 0 COMMENT 'APPLY 成功后回填真实盘点单 id；CREATE/PENDING 用 0 占位',
  warehouse_id       BIGINT       NOT NULL,
  operator_id        BIGINT,
  action             VARCHAR(16)  NOT NULL COMMENT 'CREATE / APPLY，进指纹隔离两动作',
  content_fingerprint CHAR(64)    NOT NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE',
  result_json        JSON         COMMENT 'DONE 时缓存 StocktakeResult，重放原样返回',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 盘点幂等表（动作级，CREATE/APPLY 隔离）';
