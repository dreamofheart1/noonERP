-- =============================================================================
-- NOON-20261121-RETURN：订单/退货正式入账"待核对"隔离层
-- 执行者：WorkBuddy  日期：2026-11-21
-- -----------------------------------------------------------------------------
-- 目的：为"无法可靠识别来源/父记录"的订单与退货提供一个明确的隔离落点，
--       避免在正式表 sal_order / sal_return 上臆造平台事件身份或伪造父关联。
-- 边界：
--   * 本脚本独立，不修改 docs/noon-erp-schema-v0.5.sql（Codex 与 WorkBuddy 共用）。
--   * 表名前缀 noon_ 遵循 v0.5 命名决议；sys_* 保留给若依。
--   * 入账服务（IntakeService）负责：站点授权、父记录存在、来源可靠性判定；
--     只有通过全部闸门、且有可信事件身份的记录才写正式表。其余一律写本表待核对。
--   * 本表只保存调用方"原样提供"的来源标识；不生成、不猜测平台事件号/退货号/指纹。
--   * 解析（RESOLVE）须由具备权限的主体在拿到可信证据后显式调用，并写不可变审计。
--   * 三次复核修订：来源"字面上非空"不等于已验证。唯一可核验佐证 = 系统侧 int_webhook_event
--     中同 event_id、站点非空且等于请求站点、状态非 FAILED/KILLED、event_type 匹配、
--     payload_raw 与请求报文内容一致且业务字段（订单号/退货号/门店/商品/Partner SKU/
--     金额/数量）与入参一致的记录；任一项不满足进 SOURCE_UNVERIFIED，空事件号或空报文进
--     SOURCE_UNIDENTIFIABLE。不得假定所有订单都有 webhook；无事件即待核对，不臆造身份。
--   * 正式表禁止写 NULL 占位主数据：门店/商品必须为正且被可信 payload 佐证；金额允许 0
--     （合法零金额须有证据），缺字段进 MASTER_DATA_INCOMPLETE，与证据不符进 SOURCE_UNVERIFIED。
--     门店/商品/Partner SKU 还须在本地 md_store/md_site/md_sku/md_partner_sku 映射存在且 ACTIVE，
--     无映射进 MASTER_DATA_UNVERIFIED；平台数字不直接当系统内部 ID。
--   * 冲突语义（四次复核统一两入口）：订单核心业务字段（站点/订单号/门店/商品/Partner SKU/
--     金额/数量/币种）、退货核心业务字段（站点/订单号/平台退货号/父订单行/数量）不同进
--     SOURCE_CONFLICT，禁止误当重传 ACK；仅事件元数据或报文格式变化按幂等 ACK。
--     退货正常重复 postReturn 与并发回读 resolveConcurrentReturn 共用 sameReturnBusiness 判定，
--     均不比较整份 raw_payload，语义一致；字段已落在 sal_return 的 biz_return_key/qty/
--     sal_order_item_id 列上。raw_payload 仅作证据绑定与待核对原样留痕，不参与幂等判定。
--     reason_code 全集：PARENT_MISSING/SITE_MISMATCH/ITEM_PARENT_MISMATCH/
--     SOURCE_UNIDENTIFIABLE/SOURCE_UNVERIFIED/MASTER_DATA_INCOMPLETE/
--     MASTER_DATA_UNVERIFIED/SOURCE_CONFLICT。
-- =============================================================================

CREATE TABLE IF NOT EXISTS noon_intake_review (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  review_no          VARCHAR(64)  NOT NULL COMMENT '待核对单号（应用确定性生成，非平台身份）',
  entity_type        VARCHAR(16)  NOT NULL COMMENT 'ORDER / RETURN',
  -- 调用方原样提供的来源标识；允许为空。空值本身即是"来源不可识别"的证据，禁止补造。
  site_code_claimed  VARCHAR(16)           COMMENT '调用方声明的站点；是否可信以父记录为准',
  order_no_claimed   VARCHAR(64)           COMMENT '调用方声明的平台订单号（原样）',
  source_return_no   VARCHAR(128)          COMMENT '调用方声明的平台退货单号；可空，原样保留',
  source_event_id    VARCHAR(128)          COMMENT '调用方声明的平台事件ID/指纹；可空，原样保留',
  raw_payload        JSON                  COMMENT '原始报文，字段不删减，供人工核对',
  reason_code        VARCHAR(48)  NOT NULL COMMENT '待核对原因：PARENT_MISSING/SITE_MISMATCH/ITEM_PARENT_MISMATCH/SOURCE_UNIDENTIFIABLE/SOURCE_UNVERIFIED/MASTER_DATA_INCOMPLETE/MASTER_DATA_UNVERIFIED/SOURCE_CONFLICT',
  reason_detail      VARCHAR(512)          COMMENT '人类可读说明，不缺失原始字段',
  status             VARCHAR(24)  NOT NULL DEFAULT 'NEEDS_REVIEW' COMMENT 'NEEDS_REVIEW / RESOLVED / REJECTED',
  resolution_biz_key VARCHAR(160)          COMMENT '解析后确认的业务幂等键；仅 RESOLVE 时按可信证据写入',
  resolved_by        BIGINT                COMMENT '解析操作主体 sys_user.user_id',
  resolved_at        DATETIME(3)           COMMENT 'UTC+8',
  created_by         BIGINT                COMMENT '提交主体 sys_user.user_id',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_review_no (review_no),
  KEY idx_status_reason (status, reason_code),
  KEY idx_order (order_no_claimed),
  -- 用 OR 而非 IN 列表：语义等价，且避免部分内存库对 CHECK 内 IN 常量集合的跨会话缓存问题。
  CONSTRAINT chk_intake_entity CHECK (entity_type = 'ORDER' OR entity_type = 'RETURN'),
  CONSTRAINT chk_intake_status CHECK (status = 'NEEDS_REVIEW' OR status = 'RESOLVED' OR status = 'REJECTED'),
  -- 解析必须给出结果键与主体/时间；未解析时这些字段应保持空，防止半解析态。
  CONSTRAINT chk_intake_resolution CHECK (
      status = 'NEEDS_REVIEW'
      OR ((status = 'RESOLVED' OR status = 'REJECTED') AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单/退货入账待核对隔离层（来源不可靠时不臆造身份）';

-- append-only 审计：解析/入账决策留痕，禁原地修改与物理删除（与 noon_audit_log 同口径）。
CREATE TABLE IF NOT EXISTS noon_intake_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  audit_no    VARCHAR(64)  NOT NULL,
  review_no   VARCHAR(64)           COMMENT '关联待核对单；正式入账直接过闸的可为空',
  entity_type VARCHAR(16)  NOT NULL,
  action      VARCHAR(32)  NOT NULL COMMENT 'POSTED / QUARANTINED / RESOLVED / REJECTED',
  actor_id    BIGINT       NOT NULL,
  act_at      DATETIME(3)  NOT NULL COMMENT 'UTC+8',
  detail_json JSON,
  UNIQUE KEY uk_intake_audit_no (audit_no),
  KEY idx_intake_audit_review (review_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入账决策不可变审计';
