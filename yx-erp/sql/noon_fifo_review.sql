-- =============================================================================
-- NOON-20261121-FIFO：FIFO 批次出库消耗"待核对"隔离层 + 服务契约
-- 执行者：WorkBuddy  日期：2026-11-21
-- -----------------------------------------------------------------------------
-- 目的：为"无法可靠识别来源/父订单行，或批次/妥投数量守恒不满足"的销售出库提供
--       明确隔离落点，避免在 inv_batch_consumption 上臆造批次消耗、伪造 COGS 或
--       超扣批次剩余量导致库存/成本失真。
-- 边界：独立脚本，不改主 schema v0.5、不改 intake/reversal 隔离层；内部 Spring
--       service 闸门，无 HTTP/UI、不连生产、不臆造平台事件身份、不做跨期快照重算。
-- 平台事实边界：真实事件类型 FBPI::ORDER_SYNC / FBPO::PO_SYNC / RETURNS::REFERENCE_UPDATE；
--       本包 SALE 出库事件是系统侧内部契约，非 Noon 平台原生事件类型；适配层未实现。
-- -----------------------------------------------------------------------------
-- 输入契约（DeliveryInput）：siteCode / sourceEventId / salOrderItemId / lines(sku_id,qty) /
--   rawPayload。siteCode 经 SiteAuthGuard 授权；salOrderItemId 指向真实 sal_order_item，
--   其父 sal_order 站点与请求一致；每条明细 SKU 必须等于父行 sku_id（单订单行单 SKU），
--   否则 ITEM_MISMATCH 隔离，禁止"对订单 A 扣减 SKU B"。sourceEventId 由系统侧 int_webhook_event
--   佐证（event_id 相同、站点一致、状态非 FAILED/KILLED、event_type=SALE_DELIVERY、
--   payload 与请求内容一致且 sal_order_item_id/sku_id/qty 逐项一致）。
-- 幂等键 biz_event_key='SALE:'+sourceEventId；唯一键 (biz_event_key,batch_id) 阻止重复妥投
--   双计 COGS；完整业务身份=订单行身份 + 按 SKU 聚合数量（不比事件号/元数据），
--   同键核心内容不同进 SOURCE_CONFLICT。
-- -----------------------------------------------------------------------------
-- 守恒（应用层事务，DDL 只保单行/单次幂等；Codex 阶段一复核四点已整改）：
--   * FIFO 选批：只在请求站点 site_code 内，按 received_at ASC, id ASC 选 status='OPEN'
--     且 qty_remaining>0 的批次（不跨站扣批），SELECT ... FOR UPDATE 当前读锁批次行；
--   * 批次剩余量守恒：按批汇总扣减 <= 该批 qty_remaining，不足则 BATCH_QTY_SHORTAGE 隔离；
--   * 妥投量守恒：父订单行 qty_delivered（FOR UPDATE 当前读）为权威累计，须与消耗流水
--     累计(sign=1)一致（不一致 DELIVERED_MISMATCH 隔离）；累计 + 本次(long) <=
--     订单行 qty - qty_cancelled，超出 DELIVERY_EXCEEDS_ORDER 隔离，防 int 溢出；
--   * 台账 qty_available 按 (sku,warehouse,site) 汇总一次扣减且 >=0（DDL CHECK 兜底），
--     规划阶段累计本单占用，不足 LEDGER_SHORTAGE 隔离（非异常回滚）；
--   * consumption 按 batch 聚合一次写入（一次妥投一个批次一行，合 uk_consume_biz_event 语义），
--     同事务同步父行 qty_delivered=qty_delivered+本次。
-- 锁序固定：父订单行 → 批次行(id 升序) → 台账行 → 父行 qty_delivered；锁异常事务整体回滚，调用方有界重试。
-- -----------------------------------------------------------------------------
-- reason_code 全集：
--   PARENT_ORDER_ITEM_MISSING / SITE_MISMATCH / ITEM_MISMATCH / DELIVERY_EXCEEDS_ORDER /
--   SOURCE_UNIDENTIFIABLE / SOURCE_UNVERIFIED / BATCH_QTY_SHORTAGE / LEDGER_SHORTAGE /
--   SOURCE_CONFLICT / DELIVERED_MISMATCH
-- =============================================================================

CREATE TABLE IF NOT EXISTS noon_fifo_review (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
  review_no                   VARCHAR(64)  NOT NULL COMMENT '待核对单号（应用生成，非平台身份）',
  entity_type                 VARCHAR(16)  NOT NULL COMMENT '固定 FIFO_DELIVERY',
  site_code_claimed           VARCHAR(16),
  sal_order_item_id_claimed   BIGINT,
  source_event_id             VARCHAR(128),
  raw_payload                 TEXT,
  reason_code                 VARCHAR(48)  NOT NULL,
  reason_detail               VARCHAR(512),
  status                      VARCHAR(24)  NOT NULL DEFAULT 'NEEDS_REVIEW',
  created_by                  BIGINT       NOT NULL,
  created_at                  DATETIME(3)  NOT NULL,
  CONSTRAINT chk_fifo_entity CHECK (entity_type IN ('FIFO_DELIVERY')),
  CONSTRAINT chk_fifo_status CHECK (status IN ('NEEDS_REVIEW','RESOLVED','REJECTED')),
  CONSTRAINT chk_fifo_resolution CHECK (status <> 'RESOLVED' OR (reason_code IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FIFO 出库待核对隔离层（append-only 审计见 noon_fifo_audit）';

CREATE TABLE IF NOT EXISTS noon_fifo_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  audit_no    VARCHAR(64)  NOT NULL,
  review_no   VARCHAR(64),
  entity_type VARCHAR(16)  NOT NULL,
  action      VARCHAR(24)  NOT NULL,
  actor_id    BIGINT       NOT NULL,
  act_at      DATETIME(3)  NOT NULL,
  detail_json TEXT,
  UNIQUE KEY uk_fifo_audit_no (audit_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FIFO 出库不可变审计（append-only）';
