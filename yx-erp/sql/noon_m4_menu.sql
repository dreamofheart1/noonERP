-- M4 库存与 FIFO 第一轮：菜单与权限种子（增量迁移，幂等）
-- 前置：若依基础 SQL（含 sys_menu / sys_role_menu）已初始化。
-- 约定：menu_id 从 3200 起，避开 M2 的 3000 段、M3 的 3100 段；重复执行不报错、不覆盖已有改动。
-- 本文件只建菜单与权限标识，不写入任何业务数据行。
-- 站点级数据权限由 SiteAuthGuard 在服务端执行，菜单权限不能替代站点授权。

-- 目录：Noon 库存与 FIFO
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3200, '库存与 FIFO', 0, 7, 'noon-inventory', NULL, '', '', 1, 0, 'M', '0', '0', '', 'inventory',
       'admin', NOW(), '', NULL, 'M4 库存目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3200);

-- 入库单管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3210, '入库单管理', 3200, 1, 'inbound', 'noon/inventory/inbound/index', '', '', 1, 0, 'C', '0', '0',
       'noon:inventory:inbound:list', 'tree-table', 'admin', NOW(), '', NULL, 'inv_inbound 管理（收货→批次→台账）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3210);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3211, '入库单新增', 3210, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:inventory:inbound:add', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3211);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3212, '入库确认', 3210, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:inventory:inbound:confirm', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3212);

-- FIFO 批次（只读）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3220, 'FIFO 批次', 3200, 2, 'batch', 'noon/inventory/batch/index', '', '', 1, 0, 'C', '0', '0',
       'noon:inventory:batch:list', 'tree-table', 'admin', NOW(), '', NULL, 'inv_batch 只读'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3220);

-- 四态台账（只读）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3230, '四态台账', 3200, 3, 'ledger', 'noon/inventory/ledger/index', '', '', 1, 0, 'C', '0', '0',
       'noon:inventory:ledger:list', 'tree-table', 'admin', NOW(), '', NULL, 'inv_stock_ledger 只读'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3230);

-- 超级管理员角色（role_id=1）授予全部新增菜单
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (3200,3210,3211,3212,3220,3230)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = sys_menu.menu_id);
