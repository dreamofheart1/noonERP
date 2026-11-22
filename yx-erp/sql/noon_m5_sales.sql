-- -----------------------------------------------------------------------------
-- M5 订单与退货 · 编排闭环动作级幂等表
-- 业务主表 sal_order / sal_order_item / sal_return / inv_batch_consumption /
-- inv_batch / inv_stock_ledger / int_webhook_event 均在 docs/noon-erp-schema-v0.5.sql 定义，
-- 本脚本不重复建。只新增 M5 编排层动作级幂等表 noon_m5_sales_request。
-- 用法：在已执行 schema v0.5 + M1-M4 迁移的隔离/升级库上执行。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS noon_m5_sales_request (
  request_id          VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
  action              VARCHAR(24)  NOT NULL COMMENT 'ORDER_IMPORT/ORDER_DELIVER/RETURN_IMPORT/RETURN_REVERSE/RETURN_DISPOSE 动作级隔离',
  entity_id           BIGINT       NOT NULL DEFAULT 0 COMMENT 'PENDING 期 0 占位；DONE 回填 orderId/returnId/consumptionId 等',
  site_code           VARCHAR(16)  NOT NULL COMMENT '幂等行站点，result 授权用',
  operator_id         BIGINT,
  content_fingerprint CHAR(64)     NOT NULL COMMENT 'SHA-256(action|规范化业务串)',
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json         JSON         COMMENT 'DONE 缓存结果，重放原样返回',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id, action),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M5 订单退货编排动作级幂等表';
