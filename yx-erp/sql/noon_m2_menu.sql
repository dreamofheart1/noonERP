-- M2 商品与基础资料：菜单与权限种子（增量迁移，幂等）
-- 前置：若依基础 SQL（含 sys_menu / sys_role_menu）已初始化。
-- 约定：menu_id 从 3000 起，避开若依内置 2000 段；重复执行不报错、不覆盖已有改动。
-- 本文件只建菜单与权限标识，不写入任何主数据行，不假设真实店铺/SKU。
-- 站点级数据权限由 SiteAuthGuard 在服务端执行，菜单权限不能替代站点授权。

-- 目录：Noon 商品与基础资料
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3000, '商品与基础资料', 0, 5, 'noon-masterdata', NULL, '', '', 1, 0, 'M', '0', '0', '', 'shopping',
       'admin', NOW(), '', NULL, 'M2 主数据目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3000);

-- 五个管理菜单
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3010, '店铺管理', 3000, 1, 'store', 'noon/masterdata/store/index', '', '', 1, 0, 'C', '0', '0',
       'noon:masterdata:store:list', 'store', 'admin', NOW(), '', NULL, 'md_store 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3010);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3011, '站点管理', 3000, 2, 'site', 'noon/masterdata/site/index', '', '', 1, 0, 'C', '0', '0',
       'noon:masterdata:site:list', 'flag', 'admin', NOW(), '', NULL, 'md_site 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3011);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3012, '仓库管理', 3000, 3, 'warehouse', 'noon/masterdata/warehouse/index', '', '', 1, 0, 'C', '0', '0',
       'noon:masterdata:warehouse:list', 'build', 'admin', NOW(), '', NULL, 'md_warehouse 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3012);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3013, 'SKU 管理', 3000, 4, 'sku', 'noon/masterdata/sku/index', '', '', 1, 0, 'C', '0', '0',
       'noon:masterdata:sku:list', 'shopping', 'admin', NOW(), '', NULL, 'md_sku 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3013);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3014, 'Partner SKU 映射', 3000, 5, 'partner-sku', 'noon/masterdata/partnerSku/index', '', '', 1, 0, 'C', '0', '0',
       'noon:masterdata:partnerSku:list', 'link', 'admin', NOW(), '', NULL, 'md_partner_sku 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3014);

-- 按钮权限：每个菜单 3 个操作（save / status / list 已含在菜单 perms）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3020, '店铺-编辑', 3010, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:store:save', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3020);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3021, '店铺-状态', 3010, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:store:status', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3021);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3022, '站点-编辑', 3011, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:site:save', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3022);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3023, '站点-状态', 3011, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:site:status', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3023);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3024, '仓库-编辑', 3012, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:warehouse:save', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3024);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3025, '仓库-状态', 3012, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:warehouse:status', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3025);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3026, 'SKU-编辑', 3013, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:sku:save', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3026);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3027, 'SKU-状态', 3013, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:sku:status', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3027);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3028, '映射-编辑', 3014, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:partnerSku:save', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3028);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3029, '映射-状态', 3014, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:masterdata:partnerSku:status', '#',
       'admin', NOW(), '', NULL, 'M2 按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3029);

-- 超管（role_id=1）自动拥有全部菜单；普通用户由管理员在角色管理中单独授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id BETWEEN 3000 AND 3029
AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND sys_role_menu.menu_id = sys_menu.menu_id);
