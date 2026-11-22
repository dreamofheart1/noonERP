-- M3 供应商与采购：增量迁移（执行者：WorkBuddy，2026-11-22）
-- 设计源：docs/noon-erp-schema-v0.5.sql 第 138–163 行（md_supplier/md_sku_supplier）、
--         第 289–325 行（pur_order/pur_order_item）；契约 docs/verification/m3-purchasing/contract.md 第 1 节。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，重复执行只跳过已存在表，不修改既有结构。
-- 升级提示（v2.1）：若某库已执行过 v2 且 noon_m3_receipt_request 已存在，CREATE IF NOT EXISTS 不会补
--   content_fingerprint 列；对这类旧库请先执行文件末尾「附录 A」的幂等 ALTER 再启用 v2.1 后端。
--   全新库（隔离 IT/E2E 均为此类）按本文件建表即可，无需附录 A。
-- 前置：库为 MySQL 8.0+ / utf8mb4；md_sku / md_store / md_site / md_warehouse（M2）已初始化。
-- 范围：仅 M3 的 4 张 schema 表 + 2 张 M3 审计/收货表 + 2 张 v2 整改表（收货幂等请求头、报价组合锁，契约 §9）；
--       不含 M2 表、不含业务种子、不含菜单（noon_m3_menu.sql 另文）。

-- -----------------------------------------------------------------------------
-- 1. 供应商档案（schema v0.5 第 138–146 行逐字段）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS md_supplier (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  supplier_no     VARCHAR(64)  NOT NULL,
  supplier_name   VARCHAR(256) NOT NULL,
  contact_json    JSON,
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_supplier_no (supplier_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商';

-- -----------------------------------------------------------------------------
-- 2. SKU-供应商报价（schema v0.5 第 148–163 行逐字段；历史价按 effective_from 版本化）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS md_sku_supplier (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id          BIGINT       NOT NULL,
  supplier_id     BIGINT       NOT NULL,
  unit_price      DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  moq             INT          DEFAULT 0,
  lead_time_days  INT          DEFAULT 0 COMMENT '生产/交货周期，补货模型用',
  effective_from  DATE         NOT NULL,
  effective_to    DATE,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sku_sup_eff (sku_id, supplier_id, effective_from),
  KEY idx_sup (supplier_id),
  CONSTRAINT chk_sku_sup_qty CHECK (moq >= 0 AND lead_time_days >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU-供应商报价（历史价保留）';

-- -----------------------------------------------------------------------------
-- 3. 采购单（schema v0.5 第 292–309 行逐字段）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pur_order (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no        VARCHAR(64)  NOT NULL COMMENT '采购单业务编号',
  supplier_id     BIGINT       NOT NULL,
  site_code       VARCHAR(16)  NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY',
  total_amount    DECIMAL(18,4) DEFAULT 0,
  status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/CLOSED（Q11 无审批流）',
  source          VARCHAR(24)  DEFAULT 'MANUAL' COMMENT 'MANUAL / REPLENISH_SUGGESTION / AI_SUGGESTION',
  source_ref_no   VARCHAR(64)  COMMENT '来源建议单号',
  operator_id     BIGINT       COMMENT '若依 sys_user.user_id',
  config_snapshot_batch_no VARCHAR(64) COMMENT 'v0.5：本单参数可信快照批号，引用 noon_config_snapshot',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pur_no (order_no),
  KEY idx_supplier (supplier_id),
  KEY idx_status (status),
  KEY idx_site_status (site_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单';

-- -----------------------------------------------------------------------------
-- 4. 采购单行（schema v0.5 第 311–325 行逐字段）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pur_order_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  pur_order_id    BIGINT       NOT NULL,
  sku_id          BIGINT       NOT NULL,
  qty_ordered     INT          NOT NULL,
  qty_received    INT          NOT NULL DEFAULT 0,
  unit_price      DECIMAL(18,4) NOT NULL,
  currency        CHAR(3)      NOT NULL DEFAULT 'CNY' COMMENT 'CNY 本币采购；外币采购须显式赋值',
  fx_rate         DECIMAL(18,8) DEFAULT NULL COMMENT 'P1-1：默认 NULL 不再兜底 1；本币 CNY 由应用显式写 1，缺失报错',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_pur (pur_order_id),
  KEY idx_sku (sku_id),
  CONSTRAINT chk_pur_item_qty CHECK (qty_ordered >= 0 AND qty_received >= 0 AND qty_received <= qty_ordered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单行';

-- -----------------------------------------------------------------------------
-- 5. 部分收货明细（契约 v1 新增；每次收货一行，unit_cost_cny 只含采购价）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m3_receipt (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id        BIGINT       NOT NULL COMMENT 'pur_order.id',
  order_item_id   BIGINT       NOT NULL COMMENT 'pur_order_item.id',
  qty             INT          NOT NULL,
  unit_cost_cny   DECIMAL(18,4) NOT NULL COMMENT 'unit_price × fx_rate，只含采购价，不含头程/关税/包材',
  received_at     DATETIME(3)  NOT NULL COMMENT 'UTC+8 业务时刻，应用显式传',
  operator_id     BIGINT       COMMENT '若依 user_id；取不到为 NULL',
  KEY idx_receipt_order (order_id),
  KEY idx_receipt_item (order_item_id),
  CONSTRAINT chk_m3_receipt_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 部分收货明细（append-only）';

-- -----------------------------------------------------------------------------
-- 6. M3 变更审计（append-only，与业务写入同事务；结构与 M2 审计同构）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m3_audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_type   VARCHAR(32)  NOT NULL COMMENT 'SUPPLIER / QUOTATION / PUR_ORDER / RECEIPT',
  entity_id     BIGINT       NOT NULL,
  action        VARCHAR(16)  NOT NULL COMMENT 'INSERT / UPDATE / STATUS / RECEIVE',
  operator_id   BIGINT       COMMENT '若依 user_id；无法取到登录人时为 NULL',
  operator_name VARCHAR(64)  COMMENT '登录名；取不到时 UNKNOWN',
  before_json   JSON         COMMENT '变更前行快照；新增为 NULL',
  after_json    JSON         COMMENT '变更后行快照；含 DB 生成列',
  changed_at    DATETIME(3)  NOT NULL COMMENT '业务时刻（UTC+8），应用显式传',
  KEY idx_m3_entity (entity_type, entity_id, changed_at),
  CONSTRAINT chk_m3_audit_action CHECK (action IN ('INSERT','UPDATE','STATUS','RECEIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 供应商与采购变更审计（append-only）';

-- -----------------------------------------------------------------------------
-- 7. 收货幂等请求头（契约 v2 §9.1；Codex 初审必须改：响应丢失重试只入账一次）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m3_receipt_request (
  request_id  VARCHAR(64)  NOT NULL COMMENT '客户端幂等键，一次收货提交唯一',
  order_id    BIGINT       NOT NULL,
  operator_id BIGINT       COMMENT '若依 user_id；取不到为 NULL',
  content_fingerprint CHAR(64) NOT NULL COMMENT '请求内容指纹：SHA-256(orderId|按itemId升序的itemId:qty)，同键内容不一致拒绝（契约 §9.6）',
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json JSON         COMMENT 'DONE 时缓存 ReceiptResult，重试原样返回',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id),
  KEY idx_m3_req_order (order_id),
  CONSTRAINT chk_m3_req_status CHECK (status IN ('PENDING','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 收货幂等请求头';

-- -----------------------------------------------------------------------------
-- 8. 报价组合锁（契约 v2 §9.2；按 sku+supplier 串行化，杜绝并发区间重叠）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m3_quotation_lock (
  sku_id      BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  PRIMARY KEY (sku_id, supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 报价组合串行化锁表';

-- -----------------------------------------------------------------------------
-- 附录 A（可选，仅旧库升级用）：给已存在但缺列的 noon_m3_receipt_request 补 content_fingerprint。
-- 全新库不需要执行；隔离 IT/E2E 不执行本段。MySQL 不支持 ADD COLUMN IF NOT EXISTS，
-- 用 information_schema 判断后动态执行，重复执行安全。NOT NULL 加列需给旧行一个占位默认值，
-- 旧行缺少真实指纹时后端按「内容不一致」拒绝重放（不会误判为成功入账）。
-- -----------------------------------------------------------------------------
-- SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'noon_m3_receipt_request'
--     AND COLUMN_NAME = 'content_fingerprint');
-- SET @ddl = IF(@has_col = 0,
--   'ALTER TABLE noon_m3_receipt_request ADD COLUMN content_fingerprint CHAR(64) NOT NULL DEFAULT '''' COMMENT ''请求内容指纹''',
--   'SELECT 1');
-- PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
