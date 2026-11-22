-- -----------------------------------------------------------------------------
-- M5 订单与退货 · 菜单 3700 段
-- 依赖父目录在 schema/若依基础菜单；本脚本幂等插入（NOT EXISTS）。
-- -----------------------------------------------------------------------------

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3700, '订单退货', 0, 7, 'noon-sales', NULL, 1, 0, 'M', '0', '0', '', 'shopping', 'admin', NOW(), 'M5 订单与退货目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3700);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3710, '销售订单', 3700, 1, 'order', 'noon/sales/flow/index', 1, 0, 'C', '0', '0', 'noon:sales:order:list', 'list', 'admin', NOW(), 'M5 订单列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3710);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3711, '退货单', 3700, 2, 'return', 'noon/sales/flow/index', 1, 0, 'C', '0', '0', 'noon:sales:return:list', 'list', 'admin', NOW(), 'M5 退货列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3711);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3712, '退货处置', 3700, 3, 'dispose', 'noon/sales/flow/index', 1, 0, 'C', '0', '0', 'noon:sales:dispose:list', 'edit', 'admin', NOW(), 'M5 A/B/C 分级处置'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3712);

-- 按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3719, '订单导入', 3710, 1, '', NULL, 1, 0, 'F', '0', '0', 'noon:sales:order:import', '', 'admin', NOW(), '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3719);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3720, '订单妥投', 3710, 2, '', NULL, 1, 0, 'F', '0', '0', 'noon:sales:order:deliver', '', 'admin', NOW(), '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3720);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3721, '退货导入', 3711, 1, '', NULL, 1, 0, 'F', '0', '0', 'noon:sales:return:import', '', 'admin', NOW(), '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3721);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3722, '退货红冲', 3711, 2, '', NULL, 1, 0, 'F', '0', '0', 'noon:sales:return:reverse', '', 'admin', NOW(), '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3722);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 3723, '分级处置', 3712, 1, '', NULL, 1, 0, 'F', '0', '0', 'noon:sales:dispose:edit', '', 'admin', NOW(), '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3723);

-- 授权 role 100（授权 opAE/opAESA 等操作角色）与新菜单给超管 role 1
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 100, menu_id FROM sys_menu WHERE menu_id IN (3700,3710,3711,3712,3719,3720,3721,3722,3723)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=100 AND menu_id=sys_menu.menu_id);
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (3700,3710,3711,3712,3719,3720,3721,3722,3723)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=sys_menu.menu_id);
