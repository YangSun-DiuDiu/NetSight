-- ============================================================
-- NetSight V1.1.5 升级脚本（第 6 周）
-- 1. 备件库存预警：消息模板 tpl_stock_low（短信+公众号）+ 路由规则
-- 2. 菜单权限：监控大屏（/dashboard）+ 网络拓扑（设备资产下 /device/topology）
-- 所有 INSERT 显式 id（避免 auto_increment 错位）
-- ============================================================

-- ---------- 1. 备件库存预警模板（id 9/10） ----------
INSERT INTO notification_template (id, tenant_id, template_code, template_name, channel_type, content, enabled, create_time, update_time, del_flag) VALUES
(9, 1, 'tpl_stock_low', '备件库存预警', 'sms',
 '【运维告警】备件库存预警：{{partName}}（{{partNo}}）当前库存 {{quantity}} {{unit}}，低于安全库存 {{safeStock}} {{unit}}，位置：{{location}}，请及时补货！',
 1, NOW(), NOW(), 0),
(10, 1, 'tpl_stock_low', '备件库存预警', 'wechat',
 '⚠️ 备件库存预警\n备件：{{partName}}（{{partNo}}）\n类型：{{partType}}\n当前库存：{{quantity}} {{unit}}\n安全库存：{{safeStock}} {{unit}}\n存放位置：{{location}}\n请及时安排补货！',
 1, NOW(), NOW(), 0);

-- ---------- 2. 库存预警路由规则（id 5） ----------
-- 条件：事件类型 stock_low（severity=warning），双通道发送
INSERT INTO notification_rule (id, tenant_id, rule_name, event_type, condition_json, template_id, channels_json, receiver_strategy_json, enabled, create_time, update_time, del_flag) VALUES
(5, 1, '备件库存预警', 'stock_low', '{"severity":"warning"}', 9, '["sms","wechat"]', '[]', 1, NOW(), NOW(), 0);

-- ---------- 3. 菜单权限（id 98-102） ----------
-- 98 监控大屏顶级（sort=0 排最前）
INSERT INTO sys_permission (id, parent_id, perm_name, perm_key, perm_type, sort, path, component, icon, create_time, update_time, del_flag) VALUES
(98, 0, '监控大屏', 'dashboard:menu', 'menu', 0, '/dashboard', 'Layout', 'monitor', NOW(), NOW(), 0),
(99, 98, '监控大屏', 'dashboard:index:menu', 'menu', 1, 'overview', 'dashboard/index', 'monitor', NOW(), NOW(), 0),
(100, 40, '网络拓扑', 'device:topology:menu', 'menu', 3, 'topology', 'topology/index', 'chart', NOW(), NOW(), 0),
(101, 100, '拓扑查看', 'device:topology:view', 'button', 1, '', '', '', NOW(), NOW(), 0),
(102, 99, '大屏查看', 'dashboard:view', 'button', 1, '', '', '', NOW(), NOW(), 0);

-- ---------- 4. super_admin（role_id=1）绑定 98-102 ----------
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 98), (1, 99), (1, 100), (1, 101), (1, 102);
