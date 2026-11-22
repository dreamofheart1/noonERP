-- -----------------------------------------------------------------------------
-- M4 第三轮第三批 · FBN 入仓 ASN 收货闭环
-- 业务主表 log_asn / log_asn_item / inv_batch / inv_stock_ledger / inv_stock_move /
-- inv_batch_consumption 均在 docs/noon-erp-schema-v0.5.sql 定义，本脚本不重复建。
-- 只新增动作级幂等表 noon_m4_asn_request（CREATE/SEAL/RECEIVE 隔离）。
-- 用法：在已执行 schema v0.5 + 前序 M2/M3/M4 迁移的隔离/升级库上执行。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS noon_m4_asn_request (
  request_id          VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
  action              VARCHAR(16)  NOT NULL COMMENT 'CREATE/SEAL/RECEIVE 动作级隔离',
  asn_id              BIGINT       NOT NULL COMMENT 'ASN 头 id；PENDING 期 0 占位',
  site_code           VARCHAR(16)  NOT NULL COMMENT '幂等行站点，resultOf 授权用',
  operator_id         BIGINT,
  content_fingerprint CHAR(64)     NOT NULL COMMENT 'SHA-256(动作|规范化业务串)',
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json         JSON         COMMENT 'DONE 缓存 AsnResult，重放原样返回',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id, action),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 ASN 收货闭环动作级幂等表';
