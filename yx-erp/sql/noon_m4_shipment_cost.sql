-- -----------------------------------------------------------------------------
-- M4 第三轮第二批 · 头程费用登记（log_shipment_cost 主表复用 schema v0.5）
-- 本脚本只新增动作级幂等表；费用主表 log_shipment_cost 已在 docs/noon-erp-schema-v0.5.sql 定义。
-- 用法：在已执行 schema v0.5 + 前序 M2/M3/M4 迁移的隔离/升级库上执行本脚本。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS noon_m4_shipment_cost_request (
  request_id         VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
  cost_id            BIGINT       NOT NULL COMMENT 'CREATE 成功后回填的真实费用行 id；PENDING 期用 0 占位',
  site_code          VARCHAR(16)  NOT NULL COMMENT '幂等行站点，用于 resultOf 授权',
  operator_id        BIGINT,
  content_fingerprint CHAR(64)    NOT NULL COMMENT 'SHA-256(CREATE|asn|site|period|feeType|amount|currency|fxRate|allocationFlag|remark 规范化串)',
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json        JSON         COMMENT 'DONE 时缓存 ShipmentCostResult，重放原样返回',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 头程费用登记动作级幂等表';
