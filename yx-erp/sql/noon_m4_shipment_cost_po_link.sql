-- -----------------------------------------------------------------------------
-- M4 头程费用 · 多选关联采购单（费用 ↔ 采购单 多对多）
-- 背景：原 log_shipment_cost 只有 asn_id 单关联；业务要求新增头程费用时可多选关联
--       多个已维护采购单（pur_order）。一笔费用可挂多个采购单，一个采购单也可出现在
--       多笔费用中，故用独立关联表，避免在费用主表塞 JSON/逗号串（不可约束、难查询）。
-- 约束口径（与 asnId 一致）：采购单必须存在且与费用同站点（跨站 400），发生在写入前；
--       授权由费用 siteCode 的 SiteAuthGuard 统一把守，关联表冗余 site_code 便于审计追溯。
-- 用法：在已执行 schema v0.5 + noon_m3_purchasing + noon_m4_shipment_cost 的库上执行。
--       幂等（CREATE TABLE IF NOT EXISTS），可重复执行。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS log_shipment_cost_po (
  cost_id     BIGINT       NOT NULL COMMENT '头程费用 id（log_shipment_cost.id）',
  po_id       BIGINT       NOT NULL COMMENT '采购单 id（pur_order.id）',
  site_code   VARCHAR(16)  NOT NULL COMMENT '冗余站点：写入时取费用站点，便于审计与按站追溯',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (cost_id, po_id),
  KEY idx_po (po_id),
  KEY idx_site (site_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程费用与采购单多对多关联（费用可多选采购单）';
