-- 服务号消息推送全面支持：115 测试环境数据脚本（幂等）
-- 适用库：netsight（115.159.194.149）；生产 1.94.26.126 不执行本脚本。
-- 说明：
--   1) 仅修正模板 43 的 content 为描述符 JSON，保持其 enabled=1 不动；
--   2) 新增 7 条微信模板行，全部 enabled=0、待管理员配置公众号模板 ID 后再启用；
--   3) 未提供分事件模板 ID 的行不写死 templateId，由发送器回落渠道默认 templateId；
--   4) 不新增、不启用任何通知规则，避免告警风暴。

-- 1. 修正现有设备离线微信模板（id=43, tenant_id=7）
UPDATE notification_template
SET content = '{"templateId":"-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U","url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"设备离线"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
    update_time = NOW()
WHERE id = 43 AND tenant_id = 7;

-- 2. 链路异常
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_device_line', '链路异常推送(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"外线异常"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_device_line' AND channel_id = 7
);

-- 3. 故障恢复
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_device_recovered', '故障恢复推送(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"已恢复"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"已恢复"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_device_recovered' AND channel_id = 7
);

-- 4. CPU 过高
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_device_cpu_high', 'CPU过高推送(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"CPU过高"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_device_cpu_high' AND channel_id = 7
);

-- 5. 内存过高
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_device_mem_high', '内存过高推送(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"内存过高"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_device_mem_high' AND channel_id = 7
);

-- 6. 工单派发
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_order_dispatch', '工单派发推送(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"待接单"},{"key":"character_string11","value":"{{order_no}}","default":"-"},{"key":"thing17","value":"{{fault_desc}}","default":"未填写故障描述"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_order_dispatch' AND channel_id = 7
);

-- 7. 备件库存不足
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_stock_low', '备件库存预警(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{part_name}}","default":"备件"},{"key":"phrase9","value":"{{event_state}}","default":"库存不足"},{"key":"character_string11","value":"{{part_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_stock_low' AND channel_id = 7
);

-- 8. 手动通知
INSERT INTO notification_template
  (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time, update_time, del_flag)
SELECT 7, 'tpl_manual', '手动通知(微信公众号)', 'wechat', 7,
       '{"url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{content}}","default":"系统通知"},{"key":"phrase9","value":"{{event_state}}","default":"通知"},{"key":"character_string11","value":"{{biz_id}}","default":"-"},{"key":"thing17","value":"{{tenant_name}}","default":"NetSight"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM notification_template
  WHERE tenant_id = 7 AND template_code = 'tpl_manual' AND channel_id = 7
);
