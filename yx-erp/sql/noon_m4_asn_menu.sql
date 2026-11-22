-- M4 第三轮第三批 · FBN 入仓 ASN 收货菜单（3600 段，避开 3000/3100/3200/3300/3400/3500）
-- 目录：3600 库存与物流（复用头程 3500 目录亦可；此处独立目录便于分权）
-- 菜单：3610 ASN 入仓；按钮：3611 新建 / 3612 封单 / 3613 收货
-- 幂等：重复执行前先清子项再清父项。
DELETE FROM sys_role_menu WHERE menu_id IN (3600,3610,3611,3612,3613);
DELETE FROM sys_menu WHERE menu_id IN (3600,3610,3611,3612,3613);

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3600, '入仓与收货', 0, 6, 'noon-inbound', NULL, 1, 0, 'M', '0', '0', '', 'truck', 'admin', NOW(), 'M4 第三轮 ASN 入仓');

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3610, 'ASN 入仓', 3600, 1, 'asn', 'noon/inbound/asn/index', 1, 0, 'C', '0', '0', 'noon:inventory:asn:list', 'list', 'admin', NOW(), 'FBN 入仓 ASN 列表');

INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3611, '新建 ASN', 3610, 1, '', NULL, 1, 0, 'F', '0', '0', 'noon:inventory:asn:create', '', 'admin', NOW(), 'ASN 新建');
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3612, '封单发运', 3610, 2, '', NULL, 1, 0, 'F', '0', '0', 'noon:inventory:asn:seal', '', 'admin', NOW(), 'ASN 封单发运');
INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES(3613, '收货登记', 3610, 3, '', NULL, 1, 0, 'F', '0', '0', 'noon:inventory:asn:receive', '', 'admin', NOW(), 'ASN 收货登记');

-- 授权给 M4 业务角色（role_id=100 由 E2E 种子创建）
INSERT INTO sys_role_menu(role_id, menu_id) SELECT 100, menu_id FROM sys_menu WHERE menu_id IN (3600,3610,3611,3612,3613)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=100 AND menu_id=sys_menu.menu_id);
