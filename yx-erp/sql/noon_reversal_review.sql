-- =============================================================================
-- NOON-20261121-REVERSAL：库存/累计退货红冲"待核对"隔离层 + 服务契约
-- 执行者：WorkBuddy  日期：2026-11-21
-- -----------------------------------------------------------------------------
-- 目的：为"无法可靠识别来源/父记录/原消耗行，或累计红冲超限"的退货红冲提供
--       明确的隔离落点，避免在正式表 inv_batch_consumption 上臆造批次消耗、伪造
--       红冲关联或超额冲回导致 COGS/库存失真。
-- 边界：
--   * 本脚本独立，不修改 docs/noon-erp-schema-v0.5.sql（Codex 与 WorkBuddy 共用），
--     也不修改 noon_intake_review.sql（订单/退货入账隔离层）。
--   * 工作包为内部 Spring service 闸门，无 HTTP/UI、不连生产、不臆造平台事件身份、
--     不实现真实 FIFO 批次选择/跨期快照重算；接入与生产迁移由后续工作包负责。
--   * 平台事实边界（Codex 2026-11-21 只读联调）：真实事件类型为 FBPI::ORDER_SYNC /
--     FBPO::PO_SYNC / RETURNS::REFERENCE_UPDATE，均非 ORDER/RETURN。本包事件类型
--     RETURN_REVERSAL 是系统侧内部契约，不是 Noon 平台原生事件类型；真实类型→内部
--     类型的适配层尚未实现，禁止把内部类型当平台事实。
-- -----------------------------------------------------------------------------
-- 输入契约（ReversalInput）：siteCode / sourceEventId / salReturnId /
--   originalConsumptionId / qty / rawPayload。
--   * siteCode 必须经 SiteAuthGuard 授权；父退货站点必须与之一致。
--   * sourceEventId 必须能在系统侧 int_webhook_event 中被佐证：event_id 相同、
--     site_code 非空且等于请求站点、状态非 FAILED/KILLED、event_type = RETURN_REVERSAL、
--     payload_raw 与请求报文内容一致且 sal_return_id/reversal_of_id/qty/sku_id 与入参一致。
--     无佐证一律隔离，不臆造平台事件号。
--   * salReturnId 必须指向真实存在的 sal_return（可信父退货），站点与 sku 取父记录真实值。
--   * originalConsumptionId 必须指向真实存在的 inv_batch_consumption 正常扣减行
--     （服务用 FOR UPDATE 当前读取 actual sign，显式校验 sign = +1 且 move_type = SALE；
--      DDL 允许 SALE+sign=-1，故不能只看 move_type），其 sku_id / sal_order_item_id
--     必须与父退货一致。sign/move_type 不符进 ORIGINAL_NOT_SALE 隔离。
--   * 业务幂等键 biz_event_key = 'REV:' + sourceEventId（系统侧确定性键，非平台事件号）；
--     唯一键 (biz_event_key, batch_id) 阻止同一红冲事件重复过账，同键核心业务字段不同进
--     SOURCE_CONFLICT，相同按幂等 ACK。
--   * 处理顺序【二次复核修订】：幂等/冲突判定必须先于累计守恒。否则原消耗已用满时，
--     合法重传会被"已冲回+本次>原消耗"误判超限隔离，而非 ACK。
-- -----------------------------------------------------------------------------
-- 累计守恒（schema v0.5 明确 DDL 不承担，须应用层事务）：
--   * 对原消耗行：以 SELECT id,qty ... FOR UPDATE 当前读锁住 reversal_of_id=原行 AND
--     sign=-1 的所有红冲行（按 id 升序固定锁序），Java 端 long 累加得已冲回数量，
--     要求 已冲回 + 本次 qty <= 原消耗 qty，否则 REVERSAL_EXCEEDS_ORIGINAL 隔离。
--   * 对父退货单：同样当前读锁住 sal_return_id=父退货 AND sign=-1 的红冲行，要求
--     已红冲 + 本次 qty <= 退货单 qty，否则 REVERSAL_EXCEEDS_RETURN 隔离。
--   * 必须用当前读而非普通一致性读：MySQL REPEATABLE READ 下若上游先建旧快照，普通
--     SUM 看不到已提交红冲行，不同原行同父退货会超额；FOR UPDATE 当前读解决此问题。
--     long 累加避免 int 溢出。DDL 只负责单次幂等（uk_consume_biz_event）与单行 CHECK，
--     不承担累计守恒。锁顺序固定：原消耗行 → 父退货行 → 红冲汇总行(id 升序)，无循环等待；
--     锁异常由事务整体回滚，调用方重试，service 不在半途放行。
-- -----------------------------------------------------------------------------
-- reason_code 全集：
--   PARENT_RETURN_MISSING / SITE_MISMATCH / ORIGINAL_CONSUMPTION_MISSING /
--   ORIGINAL_NOT_SALE / ITEM_MISMATCH / SOURCE_UNIDENTIFIABLE / SOURCE_UNVERIFIED /
--   REVERSAL_EXCEEDS_ORIGINAL / REVERSAL_EXCEEDS_RETURN / SOURCE_CONFLICT
-- =============================================================================

CREATE TABLE IF NOT EXISTS noon_reversal_review (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
  review_no                   VARCHAR(64)  NOT NULL COMMENT '待核对单号（应用生成，非平台身份）',
  entity_type                 VARCHAR(16)  NOT NULL COMMENT '固定 REVERSAL',
  site_code_claimed           VARCHAR(16)           COMMENT '调用方声明站点；可信值以父退货为准',
  sal_return_id_claimed       BIGINT                COMMENT '调用方声明的父退货 id（原样）',
  original_consumption_id_claimed BIGINT            COMMENT '调用方声明的原消耗 id（原样）',
  source_event_id             VARCHAR(128)          COMMENT '调用方声明的系统侧事件号；可空即不可识别',
  raw_payload                 JSON                  COMMENT '原始报文，字段不删减，供人工核对',
  reason_code                 VARCHAR(48)  NOT NULL COMMENT '见文件头 reason_code 全集',
  reason_detail               VARCHAR(512)          COMMENT '人类可读说明，不缺失原始字段',
  status                      VARCHAR(24)  NOT NULL DEFAULT 'NEEDS_REVIEW' COMMENT 'NEEDS_REVIEW / RESOLVED / REJECTED',
  resolution_biz_key          VARCHAR(160)          COMMENT '解析后确认的业务幂等键；仅 RESOLVE 时写入',
  resolved_by                 BIGINT,
  resolved_at                 DATETIME(3),
  created_by                  BIGINT,
  created_at                  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_review_no (review_no),
  KEY idx_status_reason (status, reason_code),
  CONSTRAINT chk_reversal_entity CHECK (entity_type = 'REVERSAL'),
  CONSTRAINT chk_reversal_status CHECK (status = 'NEEDS_REVIEW' OR status = 'RESOLVED' OR status = 'REJECTED'),
  CONSTRAINT chk_reversal_resolution CHECK (
      status = 'NEEDS_REVIEW'
      OR ((status = 'RESOLVED' OR status = 'REJECTED') AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存/累计退货红冲待核对隔离层';

-- append-only 审计：过账/隔离/解析决策留痕，禁原地修改与物理删除。
CREATE TABLE IF NOT EXISTS noon_reversal_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  audit_no    VARCHAR(64)  NOT NULL,
  review_no   VARCHAR(64)           COMMENT '关联待核对单；正式过账可为空',
  entity_type VARCHAR(16)  NOT NULL,
  action      VARCHAR(32)  NOT NULL COMMENT 'POSTED / QUARANTINED / RESOLVED',
  actor_id    BIGINT       NOT NULL,
  act_at      DATETIME(3)  NOT NULL COMMENT 'UTC+8',
  detail_json JSON,
  UNIQUE KEY uk_reversal_audit_no (audit_no),
  KEY idx_reversal_audit_review (review_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红冲决策不可变审计';
