-- 菜单全局路由名冲突修复（幂等，可重复执行）
-- 根因：M3 采购单管理(3130) 与 M5 销售订单(3710) 的 path 均为 'order'，且两者 route_name 为空，
--       若依 getRouteName(空, 'order') => 'Order'（SysMenuServiceImpl:447-450），全局路由名重复。
--       前端 addRoute 同名路由被覆盖（vue-router name 全局唯一），导致 /noon-purchasing/order
--       菜单项可见但路由未注册，点击落到若依 404（供应商/报价/收货等同组菜单正常可对照）。
-- 修复：给两个菜单设置全局唯一的 route_name，保留原 path（URL 不变），不隐藏菜单、不改权限。
-- 本文件既修复已装库（UPDATE），也不影响未装库（UPDATE 影响 0 行，由各自种子 SQL 保证初始 route_name）。

-- 1) 已装库修复：采购单 / 销售订单分别消歧（幂等）
UPDATE sys_menu SET route_name = 'PurchaseOrder', update_time = NOW()
WHERE menu_id = 3130 AND (route_name IS NULL OR route_name = '');

UPDATE sys_menu SET route_name = 'SalesOrder', update_time = NOW()
WHERE menu_id = 3710 AND (route_name IS NULL OR route_name = '');

-- 2) 兼容极端情况：若某库 route_name 已被手工设成重复的 'Order'，同样归一
UPDATE sys_menu SET route_name = 'PurchaseOrder', update_time = NOW()
WHERE menu_id = 3130 AND route_name = 'Order';

UPDATE sys_menu SET route_name = 'SalesOrder', update_time = NOW()
WHERE menu_id = 3710 AND route_name = 'Order';

-- 校验（执行后可手动跑）：预期两条记录 route_name 分别为 PurchaseOrder / SalesOrder，互不相同。
-- SELECT menu_id, menu_name, path, route_name FROM sys_menu WHERE menu_id IN (3130, 3710);
