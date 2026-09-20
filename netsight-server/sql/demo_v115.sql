-- ============================================================
-- NetSight 第 6 周演示数据（大屏 + 拓扑图）
-- 网关 1 台 + 设备 8 台（核心→汇聚→接入→终端 分层 + 主备线路）
-- 幂等：先清理 tenant_id=1 的设备/拓扑，再重建
-- ============================================================

DELETE FROM device_topology WHERE tenant_id = 1;
DELETE FROM device WHERE tenant_id = 1;
DELETE FROM edge_gateway WHERE id = 4;

-- 边缘网关（在线，id=4 避开历史逻辑删除记录）
INSERT INTO edge_gateway (id, tenant_id, gateway_code, gateway_name, location, link_type, gateway_token, ip_address, online_status, last_heartbeat_time, status, create_time, update_time, del_flag) VALUES
(4, 1, 'GW202609110004', '厂区边缘网关', '厂区机房', 'wired', 'e3f2a1b4c5d6e7f8091a2b3c4d5e6f70', '192.168.1.60', 1, NOW(), 1, NOW(), NOW(), 0);

-- 设备（分层：核心0 → 汇聚1 → 接入2 → 终端3；状态：1在线 0离线，line_status 1=链路异常）
INSERT INTO device (id, tenant_id, device_code, device_name, device_type, brand, model, ip_address, location, status, line_status, gateway_id, create_time, update_time, del_flag) VALUES
(1, 1, 'DEV-SW-CORE-01', '核心交换机', 'network', 'H3C', 'S7510E', '192.168.1.1', '厂区机房A区', 1, 0, 4, NOW(), NOW(), 0),
(2, 1, 'DEV-SW-AGG-01', '汇聚交换机A', 'network', '华为', 'S6730', '192.168.1.2', '厂区机房A区', 1, 0, 4, NOW(), NOW(), 0),
(3, 1, 'DEV-SW-AGG-02', '汇聚交换机B', 'network', '华为', 'S5735', '192.168.1.3', '厂区机房B区', 1, 0, 4, NOW(), NOW(), 0),
(4, 1, 'DEV-SW-ACC-01', '接入交换机', 'network', '华为', 'S1730S', '192.168.1.10', '车间一层弱电间', 1, 1, 4, NOW(), NOW(), 0),
(5, 1, 'DEV-CAM-01', '车间球机', 'camera', '海康威视', 'DS-2DC4423', '192.168.1.20', '车间一层东南角', 1, 0, 4, NOW(), NOW(), 0),
(6, 1, 'DEV-CAM-02', '车间枪机', 'camera', '大华', 'DH-IPC-HFW', '192.168.1.21', '车间一层西侧', 0, 0, 4, NOW(), NOW(), 0),
(7, 1, 'DEV-NVR-01', 'NVR录像机', 'nvr', '海康威视', 'DS-9632', '192.168.1.30', '厂区机房A区', 1, 0, 4, NOW(), NOW(), 0),
(8, 1, 'DEV-DOR-01', '门禁控制器', 'door_controller', '中控智慧', 'inBioX', '192.168.1.40', '车间一层北门', 1, 0, 4, NOW(), NOW(), 0);

-- 拓扑（1主2备上级关系：device_id 指向父设备）
INSERT INTO device_topology (id, tenant_id, device_id, parent_main_id, parent_backup1_id, parent_backup2_id, create_time, update_time, del_flag) VALUES
(1, 1, 2, 1, NULL, NULL, NOW(), NOW(), 0),   -- 汇聚A ← 核心
(2, 1, 3, 1, NULL, NULL, NOW(), NOW(), 0),   -- 汇聚B ← 核心
(3, 1, 4, 2, 3, NULL, NOW(), NOW(), 0),      -- 接入 ← 汇聚A(主) + 汇聚B(备1)
(4, 1, 5, 4, NULL, NULL, NOW(), NOW(), 0),   -- 球机 ← 接入
(5, 1, 6, 4, 3, NULL, NOW(), NOW(), 0),      -- 枪机 ← 接入(主) + 汇聚B(备1)
(6, 1, 7, 2, NULL, NULL, NOW(), NOW(), 0),   -- NVR ← 汇聚A
(7, 1, 8, 4, NULL, NULL, NOW(), NOW(), 0);   -- 门禁 ← 接入
