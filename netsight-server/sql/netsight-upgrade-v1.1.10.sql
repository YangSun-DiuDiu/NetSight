-- ============================================================
-- NetSight V1.1.10：消息模板补齐租户名占位符
-- 背景：V1.1.8 只更新了租户1 的 6 条内置模板（offline/line/recovered/order_dispatch），
--       租户7（大华设备示范租户，pushplus 通道）3 条告警模板 + 租户1 的 manual/stock_low
--       4 条模板均缺 {{tenant_name}} 占位符，导致用户收到的推送没有租户名。
-- 本脚本：补齐上述 7 条模板；占位符命名遵循告警链路下划线风格 {{tenant_name}}。
-- 已确认：template 表无 del_flag 逻辑删除约束冲突，直接 UPDATE 内容列即可。
-- 执行前请备份：mysqldump -usadmin -p netsight notification_template > tpl_bak_v1110.sql
-- ============================================================

-- 1) 租户7 pushplus 告警模板（用户真实接收通道）
UPDATE notification_template SET content =
 '⚠️ 设备离线告警\n设备：{{device_name}}（{{device_ip}}）\n位置：{{device_location}}\n级别：{{severity}}\n租户：{{tenant_name}}\n请立即排查！'
WHERE id = 11 AND tenant_id = 7 AND template_code = 'tpl_device_offline' AND channel_type = 'pushplus';

UPDATE notification_template SET content =
 '⚠️ 设备外线异常\n设备：{{device_name}}（{{device_ip}}）\n位置：{{device_location}}\n租户：{{tenant_name}}\n建议巡检线路'
WHERE id = 12 AND tenant_id = 7 AND template_code = 'tpl_device_line' AND channel_type = 'pushplus';

UPDATE notification_template SET content =
 '✅ 设备已恢复\n设备：{{device_name}}（{{device_ip}}）\n位置：{{device_location}}\n租户：{{tenant_name}}'
WHERE id = 13 AND tenant_id = 7 AND template_code = 'tpl_device_recovered' AND channel_type = 'pushplus';

-- 2) 租户1 手动通知模板（V1.1.8 遗漏）
UPDATE notification_template SET content =
 '【NetSight】{{content}}（租户：{{tenant_name}}）'
WHERE id = 5 AND tenant_id = 1 AND template_code = 'tpl_manual' AND channel_type = 'sms';

UPDATE notification_template SET content =
 '📢 NetSight 通知\n{{content}}\n租户：{{tenant_name}}'
WHERE id = 6 AND tenant_id = 1 AND template_code = 'tpl_manual' AND channel_type = 'wechat';

-- 3) 租户1 备件库存预警模板（V1.1.8 遗漏）
UPDATE notification_template SET content =
 '【运维告警】备件库存预警：{{partName}}（{{partNo}}）当前库存 {{quantity}} {{unit}}，低于安全库存 {{safeStock}} {{unit}}，位置：{{location}}，租户：{{tenant_name}}，请及时补货！'
WHERE id = 9 AND tenant_id = 1 AND template_code = 'tpl_stock_low' AND channel_type = 'sms';

UPDATE notification_template SET content =
 '⚠️ 备件库存预警\n备件：{{partName}}（{{partNo}}）\n类型：{{partType}}\n当前库存：{{quantity}} {{unit}}\n安全库存：{{safeStock}} {{unit}}\n存放位置：{{location}}\n租户：{{tenant_name}}\n请及时安排补货！'
WHERE id = 10 AND tenant_id = 1 AND template_code = 'tpl_stock_low' AND channel_type = 'wechat';

-- 4) 校验：应无"缺租户占位符"的模板（预期输出 0 行）
SELECT id, tenant_id, channel_type, template_code,
       IF(content LIKE '%tenant_name%' OR content LIKE '%tenantName%', '含租户占位符', '缺租户占位符') AS flag
FROM notification_template
HAVING flag = '缺租户占位符';
