-- =============================================================================
-- M4 库存第三轮第一批 · 盘点菜单
-- 约定：menu_id 3400 段，避开 M2(3000)/M3(3100)/M4 一轮(3200)/调拨(3300)。
-- 父菜单 3200「库存管理」；重复执行不报错、不覆盖已有改动。
-- =============================================================================

-- 盘点单管理（目录/页面）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3400, '盘点单管理', 3200, 5, 'stocktake', 'noon/inventory/stocktake/index', '', '', 1, 0, 'C', '0', '0',
       'noon:inventory:stocktake:list', 'tree-table', 'admin', NOW(), '', NULL, 'inv_stocktake 账实盘点'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3400);

-- 盘点单新增
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3401, '盘点单新增', 3400, 1, '', NULL, '', '', 1, 0, 'F', '0', '0',
       'noon:inventory:stocktake:create', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3401);

-- 盘点应用（过账）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3402, '盘点应用', 3400, 2, '', NULL, '', '', 1, 0, 'F', '0', '0',
       'noon:inventory:stocktake:apply', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3402);

-- 超级管理员角色（role_id=1）授予全部新增菜单
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (3400,3401,3402)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = sys_menu.menu_id);
