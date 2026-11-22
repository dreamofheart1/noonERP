-- M3 供应商与采购：菜单与权限种子（增量迁移，幂等）
-- 前置：若依基础 SQL（含 sys_menu / sys_role_menu）已初始化。
-- 约定：menu_id 从 3100 起，避开 M2 的 3000 段；重复执行不报错、不覆盖已有改动。
-- 本文件只建菜单与权限标识，不写入任何业务数据行。
-- 站点级数据权限由 SiteAuthGuard 在服务端执行，菜单权限不能替代站点授权。

-- 目录：Noon 供应商与采购
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3100, '供应商与采购', 0, 6, 'noon-purchasing', NULL, '', '', 1, 0, 'M', '0', '0', '', 'shopping-cart',
       'admin', NOW(), '', NULL, 'M3 采购目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3100);

-- 供应商管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3110, '供应商管理', 3100, 1, 'supplier', 'noon/purchasing/supplier/index', '', '', 1, 0, 'C', '0', '0',
       'noon:purchasing:supplier:list', 'user', 'admin', NOW(), '', NULL, 'md_supplier 管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3110);

-- 供应商按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3111, '供应商新增', 3110, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:supplier:add', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3111);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3112, '供应商修改', 3110, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:supplier:edit', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3112);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3113, '供应商启停', 3110, 3, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:supplier:status', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3113);

-- SKU 报价管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3120, 'SKU 报价', 3100, 2, 'quotation', 'noon/purchasing/quotation/index', '', '', 1, 0, 'C', '0', '0',
       'noon:purchasing:quotation:list', 'money', 'admin', NOW(), '', NULL, 'md_sku_supplier 历史价管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3120);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3121, '报价新增', 3120, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:quotation:add', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3121);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3122, '报价修改', 3120, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:quotation:edit', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3122);

-- 采购单管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
-- route_name=PurchaseOrder：与 M5 销售订单(3710, path 同为 'order')消歧，避免前端 addRoute 同名覆盖导致 404
SELECT 3130, '采购单管理', 3100, 3, 'order', 'noon/purchasing/order/index', '', 'PurchaseOrder', 1, 0, 'C', '0', '0',
       'noon:purchasing:order:list', 'documentation', 'admin', NOW(), '', NULL, 'pur_order 管理（状态机/部分收货）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3130);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3131, '采购单新增', 3130, 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:order:add', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3131);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3132, '采购单修改', 3130, 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:order:edit', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3132);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3133, '采购单确认', 3130, 3, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:order:confirm', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3133);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3134, '采购单收货', 3130, 4, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:order:receive', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3134);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3135, '采购单关单', 3130, 5, '', NULL, '', '', 1, 0, 'F', '0', '0', 'noon:purchasing:order:close', '', 'admin', NOW(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3135);

-- 收货记录（只读台账）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 3140, '收货记录', 3100, 4, 'receipt', 'noon/purchasing/receipt/index', '', '', 1, 0, 'C', '0', '0',
       'noon:purchasing:receipt:list', 'tree-table', 'admin', NOW(), '', NULL, 'noon_m3_receipt 只读'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 3140);

-- 超级管理员角色（role_id=1）授予全部新增菜单
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN
  (3100,3110,3111,3112,3113,3120,3121,3122,3130,3131,3132,3133,3134,3135,3140)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = sys_menu.menu_id);
