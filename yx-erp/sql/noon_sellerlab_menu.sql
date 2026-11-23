-- -----------------------------------------------------------------------------
-- M15 Seller Lab 只读查询 · 菜单 3900 段（FBN 库存对比 / Sales 销售明细；ASN 暂缓）
-- 幂等插入；权限复用已有点位，不新增权限点。
-- -----------------------------------------------------------------------------

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3900, 'Noon官方数据', 0, 9, 'noon-official', NULL, 1, 0, 'M', '0', '0', '', 'shopping', 'admin', NOW(), 'Noon Seller Lab 只读数据'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3900);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3910, 'FBN库存对比', 3900, 1, 'fbn-compare', 'noon/sellerlab/fbnCompare/index', 1, 0, 'C', '0', '0', 'noon:masterdata:sku:list', 'list', 'admin', NOW(), '官方FBN库存与系统台账简单对比'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3910);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3911, 'Sales销售明细', 3900, 2, 'sales-list', 'noon/sellerlab/salesList/index', 1, 0, 'C', '0', '0', 'noon:sales:order:list', 'list', 'admin', NOW(), 'Seller Lab销售明细(item_nr)'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3911);

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (3900,3910,3911)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=sys_menu.menu_id);
