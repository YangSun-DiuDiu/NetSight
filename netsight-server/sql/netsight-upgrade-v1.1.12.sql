-- =====================================================================
-- NetSight（NAMS）V1.1.12 增量升级脚本（demo 功能 1/5：通知公告）
-- 公告管理（发布/下线/置顶/过期）+ 用户端已读/未读（顶部铃铛）
-- 适用：192.168.1.55 MySQL（库 netsight）
-- 注意：权限/菜单 INSERT 一律显式指定 id，避免 auto_increment 错位
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 通知公告表
--    status: 0 草稿 / 1 已发布 / 2 已下线
--    level : normal 普通 / important 重要 / urgent 紧急
--    notice_type: notice 公告 / notify 通知
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID（多租户隔离）',
    title VARCHAR(128) NOT NULL COMMENT '公告标题',
    content TEXT COMMENT '公告内容（支持换行/富文本）',
    notice_type VARCHAR(16) NOT NULL DEFAULT 'notice' COMMENT '类型：notice 公告 / notify 通知',
    level VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '级别：normal 普通 / important 重要 / urgent 紧急',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 草稿 / 1 已发布 / 2 已下线',
    is_top TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶：0 否 / 1 是',
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间（发布时自动写入）',
    expire_time DATETIME DEFAULT NULL COMMENT '过期时间（空=永久有效，过期后用户端不可见）',
    publisher_id BIGINT DEFAULT NULL COMMENT '发布人用户ID',
    publisher_name VARCHAR(64) DEFAULT NULL COMMENT '发布人姓名（快照）',
    read_count INT NOT NULL DEFAULT 0 COMMENT '已读数（冗余统计）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '0正常/2已删',
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_publish_time (publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知公告';

-- ---------------------------------------------------------------------
-- 2. 公告已读记录表（用户端点击详情自动标记已读）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notice_read (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    notice_id BIGINT NOT NULL COMMENT '公告ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    read_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '阅读时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_notice_user (tenant_id, notice_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告已读记录';

-- ---------------------------------------------------------------------
-- 3. 菜单/权限（显式 id，从 103 开始）
--    103 通知公告顶级（icon=el-icon-message，Element 内置，已确认存在）
--    104 公告管理子菜单（path='list' → 动态路由 name='List'，无冲突）
--    105-109 按钮权限
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(103, 0,   '通知公告', 'notice:menu',   'menu',   1, '/notice', 'Layout',          'el-icon-message', NOW(), NOW(), 0),
(104, 103, '公告管理', 'notice:list:menu','menu', 1, 'list',    'notice/index',    'documentation',   NOW(), NOW(), 0),
(105, 104, '公告查询', 'notice:list',    'button', 1, '',        '',               '',                NOW(), NOW(), 0),
(106, 104, '公告新增', 'notice:add',     'button', 2, '',        '',               '',                NOW(), NOW(), 0),
(107, 104, '公告修改', 'notice:edit',    'button', 3, '',        '',               '',                NOW(), NOW(), 0),
(108, 104, '公告删除', 'notice:remove',  'button', 4, '',        '',               '',                NOW(), NOW(), 0),
(109, 104, '发布/下线', 'notice:publish','button', 5, '',        '',               '',                NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 4. super_admin（role_id=1）绑定新增 7 条权限（103-109）
--    先删除可能存在的旧绑定，再显式插入，保证幂等
--    注：租户管理员/运维/维修人员无管理菜单，通过顶部铃铛查看已发布公告
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id >= 103 AND permission_id <= 109;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 103), (1, 104), (1, 105), (1, 106), (1, 107), (1, 108), (1, 109);

-- ---------------------------------------------------------------------
-- 5. 演示数据（租户1：一条已发布公告 + 一条草稿）
-- ---------------------------------------------------------------------
INSERT INTO notice (id, tenant_id, title, content, notice_type, level, status, is_top, publish_time, expire_time, publisher_id, publisher_name, read_count, del_flag) VALUES
(1, 1, 'NetSight 系统上线公告', 'NetSight 网络资产监控管理系统 V1.2 已正式上线，欢迎使用！\n新增功能：通知公告、点检巡检、知识库故障库等，详见帮助中心。', 'notice', 'important', 1, 1, NOW(), NULL, 1, 'admin', 0, 0),
(2, 1, '关于开展季度设备点检的通知', '请各租户管理员在季度末前完成所有在线设备的点检巡检工作。', 'notify', 'normal', 0, 0, NULL, NULL, 1, 'admin', 0, 0);

-- ---------------------------------------------------------------------
-- 6. 校验
-- ---------------------------------------------------------------------
-- 应返回 7 行权限 + 2 条公告
SELECT id, perm_name, perm_key, icon FROM sys_permission WHERE id BETWEEN 103 AND 109 AND del_flag = 0;
SELECT id, title, status FROM notice WHERE del_flag = 0;
