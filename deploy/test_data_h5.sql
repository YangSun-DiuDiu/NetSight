-- ============================================================
-- H5 维修工单功能 - 多租户多账号测试数据准备
-- ============================================================
USE netsight;

-- 先清理可能存在的旧测试数据
DELETE FROM work_order WHERE order_no LIKE 'WO-H5-%';
DELETE FROM spare_part WHERE serial_no LIKE 'SN-H5-%';
DELETE FROM repairer WHERE phone IN ('13600000002');
DELETE FROM sys_user WHERE username IN ('zhang_shifu','li_shifu','h5_admin','h5_repairer');
DELETE FROM sys_tenant WHERE tenant_name='H5测试租户';

-- ========== 1. 租户1：创建维修人员 sys_user 账号 ==========
INSERT INTO sys_user (username, password, phone, tenant_id, status, create_time, update_time, del_flag)
VALUES ('zhang_shifu', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000001', 1, 1, NOW(), NOW(), 0);

INSERT INTO sys_user (username, password, phone, tenant_id, status, create_time, update_time, del_flag)
VALUES ('li_shifu', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000002', 1, 1, NOW(), NOW(), 0);

INSERT INTO sys_user_role (user_id, role_id) SELECT id, 4 FROM sys_user WHERE username IN ('zhang_shifu', 'li_shifu');

-- ========== 2. 租户7：修复 repairer_test 与 repairer 表 phone 匹配 ==========
UPDATE repairer SET phone='15700000002' WHERE id=8;

-- ========== 3. 新建租户8：完整测试 ==========
INSERT INTO sys_tenant (tenant_name, contact_person, contact_phone, status, webhook_token, create_time, update_time, del_flag)
VALUES ('H5测试租户', '测试员', '13600000001', 1, 'h5_test_webhook_token_001', NOW(), NOW(), 0);
SET @tenant8 = LAST_INSERT_ID();

INSERT INTO sys_user (username, password, phone, tenant_id, status, create_time, update_time, del_flag)
VALUES ('h5_admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13600000001', @tenant8, 1, NOW(), NOW(), 0);
INSERT INTO sys_user_role (user_id, role_id) SELECT id, 2 FROM sys_user WHERE username='h5_admin';

INSERT INTO sys_user (username, password, phone, tenant_id, status, create_time, update_time, del_flag)
VALUES ('h5_repairer', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13600000002', @tenant8, 1, NOW(), NOW(), 0);
INSERT INTO sys_user_role (user_id, role_id) SELECT id, 4 FROM sys_user WHERE username='h5_repairer';

INSERT INTO repairer (repairer_no, name, phone, tenant_id, status, create_time, update_time, del_flag)
VALUES ('RP-H5-001', '王师傅', '13600000002', @tenant8, 1, NOW(), NOW(), 0);

INSERT INTO spare_part (part_no, part_type, brand, model, serial_no, quantity, unit, status, tenant_id, create_time, update_time, del_flag)
VALUES ('SP-H5-001', '硬盘', '希捷', 'ST4000VX007', 'SN-H5-001', 5, '块', 'new', @tenant8, NOW(), NOW(), 0);

-- ========== 4. 创建测试工单 ==========

-- 租户1 张师傅的工单
INSERT INTO work_order (order_no, tenant_id, fault_type, severity, device_name, device_location, description, status, repairer_id, repairer_name, dispatch_time, create_time, update_time, del_flag)
VALUES ('WO-H5-T1-Z-001', 1, 'offline', 'critical', 'POE交换机-1楼', '1楼机房', 'POE交换机无法ping通，疑似电源故障', 1, 5, '张师傅', NOW(), NOW(), NOW(), 0);

-- 租户1 李师傅的工单
INSERT INTO work_order (order_no, tenant_id, fault_type, severity, device_name, device_location, description, status, repairer_id, repairer_name, dispatch_time, create_time, update_time, del_flag)
VALUES ('WO-H5-T1-L-001', 1, 'line_abnormal', 'warning', '光纤收发器-A栋', 'A栋弱电井', '光纤链路衰减严重，错包率高', 1, 6, '李师傅', NOW(), NOW(), NOW(), 0);

-- 租户7 维修人员的工单
INSERT INTO work_order (order_no, tenant_id, fault_type, severity, device_name, device_location, description, status, repairer_id, repairer_name, dispatch_time, create_time, update_time, del_flag)
VALUES ('WO-H5-T7-001', 7, 'offline', 'critical', '大华摄像头-南门', '南门岗亭', '南门摄像头离线，需现场检查', 1, 8, 'sunyang账号下的维修人员', NOW(), NOW(), NOW(), 0);

-- 租户8 王师傅的工单
SET @repairer8 = (SELECT id FROM repairer WHERE tenant_id=@tenant8 AND phone='13600000002');
INSERT INTO work_order (order_no, tenant_id, fault_type, severity, device_name, device_location, description, status, repairer_id, repairer_name, dispatch_time, create_time, update_time, del_flag)
VALUES ('WO-H5-T8-001', @tenant8, 'manual', 'warning', 'NVR录像机-1号', '监控中心', 'NVR硬盘告警，需要更换硬盘', 1, @repairer8, '王师傅', NOW(), NOW(), NOW(), 0);

-- ========== 验证 ==========
SELECT '=== 维修人员账号对应关系 ===' AS info;
SELECT u.id AS user_id, u.username, u.phone, u.tenant_id, r.id AS repairer_id, r.name AS repairer_name
FROM sys_user u
JOIN repairer r ON u.phone = r.phone AND u.tenant_id = r.tenant_id
WHERE u.del_flag=0 AND r.del_flag=0
ORDER BY u.tenant_id, u.id;

SELECT '=== 待测试工单 ===' AS info;
SELECT id, order_no, tenant_id, status, repairer_id, device_name, fault_type
FROM work_order
WHERE order_no LIKE 'WO-H5-%'
ORDER BY tenant_id, id;
