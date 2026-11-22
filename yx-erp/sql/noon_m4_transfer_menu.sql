-- M4 库存与 FIFO 第二轮：调拨菜单与权限种子（增量迁移，幂等）
-- 前置：若依基础 SQL + noon_m4_menu.sql（目录 3200）已初始化。
-- 约定：menu_id 从 3300 起，避开 M2(3000)/M3(3100)/M4 第一轮(3200) 段；重复执行不报错。
-- 站点级数据权限由 SiteAuthGuard 在服务端执行，菜单权限不能替代站点授权。

-- 调拨单管理（挂在 M4 目录 3200 下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3300, '调拨单管理', 3200, 4, 'transfer', 'noon/inventory/transfer/index', '', '', 1, 0, 'C', '0', '0',
       'noon:inventory:transfer:list', 'tree-table', 'admin', NOW(), '', NULL, 'inv_transfer（C-5 在途拆批）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3300);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3301, '调拨单新增', 3300, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:inventory:transfer:add', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3301);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3302, '调拨发运', 3300, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:inventory:transfer:ship', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3302);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3303, '调拨收货', 3300, 3, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:inventory:transfer:receive', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3303);

-- 超级管理员角色（role_id=1）授予全部新增菜单
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (3300,3301,3302,3303)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = sys_menu.menu_id);
