-- =============================================================
-- NetSight V1.1.8：告警 / 派单通知内容增加"租户名称"
-- 注意：本文件名此前曾用于"内置角色权限绑定 + 防越权加固"（2026-09-15，云端已执行，内容见 AGENTS.md V1.1.8 章节）；
--       该历史 SQL 无本地副本（被本轮覆盖），云端执行结果不受影响。
-- 说明：
--  1. 后端 EventCenterService.buildVars 已注入 {{tenant_name}}（告警链路，下划线风格）
--     WorkOrderService.notifyRepairer 已注入 {{tenantName}}（派单通知，驼峰风格）
--  2. 本脚本仅更新租户 1 的内置模板，加入租户名占位符
--  3. 租户自定义模板由各租户在消息模板页自行添加 {{tenant_name}} 占位符即可
-- =============================================================

-- 1. 设备离线告警（短信）
UPDATE notification_template
SET content = '【运维告警】高危故障：{{device_name}}（{{device_ip}}），【{{device_type}}】于{{time}}离线，位置：{{location}}，租户：{{tenant_name}}，请尽快排查！'
WHERE tenant_id = 1 AND template_code = 'tpl_device_offline' AND channel_type = 'sms';

-- 2. 设备离线告警（公众号）
UPDATE notification_template
SET content = '⚠️ 设备离线告警
设备：{{device_name}}（{{device_ip}}）
类型：{{device_type}}
位置：{{location}}
级别：{{severity}}
租户：{{tenant_name}}
时间：{{time}}
请立即排查处理！'
WHERE tenant_id = 1 AND template_code = 'tpl_device_offline' AND channel_type = 'wechat';

-- 3. 设备外线异常（公众号）
UPDATE notification_template
SET content = '⚠️ 设备外线异常
设备：{{device_name}}（{{device_ip}}）
类型：{{device_type}}
位置：{{location}}
级别：{{severity}}
租户：{{tenant_name}}
时间：{{time}}
设备在线，建议巡检线路！'
WHERE tenant_id = 1 AND template_code = 'tpl_device_line' AND channel_type = 'wechat';

-- 4. 故障恢复通知（公众号）
UPDATE notification_template
SET content = '✅ 故障已恢复
设备：{{device_name}}（{{device_ip}}）
位置：{{location}}
租户：{{tenant_name}}
恢复时间：{{time}}'
WHERE tenant_id = 1 AND template_code = 'tpl_device_recovered' AND channel_type = 'wechat';

-- 5. 工单派单通知（短信）
UPDATE notification_template
SET content = '【运维告警】工单{{orderNo}}：设备{{deviceName}}({{deviceIp}}){{faultDesc}}，请{{repairerName}}尽快上门处理，位置：{{deviceLocation}}，租户：{{tenantName}}'
WHERE tenant_id = 1 AND template_code = 'tpl_order_dispatch' AND channel_type = 'sms';

-- 6. 工单派单通知（公众号）
UPDATE notification_template
SET content = '⚠️ 新工单派发 工单号：{{orderNo}} 设备：{{deviceName}}（{{deviceIp}}） 类型：{{deviceType}} 位置：{{deviceLocation}} 故障：{{faultDesc}} 租户：{{tenantName}} 请{{repairerName}}尽快处理并回填维修结果。'
WHERE tenant_id = 1 AND template_code = 'tpl_order_dispatch' AND channel_type = 'wechat';
