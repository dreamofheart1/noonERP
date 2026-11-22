-- M4 第三轮第二批 · 头程费用登记菜单（3500 段，避开 M2 3000/M3 3100/M4一轮 3200/调拨 3300/盘点 3400）
-- 目录：3500 库存与物流；菜单：3510 头程费用；按钮：3511 新增 / 3512 查看
-- 幂等：重复执行前先清子项再清父项，避免重复插入。
DELETE FROM sys_role_menu WHERE menu_id IN (3500,3510,3511,3512);
DELETE FROM sys_menu WHERE menu_id IN (3500,3510,3511,3512);

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3500, '头程与物流', 0, 5, 'noon-logistics', NULL, 1, 0, 'M', '0', '0', '', 'shipping', 'admin', NOW(), 'M4 第三轮 头程费用');

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3510, '头程费用', 3500, 1, 'shipment-cost', 'noon/logistics/shipmentCost/index', 1, 0, 'C', '0', '0', 'noon:inventory:shipment:list', 'money', 'admin', NOW(), '头程费用登记列表');

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3511, '新增费用', 3510, 1, '', NULL, 1, 0, 'F', '0', '0', 'noon:inventory:shipment:create', '', 'admin', NOW(), '头程费用登记新增');
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3512, '费用详情', 3510, 2, '', NULL, 1, 0, 'F', '0', '0', 'noon:inventory:shipment:list', '', 'admin', NOW(), '头程费用详情查看');

-- 授权给 M4 业务角色（role_id=100 由 E2E 种子创建）
INSERT INTO sys_role_menu(role_id, menu_id) SELECT 100, menu_id FROM sys_menu WHERE menu_id IN (3500,3510,3511,3512)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=100 AND menu_id=sys_menu.menu_id);
