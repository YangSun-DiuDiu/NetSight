-- =====================================================================
-- NetSight V1.2 知识库故障库（demo 功能 3/5）
-- 增量 SQL v1.1.14
-- 表：fault_article（故障知识条目）
-- 权限：124-130（124 知识中心顶级 / 125 故障库 / 126-130 按钮）
-- 角色：role1/2 全绑 124-130；role3/4 绑 124+125+126+130（可查可看详情，无管理）
-- 演示数据：租户1 常见故障 3 条
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 故障知识库表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fault_article` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`    BIGINT       NOT NULL DEFAULT 0    COMMENT '租户ID',
  `title`        VARCHAR(200) NOT NULL COMMENT '标题',
  `category`     VARCHAR(50)  NOT NULL COMMENT '分类（设备离线/链路异常/视频故障/门禁故障/性能问题/其他）',
  `device_type`  VARCHAR(30)  NOT NULL DEFAULT 'all' COMMENT '适用设备类型 network/camera/nvr/door_controller/all',
  `brand`        VARCHAR(100) DEFAULT NULL COMMENT '适用品牌（空=不限）',
  `model`        VARCHAR(100) DEFAULT NULL COMMENT '适用型号（空=不限）',
  `symptom`      TEXT         COMMENT '故障现象描述',
  `cause`        TEXT         COMMENT '可能原因',
  `solution`     TEXT         COMMENT '处理步骤/解决方案',
  `keywords`     VARCHAR(255) DEFAULT NULL COMMENT '关键词（逗号分隔）',
  `severity_ref` VARCHAR(20)  NOT NULL DEFAULT 'info' COMMENT '参考级别 critical/warning/info',
  `view_count`   INT          NOT NULL DEFAULT 0    COMMENT '浏览次数',
  `status`       TINYINT      NOT NULL DEFAULT 1    COMMENT '状态 1启用 0停用',
  `create_by`    VARCHAR(50)  DEFAULT NULL COMMENT '创建人（快照）',
  `create_time`  DATETIME     DEFAULT NULL COMMENT '创建时间',
  `update_time`  DATETIME     DEFAULT NULL COMMENT '修改时间',
  `del_flag`     TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_tenant`    (`tenant_id`, `del_flag`),
  KEY `idx_dev_type`  (`tenant_id`, `device_type`),
  KEY `idx_category`  (`tenant_id`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障知识库条目';

-- ---------------------------------------------------------------------
-- 2. 菜单/权限（显式 id，从 124 开始）
--    124 知识中心顶级（icon=el-icon-reading，Element 内置已确认存在）
--    125 故障库（path='fault'）
--    126-130 按钮权限
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(124, 0,   '知识中心', 'knowledge:menu',       'menu',   1, '/knowledge', 'Layout',                  'el-icon-reading', NOW(), NOW(), 0),
(125, 124, '故障库',   'knowledge:fault:menu', 'menu',   1, 'fault',      'knowledge/fault/index',  'documentation',   NOW(), NOW(), 0),
(126, 125, '故障查询', 'knowledge:fault:list',   'button', 1, '', '', '', NOW(), NOW(), 0),
(127, 125, '故障新增', 'knowledge:fault:add',    'button', 2, '', '', '', NOW(), NOW(), 0),
(128, 125, '故障修改', 'knowledge:fault:edit',   'button', 3, '', '', '', NOW(), NOW(), 0),
(129, 125, '故障删除', 'knowledge:fault:remove', 'button', 4, '', '', '', NOW(), NOW(), 0),
(130, 125, '故障详情', 'knowledge:fault:view',   'button', 5, '', '', '', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 3. 角色绑定（幂等）
--    role 1/2：全绑 124-130（管理 + 查看）
--    role 3/4：绑 124+125+126+130（可查看列表/详情，无增删改）
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id BETWEEN 124 AND 130;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 124), (1, 125), (1, 126), (1, 127), (1, 128), (1, 129), (1, 130);
DELETE FROM sys_role_permission WHERE role_id = 2 AND permission_id BETWEEN 124 AND 130;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2, 124), (2, 125), (2, 126), (2, 127), (2, 128), (2, 129), (2, 130);
DELETE FROM sys_role_permission WHERE role_id = 3 AND permission_id BETWEEN 124 AND 130;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3, 124), (3, 125), (3, 126), (3, 130);
DELETE FROM sys_role_permission WHERE role_id = 4 AND permission_id BETWEEN 124 AND 130;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(4, 124), (4, 125), (4, 126), (4, 130);

-- ---------------------------------------------------------------------
-- 4. 演示数据（租户1 常见故障 3 条）
-- ---------------------------------------------------------------------
INSERT INTO fault_article (id, tenant_id, title, category, device_type, brand, model, symptom, cause, solution, keywords, severity_ref, view_count, status, create_by, create_time, update_time, del_flag) VALUES
(1, 1, '设备离线排查指南（交换机/摄像头通用）', '设备离线', 'all', NULL, NULL,
 '设备在监控平台显示离线，PING/SNMP 均无响应，平台告警"设备离线故障"。',
 '1. 网线/光纤松动或损坏；2. 设备断电；3. 交换机端口故障；4. IP 冲突或 VLAN 配置错误；5. 设备死机。',
 '1. 现场查看设备指示灯是否点亮；2. 检查网线/光纤连接并插拔重试；3. 用笔记本直连设备测试连通性；4. 检查交换机端口、VLAN 与 IP 配置；5. 仍无法恢复时更换备件测试；6. 恢复后在平台确认在线状态。',
 '离线,断网,ping不通,设备无响应', 'critical', 15, 1, 'admin', NOW(), NOW(), 0),
(2, 1, '视频画面黑屏/无信号处理', '视频故障', 'camera', '大华,海康', NULL,
 '摄像头画面黑屏或无视频信号，录像中断。',
 '1. 镜头被遮挡；2. 摄像机供电异常；3. 网络带宽不足或丢包；4. 摄像机死机；5. NVR 通道配置错误。',
 '1. 检查镜头是否被遮挡、清洁；2. 重启摄像机电源；3. 测试网络丢包率（ping -t）；4. 检查交换机端口速率与带宽；5. 断电重启摄像机，等待自动注册；6. 检查 NVR 通道配置。',
 '黑屏,无信号,视频中断,摄像头', 'warning', 9, 1, 'admin', NOW(), NOW(), 0),
(3, 1, '门禁刷卡无响应排查', '门禁故障', 'door_controller', NULL, NULL,
 '门禁控制器刷卡无响应、无法开门或记录不上传。',
 '1. 读卡器故障或接线松动；2. 控制器死机；3. 电锁电源异常；4. 线路接触不良；5. 门禁平台离线。',
 '1. 重启门禁控制器；2. 检查读卡器接线与指示灯；3. 测试电锁电源电压；4. 检查控制器到平台的网络链路；5. 更换读卡器测试。',
 '门禁,刷卡,开门失败,读卡器', 'warning', 6, 1, 'admin', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 5. 校验查询
-- ---------------------------------------------------------------------
SELECT id, perm_name, perm_key, icon FROM sys_permission WHERE id BETWEEN 124 AND 130 AND del_flag = 0;
SELECT role_id, COUNT(*) cnt FROM sys_role_permission WHERE permission_id BETWEEN 124 AND 130 GROUP BY role_id ORDER BY role_id;
