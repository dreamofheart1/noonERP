-- M4 库存与 FIFO 第二轮：调拨 + 在途拆批 + 短少 增量迁移（执行者：WorkBuddy，2026-11-22）
-- 设计源：docs/noon-erp-schema-v0.5.sql 第 471–494 行（inv_transfer / inv_transfer_item）；契约
--         docs/verification/m4-transfer/contract.md 第 1 节。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，重复执行只跳过已存在表，不修改既有结构。
-- 前置：第一轮 noon_m4_inventory.sql 已建 inv_batch / inv_stock_ledger / inv_stock_move /
--       inv_batch_consumption（后者由 FIFO 服务建表）；md_warehouse / md_sku（M2）已初始化。
-- 范围：仅第二轮调拨 2 张 schema 表 + 1 张调拨幂等表；
--       不改第一轮表、不含第三轮 ASN/头程/盘点、不含业务种子、不含菜单（noon_m4_transfer_menu.sql 另文）。

-- -----------------------------------------------------------------------------
-- 1. 调拨单（schema v0.5 第 471–479 行逐字段，不改语义）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_transfer (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_no      VARCHAR(64)  NOT NULL,
  from_warehouse_id BIGINT      NOT NULL,
  to_warehouse_id  BIGINT       NOT NULL,
  status           VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_TRANSIT/RECEIVED/CLOSED',
  shipped_at       DATETIME(3)  COMMENT 'UTC+8',
  received_at      DATETIME(3)  COMMENT 'UTC+8',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_transfer_no (transfer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单（站点内 源仓→目标仓；C-5 在途拆批）';

-- -----------------------------------------------------------------------------
-- 2. 调拨单行（schema v0.5 第 483–494 行逐字段，不改语义）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inv_transfer_item (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_id  BIGINT       NOT NULL,
  sku_id       BIGINT       NOT NULL,
  batch_no     VARCHAR(64)  NOT NULL COMMENT '来源父批次号（拆批血缘根）',
  qty_shipped  INT          NOT NULL,
  qty_received INT          DEFAULT 0,
  diff_qty     INT          DEFAULT 0 COMMENT '收货差异，回写库存与批次',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_transfer (transfer_id),
  CONSTRAINT chk_transfer_item_qty CHECK (qty_shipped > 0 AND qty_received >= 0 AND qty_received <= qty_shipped)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单行，在途与收货差异';

-- -----------------------------------------------------------------------------
-- 3. 调拨幂等表（对齐 noon_m4_inbound_request：授权>指纹>DONE重放>状态闸门）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m4_transfer_request (
  request_id         VARCHAR(64)  NOT NULL,
  transfer_id        BIGINT       NOT NULL,
  operator_id        BIGINT,
  content_fingerprint CHAR(64)    NOT NULL COMMENT 'SHA-256(transferId|动作|按itemId升序的itemId:qty)；动作进入指纹使 ship/receive 隔离',
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json        JSON         COMMENT 'DONE 时缓存 TransferResult，重放原样返回',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 调拨幂等表（Ship/Receive 动作级）';

-- -----------------------------------------------------------------------------
-- 4. 创建调拨单幂等表（v1.2 新增，Codex 复核整改；独立于 ship/receive 幂等表）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS noon_m4_transfer_create_request (
  request_id         VARCHAR(64)  NOT NULL,
  transfer_id        BIGINT       NOT NULL COMMENT 'CREATE 成功后回填的真实调拨单 id',
  operator_id        BIGINT,
  content_fingerprint CHAR(64)    NOT NULL COMMENT 'SHA-256(CREATE|from|to|按(skuId,batchNo)升序的 skuId:batchNo:qty)',
  status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
  result_json        JSON         COMMENT 'DONE 时缓存 TransferResult，重放原样返回',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M4 调拨建单幂等表（CREATE 动作级，create 时 transferId 尚未生成）';
